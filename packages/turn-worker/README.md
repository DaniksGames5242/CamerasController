# turn-worker

A tiny Cloudflare Worker that mints short-lived Cloudflare Realtime TURN
credentials for **your own** CamerasController network, so the Cloudflare API
token never ships inside the apps.

Step-by-step setup (in Russian): [docs/TURN.md](../../docs/TURN.md), option B.

Secrets (`npx wrangler secret put <NAME>`):

| Name | Value |
|---|---|
| `TURN_KEY_ID` | Cloudflare Realtime TURN Key ID |
| `TURN_KEY_API_TOKEN` | Cloudflare Realtime TURN API token |
| `ALLOWED_NETWORK_ID` | your network id (shown on the "create network" screen); several — comma-separated |

Requests for any other network id get `403`, so a leaked worker URL alone
can't spend your quota.
