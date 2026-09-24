# Setup guide - running this on your own machine (Docker + ngrok)

How to get `whatsapp-flow-engine-service-v3` running on a fresh machine and test it, from a plain
`curl` smoke test up to a real WhatsApp Flow on your phone. Steps 3-4 (Docker Postgres from a clean
volume, app start, `/screen` walkthrough) were run end to end before this was written. Steps 5-7
(Temporal, ngrok, Meta) follow the same project's existing setup but were not re-run from scratch
for this guide - if a command there doesn't behave as described, tell the repo owner.

> For *how the service works* (architecture, flow graph, reminder timeline, troubleshooting) see
> [`REMINDER_SERVICES_GUIDE.md`](REMINDER_SERVICES_GUIDE.md) and [`TEMPORAL_GUIDE.md`](TEMPORAL_GUIDE.md).
> This document is only about getting it running.

---

## 0. Pick how far you want to go

| Level | You get | You need | Steps |
|---|---|---|---|
| **A. Local smoke test** | The whole flow engine driven by `curl` against `/screen` - no WhatsApp involved | Java 21, Docker | 1-4 |
| **B. + Reminders** | The "remind me about my funds" step schedules a real Temporal timer | Level A + the separate `temporal-workflow-service` repo | 5 |
| **C. Real WhatsApp** | The Flow opens on a phone and Meta calls *your* laptop | Level A + ngrok + a Meta app / WhatsApp number | 6-7 |

Start with A. If A works, everything that can go wrong afterwards is a network or Meta-config
problem, not an application problem.

## 1. Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | **21** (the pom targets 21) | `java -version` |
| Docker Desktop / Engine | with Compose v2 | `docker compose version` |
| Git | any | `git --version` |
| ngrok | v3 (levels C only) | `ngrok version` |

You do **not** need Maven (the repo ships `mvnw` / `mvnw.cmd`, which downloads it on first run) and
you do **not** need `psql` (Postgres runs in Docker and every command below goes through
`docker exec`).

Free ports on your machine: **5434** (Postgres), **8082** (this service). Level B adds **7233**
(Temporal), **8088** (Temporal UI), **8083** (reminder service).

## 2. What is *not* in the git repo

The repo alone gets you level A. These three things are deliberately absent - ask the repo owner for
whichever you need:

| Missing | Needed for | Why it isn't in git |
|---|---|---|
| `keys/` (RSA keypair) | Level C only - decrypting Meta's Flow requests | Secret; `keys/` is git-ignored. See [6.4](#64-the-rsa-key-level-c). Not needed for `/screen` testing or for starting the app. |
| `temporal-workflow-service` | Level B | It is a separate project, currently a local git repo with no remote. |
| Meta credentials (token, app secret, Flow IDs) | Level C | Secrets. Defaults in `application.yaml` point at the owner's test WhatsApp account; override them with env vars ([6.3](#63-configuration-you-will-override)). |

## 3. Clone and start the database (Docker)

```bash
git clone https://github.com/Anuj-Gaonkar/whatsapp-flow-engine-service-v3.git
cd whatsapp-flow-engine-service-v3

docker compose up -d postgres
docker compose ps            # wait until flow-engine-postgres shows "healthy" (~10s)
```

`docker-compose.yml` starts Postgres 16 on host port **5434** with role `chatbot` / `chatbot` and
creates the database `chatbot_v3`. On first boot it also runs everything in `db/` in filename order
(`01_schema.sql`, `02_seed_amb_reminder_example.sql`, `03_seed_amb_reminder_radio_example.sql`),
so the schema and both flows are loaded for you.

Verify:

```bash
docker exec flow-engine-postgres psql -U chatbot -d chatbot_v3 \
  -c "select flow_code, count(*) from flow_engine.flow_node group by 1"
```

Expected: `AMB_REMINDER` = 22 nodes, `AMB_REMINDER_RADIO` = 18.

**Re-seeding / resetting.** The `db/` scripts only run on an *empty* data volume. To start over
(this deletes all sessions and history):

```bash
docker compose down -v && docker compose up -d postgres
```

To re-apply one script to a running database instead (all are idempotent or safe to re-run - check
the script header first):

```bash
docker exec -i flow-engine-postgres psql -U chatbot -d chatbot_v3 < db/03_seed_amb_reminder_radio_example.sql
```

> **Port 5434 already taken?** Change the left side of `"5434:5432"` in `docker-compose.yml`
> *and* the port in `spring.datasource.url` in `application.yaml` (or override with
> `SPRING_DATASOURCE_URL`).

## 4. Run the service and smoke-test it (level A)

```bash
./mvnw spring-boot:run            # macOS / Linux / Git Bash
.\mvnw.cmd spring-boot:run        # Windows PowerShell / cmd
```

