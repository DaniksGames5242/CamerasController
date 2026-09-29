// A "network" is everything one person's devices need to find each other:
// their own Firebase project (signaling), an unguessable network id (the
// path under /rooms that only their devices know) and, optionally, their own
// TURN relay. Nothing of this is baked into a build — every install gets it
// at first launch, either by creating a new network or by joining one with
// an invite code produced on a device that is already in it.

export interface FirebaseWebConfig {
  apiKey: string;
  authDomain: string;
  databaseURL: string;
  projectId: string;
  appId: string;
}

export interface TurnConfig {
  /** URL of a deployed packages/turn-worker (mints short-lived Cloudflare TURN credentials). */
  workerUrl?: string;
  /** Static TURN servers (Metered, own coturn, …) — used as-is. */
  servers?: RTCIceServer[];
}

export interface NetworkConfig {
  v: 1;
  /** Human label only — shown in the apps, never used for access. */
  name: string;
  firebase: FirebaseWebConfig;
  networkId: string;
  turn?: TurnConfig;
}

let current: NetworkConfig | null = null;

export function setNetwork(cfg: NetworkConfig) {
  current = cfg;
}

export function network(): NetworkConfig {
  if (!current) throw new Error('network is not configured yet');
  return current;
}

// ---------- invite codes ----------

const INVITE_PREFIX = 'CC1.';

function toBase64Url(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let bin = '';
  bytes.forEach((b) => (bin += String.fromCharCode(b)));
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(s: string): string {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4);
  const bin = atob(b64);
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
}

/**
 * The invite code carries the whole network config. Whoever holds it can
 * add a device to the network — so it is a password, and the apps say so
 * wherever they show it.
 */
export function encodeInvite(cfg: NetworkConfig): string {
  return INVITE_PREFIX + toBase64Url(JSON.stringify(cfg));
}

export function decodeInvite(code: string): NetworkConfig {
  const clean = code.replace(/\s+/g, '');
  if (!clean.startsWith(INVITE_PREFIX)) throw new Error('Это не код приглашения CamerasController');
  let parsed: unknown;
  try {
    parsed = JSON.parse(fromBase64Url(clean.slice(INVITE_PREFIX.length)));
  } catch {
    throw new Error('Код повреждён — скопируйте его целиком');
  }
  return validateNetwork(parsed);
}

// ---------- building / validating ----------

const REQUIRED_FIREBASE_KEYS: (keyof FirebaseWebConfig)[] = ['apiKey', 'authDomain', 'databaseURL', 'projectId', 'appId'];

/**
 * Accepts what people actually paste from the Firebase console: plain JSON,
 * or the JS snippet (`const firebaseConfig = { apiKey: "…", … };`) with
 * unquoted keys, single quotes and trailing commas.
 */
export function parseFirebaseSnippet(text: string): FirebaseWebConfig {
  const out: Record<string, string> = {};
  const re = /["']?(\w+)["']?\s*:\s*["']([^"']*)["']/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) out[m[1]] = m[2].trim();
  const missing = REQUIRED_FIREBASE_KEYS.filter((k) => !out[k]);
  if (missing.includes('databaseURL') && out.projectId) {
    // Realtime Database was probably created after the web app was registered
    // — the console snippet then omits it. Don't guess the region: ask.
    throw new Error('В конфиге нет databaseURL. Создайте Realtime Database и скопируйте конфиг заново (или вставьте адрес базы вручную).');
  }
  if (missing.length) throw new Error(`В конфиге не хватает полей: ${missing.join(', ')}`);
  return {
    apiKey: out.apiKey,
    authDomain: out.authDomain,
    databaseURL: out.databaseURL.replace(/\/+$/, ''),
    projectId: out.projectId,
    appId: out.appId,
  };
}

export function newNetworkId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export function validateNetwork(raw: unknown): NetworkConfig {
  const c = raw as Partial<NetworkConfig>;
  if (!c || c.v !== 1) throw new Error('Неподдерживаемая версия конфигурации сети');
  if (!c.networkId || !/^[a-f0-9]{32}$/.test(c.networkId)) throw new Error('Некорректный идентификатор сети');
  const fb = c.firebase as Partial<FirebaseWebConfig> | undefined;
  if (!fb || REQUIRED_FIREBASE_KEYS.some((k) => typeof fb[k] !== 'string' || !fb[k])) {
    throw new Error('В конфигурации сети нет данных Firebase');
  }
  if (!/^https:\/\//.test(fb.databaseURL!)) throw new Error('databaseURL должен начинаться с https://');
  const turn: TurnConfig = {};
  if (c.turn?.workerUrl) {
    if (!/^https:\/\//.test(c.turn.workerUrl)) throw new Error('Адрес TURN-воркера должен начинаться с https://');
    turn.workerUrl = c.turn.workerUrl;
  }
  if (Array.isArray(c.turn?.servers) && c.turn!.servers.length) turn.servers = parseTurnServers(c.turn!.servers);
  return {
    v: 1,
    name: (c.name || 'Моя сеть').slice(0, 60),
    firebase: fb as FirebaseWebConfig,
    networkId: c.networkId,
    ...(turn.workerUrl || turn.servers ? { turn } : {}),
  };
}

/** Static TURN list: a JSON array of RTCIceServer, or a single object. */
export function parseTurnServers(input: unknown): RTCIceServer[] {
  const val = typeof input === 'string' ? JSON.parse(input) : input;
  const list = (Array.isArray(val) ? val : [val]) as RTCIceServer[];
  for (const s of list) {
    const urls = Array.isArray(s.urls) ? s.urls : [s.urls];
    if (!urls.length || urls.some((u) => typeof u !== 'string' || !/^(turns?|stun):/.test(u))) {
      throw new Error('У TURN-сервера должно быть поле urls вида turn:host:port');
    }
  }
  return list.map((s) => ({ urls: s.urls, username: s.username, credential: s.credential }));
}
