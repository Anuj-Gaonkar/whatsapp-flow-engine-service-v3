# whatsapp-flow-engine-service-v3

Standalone Spring Boot service that drives WhatsApp Flows end-to-end - multiple, independently
registered flows (e.g. `AMB_REMINDER`, `INACTIVE_SALARY`) sharing one engine, one database, one
encryption keypair. Modeled on `whatsapp-flow-engine-service` (V2) but restructured into a normal
layered package layout (`controller` / `service` / `repository` / `entity` / `model`) and with its
own, fully isolated Postgres database.

## Documentation

| Document | What it covers |
|---|---|
| [`SETUP_GUIDE.md`](SETUP_GUIDE.md) | **Start here on a new machine.** Docker (Postgres + Temporal via `docker-compose.yml`), running the app, `curl` smoke tests, exposing it with ngrok, pointing Meta at it, troubleshooting. |
| [`REMINDER_SERVICES_GUIDE.md`](REMINDER_SERVICES_GUIDE.md) | Complete guide to this service **and** `temporal-workflow-service`: architecture, the end-to-end reminder flow, every endpoint/table/class, DEMO vs PRODUCTION reminder timing (`FUNDS_REMINDER_MODE`), logging, setup, troubleshooting, known limitations. |
| [`TEMPORAL_GUIDE.md`](TEMPORAL_GUIDE.md) | Everything Temporal: mental model, wiring, the real event history of a reminder, replay/determinism, retries, the Temporal UI, safe workflow changes, testing. |
| [`AMB_REMINDER_FLOW_JOURNEY.md`](AMB_REMINDER_FLOW_JOURNEY.md), [`AMB_REMINDER_RADIO_FLOW_JOURNEY.md`](AMB_REMINDER_RADIO_FLOW_JOURNEY.md) | Screen-by-screen `/screen` walkthroughs of each registered flow. |

## Quick start

```bash
docker compose up -d postgres        # Postgres on :5434, schema + both flows loaded automatically
./mvnw spring-boot:run               # .\mvnw.cmd on Windows - service on :8082 (needs Java 21)
curl localhost:8082/actuator/health  # {"status":"UP",...}
```