First run downloads Maven and dependencies (a few minutes). It is ready when this returns `UP`:

```bash
curl http://localhost:8082/actuator/health
```

The app starts fine **without** `keys/` and without any WhatsApp credentials - those are only read
when a request actually needs them.

Now walk the radio-button flow with plain JSON (no Meta, no encryption, no signature):

```bash
curl -X POST localhost:8082/screen/AMB_REMINDER_RADIO -H "Content-Type: application/json" \
  -d '{"action":"data_exchange","flow_token":"T1","screen":"PRO_WELCOME_SCREEN","data":{}}'
# -> {"screen":"PRO_AMB_MENU_SCREEN","data":{}}

curl -X POST localhost:8082/screen/AMB_REMINDER_RADIO -H "Content-Type: application/json" \
  -d '{"action":"data_exchange","flow_token":"T1","screen":"PRO_AMB_MENU_SCREEN","data":{"amb_menu_option":"funds_shortly"}}'
```

Then look at what the engine recorded:

```
http://localhost:8082/sessions/T1/history          the path this token walked
http://localhost:8082/swagger-ui.html              every endpoint, try-it-out
http://localhost:8082/flow-graph.html              the flow drawn as a graph
```

`AMB_REMINDER_RADIO_FLOW_JOURNEY.md` and `AMB_REMINDER_FLOW_JOURNEY.md` have the full copy-paste
walkthrough of every screen and branch. **If this works, levels B and C are only wiring.**

## 5. Reminders with Temporal (level B)

The last screens of the flow ("remind me when my funds arrive") call a second service,
**`temporal-workflow-service`** (port 8083), which owns the durable timer. Without it the rest of the
flow works but that one step fails because the call to `:8083` has nothing to talk to.

Its code is **not in this repo** - get it from the repo owner and put it next to this one. Then:

**5.1 Temporal server + UI (Docker, from this repo):**

```bash
docker compose up -d temporal-postgres temporal temporal-ui
```

Give `temporal` ~30 seconds (it creates its schema on first boot). UI: <http://localhost:8088>.

**5.2 The reminder service's database** (same Postgres container, second database):

```bash
docker exec flow-engine-postgres psql -U chatbot -d postgres \
  -c "CREATE DATABASE chatbot_reminder OWNER chatbot;"
docker exec -i flow-engine-postgres psql -U chatbot -d chatbot_reminder \
  < ../temporal-workflow-service/db/01_schema.sql
```

**5.3 Start it**, in its own terminal, then (re)start this service:

```bash
cd ../temporal-workflow-service && ./mvnw spring-boot:run     # port 8083
```

Start order: Temporal -> `temporal-workflow-service` -> flow engine (only Temporal-before-reminder
is strict).

**5.4 Check the pipe** without any WhatsApp:

```bash
curl -X POST http://localhost:8083/reminders -H "Content-Type: application/json" \
  -d '{"waId":"919999999999","message":"test","remindAt":"2030-01-01T00:00:00Z"}'
```

A `201` plus a running workflow in the Temporal UI means it's wired. Delivery of the reminder text
at the end *does* call WhatsApp, so seeing it actually arrive needs level C.

**Reminder timing:** by default `FUNDS_REMINDER_MODE=DEMO` - the reminder fires after 3 / 5 / 7
**minutes** depending on the option picked, so you can watch it. `FUNDS_REMINDER_MODE=PRODUCTION`
uses 3 / 7 / 15 **days**.

## 6. Real WhatsApp: ngrok and Meta (level C)

### 6.1 Why ngrok

Meta calls your service over the public internet, over HTTPS. `localhost:8082` is invisible to it.
ngrok gives your laptop a public HTTPS URL that forwards to `localhost:8082`.

```bash
ngrok config add-authtoken <your token from https://dashboard.ngrok.com>   # once
ngrok http 8082
```

Copy the `https://<something>.ngrok-free.app` (or `.ngrok-free.dev`) URL it prints - call it
`$NGROK` below. Leave this terminal running.

* **The URL changes every time you restart ngrok** (free plan), and you must then update it in Meta
  again. Claim your free static domain at *ngrok dashboard -> Domains* and start with
  `ngrok http --url=<your-domain> 8082` to keep it stable.
* **Inspector:** <http://127.0.0.1:4040> shows every request Meta sends and your service's
  response, and lets you *replay* one. It is the fastest way to debug this setup.
* **Everything on 8082 is now public**, including `/trigger` (sends WhatsApp messages),
  `/screen` and the history endpoints, none of which are authenticated. Don't share the URL, and
  stop ngrok when you are done.

Check the tunnel end to end:

```bash
curl "$NGROK/actuator/health"        # {"status":"UP",...}
```

