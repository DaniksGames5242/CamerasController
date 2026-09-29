import { network } from './network.js';

// STUN only by default: enough for most home networks. Anything behind a
// strict NAT or a mobile carrier needs a TURN relay — and every user brings
// their own (see docs/TURN.md): a shared public relay would be both
// unreliable and a way to route strangers' video through someone else.
const STUN: RTCIceServer[] = [
  { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] },
];

/**
 * ICE servers for a new call: STUN + the network's own TURN. A configured
 * packages/turn-worker is asked for short-lived Cloudflare credentials; if it
 * is slow or down the call still starts with whatever else is configured,
 * never blocking for more than a few seconds.
 */
export async function fetchIceServers(): Promise<RTCIceServer[]> {
  const turn = network().turn ?? {};
  const servers: RTCIceServer[] = [...STUN, ...(turn.servers ?? [])];
  if (!turn.workerUrl) return servers;
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 4000);
    const resp = await fetch(`${turn.workerUrl}?room=${encodeURIComponent(network().networkId)}`, {
      signal: controller.signal,
    });
    clearTimeout(timeout);
    if (!resp.ok) return servers;
    const data = (await resp.json()) as { iceServers?: RTCIceServer[] };
    return data.iceServers?.length ? [...servers, ...data.iceServers] : servers;
  } catch (err) {
    console.error('fetchIceServers: TURN worker unavailable, continuing without it', err);
    return servers;
  }
}

export async function createPeerConnection(): Promise<RTCPeerConnection> {
  return new RTCPeerConnection({ iceServers: await fetchIceServers() });
}