Then drive a flow with `curl` against `/screen` - no WhatsApp, keys or Temporal needed. Temporal
(`docker compose up -d`) and the separate `temporal-workflow-service` are only needed for the funds
reminder step; without them that step logs `Could not schedule funds reminder` and the flow carries
on. For a real WhatsApp test (ngrok, Meta configuration, RSA key) see
[`SETUP_GUIDE.md`](SETUP_GUIDE.md).

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET`/`POST` | `/webhook` | Classic Cloud API messaging webhook. Plain JSON, HMAC-signed (`X-Hub-Signature-256`) - Meta signs every request to a registered endpoint, but only the Flow data-endpoint below is RSA/AES-encrypted; this one carries the terminal screen's `nfm_reply` once the customer taps "Done". |
| `POST` | `/webhook/flow`, `/webhook/flow/{flowKey}` | The Flow data-endpoint - Meta's real encrypted contract (RSA-OAEP-wrapped AES key + AES-128-GCM body), registered as a Flow object's own `endpoint_uri`. Handles `INIT`/`data_exchange`/`BACK`/`ping`. |
| `POST` | `/screen`, `/screen/{flowKey}` | Plain-JSON twin of `/webhook/flow` - same actions, no encryption, no signature check. For local testing without RSA keys or HMAC signing. |
| `POST` | `/trigger` | Sends a Flow to a WhatsApp number: `{"to": "9199...", "flowKey": "AMB_REMINDER"}`. `flowKey` optional, defaults to `flows.default-flow-key`. |
| `GET` | `/sessions/{flowToken}/history` | One Flow instance's full interaction history, in order. |
| `GET` | `/session/{waId}/history` | Every Flow instance that customer has ever had, each with its own history attached. |
| `GET` | `/sessions/{flowToken}/conversation` | UI-friendly view of the same session: each step walked plus the options offered, with the one chosen marked. |
| `GET` | `/flows/{flowKey}/graph` | A whole flow as nodes and edges - what `static/flow-graph.html` (`/flow-graph.html`) draws. |
| `POST` | `/messages/reminder` | Internal: sends a plain WhatsApp text `{"waId","message"}`. Called by `temporal-workflow-service` when a reminder falls due. |
| `GET` | `/actuator/health`, `/swagger-ui.html` | Health check; OpenAPI UI for every endpoint above. |

See `AMB_REMINDER_FLOW_JOURNEY.md` and `AMB_REMINDER_RADIO_FLOW_JOURNEY.md` for a full,
copy-pasteable screen-by-screen walkthrough of each registered flow via `/screen`.

## Registering a new Flow

No code change needed - add a block to `flows.definitions` in `application.yaml` (entry screen id,
Meta Flow ID, draft/published mode, CTA label, opening message text), then seed that flow's
`flow_node`/`flow_transition` rows with a script like `db/02_seed_amb_reminder_example.sql`. See
`FlowRegistry`/`FlowEngineService` for how a `flow_key` ties the two together - `flow_node.flow_code`
is a plain label, every lookup at runtime goes by `screen_id`/`node_id` (globally unique across
every registered flow), never by `flow_code` or the request path.

## Database

**With Docker (recommended):** `docker compose up -d postgres` creates `chatbot_v3` and runs every
script in `db/` on first boot, so there is nothing else to do. To start over: `docker compose down -v`.

**Against an existing Postgres:** own database (`chatbot_v3` by default), same Postgres instance V2 uses, own schema
(`flow_engine`) - no tables shared with V2 or anything else. `spring.jpa.hibernate.ddl-auto` is
`validate`, not `update`: this service never creates or alters schema itself. Run, in order,
before starting the app:

```
psql -h localhost -p 5434 -U <a role that can CREATE DATABASE> -d postgres -c "CREATE DATABASE chatbot_v3 OWNER chatbot;"
psql "postgresql://chatbot:chatbot@localhost:5434/chatbot_v3" -f db/01_schema.sql
psql "postgresql://chatbot:chatbot@localhost:5434/chatbot_v3" -f db/02_seed_amb_reminder_example.sql
psql "postgresql://chatbot:chatbot@localhost:5434/chatbot_v3" -f db/03_seed_amb_reminder_radio_example.sql
```

`db/02_...` seeds `AMB_REMINDER` (`amb_reminder_flow.json`, `FULL_*` screens, node_ids
1000-1210). `db/03_...` seeds `AMB_REMINDER_RADIO` (`amb_reminder_radio_flow.json`, `PRO_*`
screens, node_ids 2000-2170) - the same journey redesigned with native `RadioButtonsGroup`
screens instead of chat-lifted ack-then-menu pairs. Both are already registered in
`application.yaml`'s `flows.definitions`. The next new flow should use node_ids 3000+.

Tables: `flow_node`, `flow_transition`, `flow_session`, `flow_node_history` - identical shape to
V2's (see `db/01_schema.sql`), just in an isolated database.

## Encryption keys

`keys/` is **git-ignored** - it is not in a clone; ask the repo owner for `private_plain.pem` (or
generate your own pair and register it with Meta: `SETUP_GUIDE.md` section 6.4). Only real Meta
traffic on `/webhook/flow` needs it; `/screen` testing and app startup do not.

`keys/` holds the same RSA keypair as V2's (`private.pem` passphrase-encrypted,
`private_plain.pem` the pre-decrypted PKCS8 form the app actually reads by default, `public.pem`
the half already registered with Meta) - both services can share it since the keypair is
registered per phone number, not per service. `whatsapp.rsa-private-key-path` points at
`keys/private_plain.pem` by default.

## Config

All of `whatsapp.*` and `flows.*` in `application.yaml` are env-var backed, but the *defaults* written
in the file are the author's test-account values - override them (`WA_ACCESS_TOKEN`, `WA_APP_SECRET`,
`WA_PHONE_NUMBER_ID`, ... - full list in `SETUP_GUIDE.md` section 6.3) and don't reuse them for anything
real. `FUNDS_REMINDER_MODE` (`DEMO` minutes / `PRODUCTION` days) and `TEMPORAL_WORKFLOW_SERVICE_BASE_URL`
are env-var backed too. Server runs on port `8082` (V2 uses `8081`, chat-bot-service uses `8080`) so all three
can run side by side locally.