### 6.2 Point Meta at your tunnel

Two separate URLs, both in the Meta developer console for the WhatsApp app:

| What | Where in Meta | Value |
|---|---|---|
| **Messaging webhook** (receives the customer's final "Done" tap) | *WhatsApp -> Configuration -> Webhook -> Edit* | Callback URL `$NGROK/webhook`, verify token = `WA_VERIFY_TOKEN` (default `HDFCCERN`). Subscribe to the **messages** field. |
| **Flow data-endpoint** (receives every screen tap, encrypted) | *WhatsApp Manager -> Flows -> your flow -> Endpoint / Settings* | `$NGROK/webhook/flow/AMB_REMINDER` (or `.../AMB_REMINDER_RADIO`) |

When you press *Verify and save* on the webhook, Meta does a `GET /webhook?hub.mode=subscribe&hub.verify_token=...&hub.challenge=...`
to your tunnel; you can watch it in the ngrok inspector. You can rehearse it yourself:

```bash
curl "$NGROK/webhook?hub.mode=subscribe&hub.verify_token=HDFCCERN&hub.challenge=1234"   # -> 1234
```

> **Important - one Meta app points at one URL.** The webhook callback URL is a single setting per
> Meta app, and a Flow has a single endpoint URI. If you use the owner's Meta app/flows, then
> pointing them at *your* ngrok URL takes them away from everyone else (including the owner) until
> someone points them back. Two people cannot test against the same Meta app at the same time. The
> clean way to test independently is your **own** Meta app, WhatsApp test number, and Flows (upload
> `src/main/resources/flows/*.json` in Flow Builder), configured as in 6.3-6.4.

### 6.3 Configuration you will override

Everything sensitive is env-var backed in `application.yaml` (the checked-in defaults are the
owner's test account). Set what differs for you before starting the app:

| Env var | What it is |
|---|---|
| `WA_PHONE_NUMBER_ID` | Your WhatsApp sender's phone-number ID (Meta -> WhatsApp -> API Setup) |
| `WA_WABA_ID` | Your WhatsApp Business Account ID |
| `WA_ACCESS_TOKEN` | Graph API token. The **temporary** token from API Setup expires after ~24h - a 401/190 error in the logs means it's time for a new one |
| `WA_APP_SECRET` | Meta app -> Settings -> Basic -> App secret. Used to verify `X-Hub-Signature-256`; wrong value => every Meta call is rejected (HTTP 432) |
| `WA_VERIFY_TOKEN` | Any string you choose; must match what you type into Meta's webhook form |
| `WA_FLOW_ID_AMB_REMINDER` | Meta's ID for your uploaded flow - see the caveat below |
| `WA_FLOW_MODE_AMB_REMINDER` | `published`, or `draft` while the flow is still a draft in Meta |
| `WA_RSA_PRIVATE_KEY_PATH` / `WA_RSA_PRIVATE_KEY_PASSPHRASE` | Private key location (default `keys/private_plain.pem`); passphrase only for an encrypted key |
| `FUNDS_REMINDER_MODE` | `DEMO` (minutes, default) or `PRODUCTION` (days) |

Bash: `export WA_ACCESS_TOKEN=... ; ./mvnw spring-boot:run`
PowerShell: `$env:WA_ACCESS_TOKEN="..."; .\mvnw.cmd spring-boot:run`

**Caveat - the two flows share one Flow-ID variable.** In `application.yaml`, both
`AMB_REMINDER` and `AMB_REMINDER_RADIO` read `${WA_FLOW_ID_AMB_REMINDER:...}`, so setting that
variable overrides **both** to the same ID. To use two different flows of your own, put the real IDs
in a git-ignored `src/main/resources/application-local.yaml`:

```yaml
flows:
  definitions:
    AMB_REMINDER:
      meta-flow-id: "<your first flow id>"
    AMB_REMINDER_RADIO:
      meta-flow-id: "<your second flow id>"
```

and start with `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.

**A WhatsApp *test* number** can only message phone numbers you've added to the recipient list
(API Setup -> "To" -> Manage phone number list), and free-form text is only allowed within 24 hours
of that person's last message to you.

### 6.4 The RSA key (level C)

Meta encrypts every Flow data-endpoint request with a public key registered against the sender
phone number; the service needs the matching **private** key.

* **Using the owner's WhatsApp number:** ask the owner to send you their `keys/private_plain.pem`
  privately (not through git, chat history or a ticket) and put it at `keys/private_plain.pem`. It
  matches the key already registered with Meta, so nothing to register.
* **Using your own WhatsApp number:** generate a keypair and register the public half.

  ```bash
  mkdir -p keys
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private_plain.pem
  openssl pkey -in keys/private_plain.pem -pubout -out keys/public.pem

  curl -X POST "https://graph.facebook.com/v20.0/$WA_PHONE_NUMBER_ID/whatsapp_business_encryption" \
    -H "Authorization: Bearer $WA_ACCESS_TOKEN" \
    --data-urlencode "business_public_key=$(cat keys/public.pem)"
  ```

  (This is Meta's documented "set business public key" call - confirm the current endpoint in
  Meta's Flows docs if it returns an error.) The file must be an **unencrypted PKCS#8** PEM, i.e.
  start with `-----BEGIN PRIVATE KEY-----`; the generate command above produces exactly that.

## 7. Send yourself the flow and watch it work

With Postgres, the app and ngrok running, Meta pointed at your tunnel, and the env vars set:

```bash
curl -X POST http://localhost:8082/trigger -H "Content-Type: application/json" \
  -d '{"to":"91XXXXXXXXXX","flowKey":"AMB_REMINDER_RADIO"}'
```

(`to` = your number in international format, digits only, on the recipient list if it's a test
number. `flowKey` is `AMB_REMINDER` or `AMB_REMINDER_RADIO`.)

The WhatsApp message with the **Review AMB** button arrives; tap it and walk the screens. Where to
look while you do:

| Where | What you see |
|---|---|
| App console | `Get data {...}` for each screen tap, `Flow data-endpoint request (decrypted)`, `Flow completed: from=... flow_token=...` at the end |
| <http://127.0.0.1:4040> | Meta's raw requests to your tunnel and your responses |
| `GET /session/<your number>/history` | The full path you walked |
| Temporal UI <http://localhost:8088> (level B) | The `reminder-REM-...` workflow with its timer; the reminder text arrives on WhatsApp 3/5/7 minutes later in DEMO mode |

## 8. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `docker compose up` : port is already allocated | Something already uses 5434 / 7233 / 8088. Stop it, or change the host-side port ([3](#3-clone-and-start-the-database-docker)). |
| App fails at startup with a Hibernate `Schema-validation` error, or `relation "flow_engine..." does not exist` | The schema didn't load. `docker compose down -v && docker compose up -d postgres`. Check `docker compose logs postgres` for a SQL error. |
| App fails: `Connection refused` on 5434 | Postgres isn't up yet or isn't healthy: `docker compose ps`. |
| `curl $NGROK/...` returns an ngrok error page / `ERR_NGROK_8012` | The tunnel is up but nothing listens on 8082 - start the app. |
| Meta "Verify and save": *couldn't validate the callback URL* | URL must be `https://.../webhook` (no trailing slash issues), verify token must equal `WA_VERIFY_TOKEN`, app must be running. Look at the request in the ngrok inspector. |
| Flow opens then shows a generic error, ngrok shows **432** | Signature check failed: `WA_APP_SECRET` doesn't match the Meta app that owns the Flow. |
| Flow shows an error, ngrok shows **421** | Decryption failed: private key doesn't match the public key registered for that phone number ([6.4](#64-the-rsa-key-level-c)), or `keys/private_plain.pem` is missing/encrypted. |
| `/trigger` returns an error from Meta | Read the response body. Expired `WA_ACCESS_TOKEN`; number not on the test recipient list; wrong `WA_PHONE_NUMBER_ID`; wrong Flow ID; `draft` flow but `WA_FLOW_MODE_...=published` (or vice versa). |
| Flow works but the button says the flow can't be found | `meta-flow-id` isn't a flow in *your* WABA - see the shared-variable caveat in [6.3](#63-configuration-you-will-override). |
| Reached the reminder screen and it failed | Level B isn't running: `curl localhost:8083/reminders?waId=1` should not be *connection refused*, and Temporal must be up on 7233. |
| Reminder scheduled but no text arrives | Full checklist: `REMINDER_SERVICES_GUIDE.md` section 10. Most common: expired token, or the recipient isn't allowed. |
| Changed code/config, nothing changed | Restart the app. Check the console for `Funds reminder requested ... mode=DEMO` to confirm you're running the current build. |

## 9. Shutting down

```bash
# Ctrl+C the app(s) and ngrok, then:
docker compose stop               # keep data for next time
docker compose down -v            # or: delete containers AND all data
```

## 10. Ports at a glance

| Port | What | Started by |
|---|---|---|
| 5434 | Postgres (`chatbot_v3`, and `chatbot_reminder` at level B) | `docker compose up -d postgres` |
| 8082 | **This service** (the one ngrok exposes) | `./mvnw spring-boot:run` |
| 7233 | Temporal gRPC | `docker compose up -d temporal ...` |
| 8088 | Temporal UI | same |
| 8083 | `temporal-workflow-service` | its own `./mvnw spring-boot:run` |
| 4040 | ngrok inspector | `ngrok http 8082` |
