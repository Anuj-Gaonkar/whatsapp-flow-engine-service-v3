# whatsapp-flow-engine-service-v3

Standalone Spring Boot service that drives WhatsApp Flows end-to-end - multiple, independently
registered flows (e.g. `AMB_REMINDER`, `INACTIVE_SALARY`) sharing one engine, one database, one
encryption keypair. Modeled on `whatsapp-flow-engine-service` (V2) but restructured into a normal
layered package layout (`controller` / `service` / `repository` / `entity` / `model`) and with its
own, fully isolated Postgres database.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET`/`POST` | `/webhook` | Classic Cloud API messaging webhook. Plain JSON, HMAC-signed (`X-Hub-Signature-256`) - Meta signs every request to a registered endpoint, but only the Flow data-endpoint below is RSA/AES-encrypted; this one carries the terminal screen's `nfm_reply` once the customer taps "Done". |
| `POST` | `/webhook/flow`, `/webhook/flow/{flowKey}` | The Flow data-endpoint - Meta's real encrypted contract (RSA-OAEP-wrapped AES key + AES-128-GCM body), registered as a Flow object's own `endpoint_uri`. Handles `INIT`/`data_exchange`/`BACK`/`ping`. |
| `POST` | `/screen`, `/screen/{flowKey}` | Plain-JSON twin of `/webhook/flow` - same actions, no encryption, no signature check. For local testing without RSA keys or HMAC signing. |
| `POST` | `/trigger` | Sends a Flow to a WhatsApp number: `{"to": "9199...", "flowKey": "AMB_REMINDER"}`. `flowKey` optional, defaults to `flows.default-flow-key`. |
| `GET` | `/sessions/{flowToken}/history` | One Flow instance's full interaction history, in order. |
| `GET` | `/session/{waId}/history` | Every Flow instance that customer has ever had, each with its own history attached. |

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

Own database (`chatbot_v3` by default), same Postgres instance V2 uses, own schema
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

`keys/` holds the same RSA keypair as V2's (`private.pem` passphrase-encrypted,
`private_plain.pem` the pre-decrypted PKCS8 form the app actually reads by default, `public.pem`
the half already registered with Meta) - both services can share it since the keypair is
registered per phone number, not per service. `whatsapp.rsa-private-key-path` points at
`keys/private_plain.pem` by default.

## Config

All of `whatsapp.*` and `flows.*` in `application.yaml` are env-var backed - nothing sensitive is
hardcoded. Server runs on port `8082` (V2 uses `8081`, chat-bot-service uses `8080`) so all three
can run side by side locally.
