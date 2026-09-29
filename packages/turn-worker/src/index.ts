export interface Env {
  TURN_KEY_ID: string;
  TURN_KEY_API_TOKEN: string;
  /** Network id(s) this worker serves, comma-separated. */
  ALLOWED_NETWORK_ID: string;
}

// How long a minted credential stays valid. Fetched fresh per call rather
// than cached, so this only needs to comfortably outlast one viewing
// session — 24h covers even a long-running open viewer with room to spare,
// well under Cloudflare's 172800s (48h) max.
const CREDENTIAL_TTL_SECONDS = 86_400;

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: CORS_HEADERS });
    }

    // Anyone who finds this URL could otherwise mint credentials against
    // this Cloudflare account's quota — only serve the owner's own network
    // id(s), the same unguessable value every device of that network knows.
    const room = new URL(request.url).searchParams.get('room') ?? '';
    const allowed = (env.ALLOWED_NETWORK_ID ?? '').split(',').map((s) => s.trim()).filter(Boolean);
    if (!room || !allowed.includes(room)) {
      return new Response('forbidden', { status: 403, headers: CORS_HEADERS });
    }

    const resp = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ ttl: CREDENTIAL_TTL_SECONDS }),
      },
    );

    if (!resp.ok) {
      return new Response('turn credential generation failed', { status: 502, headers: CORS_HEADERS });
    }

    const body = await resp.text();
    return new Response(body, {
      headers: { ...CORS_HEADERS, 'Content-Type': 'application/json' },
    });
  },
};
