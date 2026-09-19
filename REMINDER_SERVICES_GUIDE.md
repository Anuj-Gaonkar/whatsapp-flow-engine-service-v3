# Reminder Platform Guide - `whatsapp-flow-engine-service-v3` + `temporal-workflow-service`

A complete, bit-by-bit explanation of the two Spring Boot services that together let a customer
answer a WhatsApp Flow ("I expect funds shortly -> Within 3 days") and later receive a WhatsApp
reminder message automatically.

> Scope: this document describes the code exactly as it stands on `main` of
> `whatsapp-flow-engine-service-v3` (commit `1505d76`) and `master` of `temporal-workflow-service`
> (commit `36c165a`). Where the code has limitations or rough edges they are called out honestly in
> [Section 11](#11-known-limitations-and-suggested-next-steps) rather than glossed over.

---

## Table of contents

1. [The 60-second version](#1-the-60-second-version)
2. [Architecture](#2-architecture)
3. [End-to-end walkthrough of one reminder](#3-end-to-end-walkthrough-of-one-reminder)
4. [Service 1 - `whatsapp-flow-engine-service-v3`](#4-service-1---whatsapp-flow-engine-service-v3)
5. [Service 2 - `temporal-workflow-service`](#5-service-2---temporal-workflow-service)
6. [Infrastructure and ports](#6-infrastructure-and-ports)
7. [Running everything locally](#7-running-everything-locally)
8. [Logging guide](#8-logging-guide)
9. [Testing the reminder without WhatsApp](#9-testing-the-reminder-without-whatsapp)
10. [Troubleshooting: "I did not get the reminder"](#10-troubleshooting-i-did-not-get-the-reminder)
11. [Known limitations and suggested next steps](#11-known-limitations-and-suggested-next-steps)
12. [Glossary](#12-glossary)

---

## 1. The 60-second version

There are **two independent Spring Boot applications** plus some infrastructure:

| Piece | Job |
|---|---|
| **whatsapp-flow-engine-service-v3** (port 8082) | Talks to WhatsApp. Sends a *Flow* (a multi-screen form) to a customer, answers Meta's callbacks for every screen tap, remembers where each customer is in the flow, and - when the customer lands on the "Reminder Set" screen - asks the other service to schedule a reminder. Also the service that actually *sends* the reminder text when it is due. |
| **temporal-workflow-service** (port 8083) | Owns the *timer*. Given "remind wa_id X at time T with message M", it starts a durable **Temporal workflow** that sleeps until T, then calls the flow engine back to deliver the message. |
| **Temporal server** (port 7233) | Runs the durable timers. Survives restarts of either app. Has its own Postgres. |
| **Postgres** (port 5434) | Two databases: `chatbot_v3` (flow engine) and `chatbot_reminder` (reminder service). |
| **WhatsApp Cloud API (Meta)** | The customer-facing channel. |

The whole feature in one line:

```
Customer taps "Within 3 days"
   -> flow engine calls  POST temporal-workflow-service /reminders  (remindAt = now + N)
   -> Temporal sleeps N
   -> temporal-workflow-service calls  POST flow-engine /messages/reminder
   -> flow engine calls  WhatsApp Cloud API  -> customer receives the text
```

**Demo mode** (the default) makes `N` be **3 / 5 / 7 minutes** instead of 3 / 7 / 15 days, so a demo
does not need to wait days. See [Section 4.9](#49-the-funds-reminder-in-detail-demo-vs-production).

---

## 2. Architecture

### 2.1 Component diagram

```mermaid
flowchart LR
    C([Customer<br/>WhatsApp])
    M[[Meta<br/>WhatsApp Cloud API]]

    subgraph FE[whatsapp-flow-engine-service-v3 :8082]
        WH[/webhook/flow<br/>encrypted data-endpoint/]
        MW[/webhook<br/>messaging webhook/]
        TR[/trigger/]
        ENG[FlowEngineService]
        RD[/messages/reminder/]
        TC[TemporalReminderClient]
        WC[WhatsAppClient]
    end

    subgraph TW[temporal-workflow-service :8083]
        RC[/reminders REST/]
        RS[ReminderService]
        WF[ReminderWorkflowImpl<br/>durable timer]
        ACT[ReminderActivitiesImpl]
        DC[ReminderDeliveryClient]
    end

    TS[(Temporal server :7233<br/>+ own Postgres)]
    P1[(Postgres :5434<br/>chatbot_v3)]
    P2[(Postgres :5434<br/>chatbot_reminder)]

    C <--> M
    M -- "INIT / data_exchange / BACK (RSA+AES)" --> WH
    M -- "nfm_reply on Done" --> MW
    TR -- "send Flow message" --> WC --> M
    WH --> ENG --> P1
    ENG -- "SCHEDULE_FUNDS_REMINDER" --> TC -- "POST /reminders" --> RC --> RS
    RS -- "start workflow" --> TS
    RS --> P2
    TS -- "timer fires -> run activity" --> WF --> ACT
    ACT --> P2
    ACT --> DC -- "POST /messages/reminder" --> RD --> WC
    WC -- "text message" --> M
```

### 2.2 Why two services?

* **Separation of concerns.** The flow engine is a request/response state machine (stateless per
  request, state in Postgres). Waiting three days is a completely different problem - it needs a
  *durable timer*. Temporal is purpose-built for that, so the timing logic lives in its own
  service instead of being bolted onto the flow engine (with `@Scheduled` jobs, cron tables, etc.).
* **Durability.** If either Spring app is restarted while a reminder is pending, the reminder is not
  lost: Temporal persisted the timer in its own database, and a restarted worker picks it up.
* **Direct HTTP, no Kafka.** Earlier iterations of the reminder POC published events to Kafka. The
  current design uses plain HTTP in both directions - simpler to run and debug. (The Kafka container
  is still listed in `docker-compose.yml` but nothing uses it any more - see
  [Section 11](#11-known-limitations-and-suggested-next-steps).)

### 2.3 Repositories and layout

Both projects live side by side (each is its own Git repository):

```
Chat-Bot-V3/
├── docker-compose.yml                 Temporal server + UI + its Postgres (+ unused Kafka)
├── whatsapp-flow-engine-service-v3/   Service 1 (git repo, branch main)
├── temporal-workflow-service/         Service 2 (git repo, branch master, no remote configured)
└── yaml-workflow-service/             unrelated sibling, not covered here
```

### 2.4 Technology stack

| | whatsapp-flow-engine-service-v3 | temporal-workflow-service |
|---|---|---|
| Language / build | Java 21 source level (runs fine on newer JDKs), Maven, Lombok | same |
| Framework | Spring Boot **4.1.0** (Spring MVC, Data JPA, Validation, Actuator) | Spring Boot **4.1.1** |
| JSON | Jackson 3 (`tools.jackson.*` packages) | Spring default |
| Database | PostgreSQL via JPA/Hibernate, `ddl-auto: validate` (schema is owned by SQL scripts) | same |
| Extra | springdoc-openapi 3.0.3 (Swagger UI) | springdoc-openapi 3.0.3, **Temporal Java SDK Spring Boot starter 1.39.0** |
| Outbound HTTP | Spring `RestClient` | Spring `RestClient` |

Both `main` methods call `TimeZone.setDefault(UTC)` before Spring starts. Reason (from the code
comments): the PostgreSQL JDBC driver sends the JVM's default zone as a startup parameter, and on
some Windows machines that is a legacy alias (`Asia/Calcutta`) that Postgres rejects.

---

## 3. End-to-end walkthrough of one reminder

Scenario: customer picks **"I expect funds shortly" -> "Within 3 days"** in the
`AMB_REMINDER_RADIO` flow, running in DEMO mode.

```mermaid
sequenceDiagram
    autonumber
    participant Cust as Customer
    participant Meta as Meta / WhatsApp
    participant FE as flow-engine :8082
    participant DB1 as Postgres chatbot_v3
    participant TW as temporal-workflow-service :8083
    participant TS as Temporal server
    participant DB2 as Postgres chatbot_reminder

    Note over FE,DB1: (earlier) POST /trigger created a flow_session row (OPENED) and sent the Flow message
    Cust->>Meta: taps "Within 3 days" then Continue
    Meta->>FE: POST /webhook/flow  (encrypted data_exchange, screen=PRO_FUNDS_TIMING_SCREEN,<br/>data={funds_timing_option: within_three_days})
    FE->>FE: verify signature, decrypt, dispatch
    FE->>DB1: load session, merge payload into context, match transition
    FE->>FE: next node PRO_FUNDS_REMINDER_SET_SCREEN has action SCHEDULE_FUNDS_REMINDER
    FE->>FE: DEMO mode: within_three_days -> 3 MINUTES, remindAt = now + 3 min
    FE->>TW: POST /reminders {waId, message, remindAt}
    TW->>TS: start workflow ReminderWorkflow.remind(...)  (workflowId reminder-REM-XXXX)
    TW->>DB2: insert reminder row, status SCHEDULED
    TW-->>FE: 201 {reminderId, workflowId, remindAt}
    FE->>DB1: save session (context now has reminder_id, reminder_date), write history row
    FE-->>Meta: encrypted {screen: PRO_FUNDS_REMINDER_SET_SCREEN, data: {...}}
    Meta-->>Cust: "You're all set" screen
    Cust->>Meta: taps Done
    Meta->>FE: POST /webhook (nfm_reply with outcome_screen)
    FE->>DB1: session status COMPLETED

    Note over TS: ... 3 minutes pass. Temporal's timer is durable (survives restarts) ...
    TS->>TW: workflow task: timer fired -> run activity deliverReminder
    TW->>FE: POST /messages/reminder {waId, message}
    FE->>Meta: POST /{phone-number-id}/messages  (type=text)
    Meta-->>Cust: "Reminder: please transfer funds to maintain your Average Monthly Balance."
    FE-->>TW: 200
    TW->>DB2: reminder.status = FIRED
```

Step by step, with the class that does each step:

| # | What happens | Where |
|---|---|---|
| 1 | Someone calls `POST /trigger`. A UUID `flow_token` is minted, a `flow_session` row is inserted (`OPENED`, pointing at the flow's entry node), and the Flow message is sent through the Cloud API. | `TriggerController` -> `FlowMessageSenderService` -> `WhatsAppClient.sendFlow` |
| 2 | Customer opens the Flow and taps through screens. Each tap makes Meta call the flow engine's data-endpoint. | `FlowWebhookController` |
| 3 | The request is signature-checked, RSA/AES-decrypted, and turned into an action (`INIT`/`data_exchange`/`BACK`/`ping`). | `WhatsAppSignatureVerifier`, `FlowEncryptionService`, `FlowRequestDispatcher` |
| 4 | `FlowEngineService.dataExchange` merges the tap into the session's `context`, finds the matching outgoing `flow_transition`, and moves to the next node. | `FlowEngineService` |
| 5 | If the *next* node has an `action_code` (here `SCHEDULE_FUNDS_REMINDER`) the action runs **before** the screen is returned. | `FlowEngineService.applySimulatedAction` |
| 6 | The action computes `remindAt` (now + 3 min in DEMO) and calls the reminder service. | `TemporalReminderClient.schedule` |
| 7 | The reminder service starts a Temporal workflow and saves a `reminder` row. | `ReminderController` -> `ReminderService.createReminder` |
| 8 | The flow engine stores `reminder_id` and `reminder_date` in the session context and returns the terminal screen. | `FlowEngineService` |
| 9 | Customer taps **Done**. Meta sends an `nfm_reply` to the *plain* messaging webhook; the session is marked `COMPLETED`. | `MessagingWebhookController` -> `FlowEngineService.completeSession` |
| 10 | Three minutes later Temporal wakes the workflow, which runs the delivery activity. | `ReminderWorkflowImpl`, `ReminderActivitiesImpl` |
| 11 | The activity calls back the flow engine over HTTP. | `ReminderDeliveryClient` |
| 12 | The flow engine sends the text via the Cloud API. | `ReminderDeliveryController` -> `WhatsAppClient.sendText` |
| 13 | The reminder row is marked `FIRED`; the workflow completes. | `ReminderActivitiesImpl`, `ReminderWorkflowImpl` |

Steps 9 and 10 are independent - the reminder fires whether or not the customer taps **Done**.

---

## 4. Service 1 - `whatsapp-flow-engine-service-v3`

### 4.1 What it is

A generic **WhatsApp Flow engine**. A *Flow* is Meta's feature for multi-screen forms inside a
WhatsApp chat. The screens' layout is uploaded to Meta as a JSON file, but every time the customer
taps a button Meta calls **your** server (the "data-endpoint") asking "which screen next, and with
what data?". This service is that server.

Key design points:

* **Multiple flows, one engine.** Any number of flows are registered by *configuration* (YAML +
  database rows). No per-flow code, controllers or routes.
* **Graph in the database.** A flow is a directed graph: `flow_node` rows are screens,
  `flow_transition` rows are the options that connect them.
* **Stateless requests, stateful sessions.** Each Meta call is independent; the position of the
  customer lives in a `flow_session` row keyed by `flow_token`.
* **Full audit trail.** Every INIT / tap / BACK / completion writes a `flow_node_history` row.

Currently registered flows (`application.yaml`):

| Flow key | Entry screen | Screens | Node-id range | Definition JSON | Seed SQL |
|---|---|---|---|---|---|
| `AMB_REMINDER` (the *default* flow) | `FULL_WELCOME_SCREEN` | 22 (`FULL_*`) | 1000-1210 | `flows/amb_reminder_flow.json` | `db/02_seed_amb_reminder_example.sql` |
| `AMB_REMINDER_RADIO` | `PRO_WELCOME_SCREEN` | 18 (`PRO_*`) | 2000-2170 | `flows/amb_reminder_radio_flow.json` | `db/03_seed_amb_reminder_radio_example.sql` |

"AMB" = **Average Monthly Balance** - the business scenario is a bank telling a customer their
balance is low and letting them respond (fund now / funds coming / cash flow issue / didn't know /
inactive account).

### 4.2 Package map - every class

Base package `com.hdfc.flowengine`.

**Entry point**

| Class | Purpose |
|---|---|
| `FlowEngineApplication` | `@SpringBootApplication` + `@ConfigurationPropertiesScan` (auto-registers all `@ConfigurationProperties` records); forces UTC default timezone. |

**`controller` - HTTP surface**

| Class | Routes | Purpose |
|---|---|---|
| `FlowWebhookController` | `POST /webhook/flow`, `POST /webhook/flow/{flowKey}` | Meta's real, **encrypted** Flow data-endpoint. |
| `MessagingWebhookController` | `GET /webhook`, `POST /webhook` | Meta's webhook verification handshake, and inbound messages (used for `nfm_reply` = Flow completed). |
| `ScreenController` | `POST /screen`, `POST /screen/{flowKey}` | Plain-JSON twin of the data-endpoint (no encryption/signature) for local testing. |
| `TriggerController` | `POST /trigger` | Sends a Flow to a phone number. |
| `ReminderDeliveryController` | `POST /messages/reminder` | Called by the reminder service when a timer fires; sends the WhatsApp text. |
| `SessionHistoryController` | `GET /sessions/{flowToken}/history`, `GET /session/{waId}/history` | Raw interaction history. |
| `ConversationController` | `GET /sessions/{flowToken}/conversation`, `GET /flows/{flowKey}/graph` | UI-friendly views: path walked plus options offered; whole flow graph. |

**`service` - business logic**

| Class | Purpose |
|---|---|
| `FlowRequestDispatcher` | Shared by both data-endpoint controllers. Reads `action` from the decoded body and calls the right `FlowEngineService` method. |
| `FlowEngineService` | The state machine: `init`, `dataExchange`, `back`, `ping`, `completeSession`, plus the simulated backend actions (including scheduling the reminder). |
| `FlowRegistry` | `flow_key -> config` lookups (entry screen, Meta flow id, mode, CTA label, body text) over `FlowRegistryProperties`. |
| `FlowMessageSenderService` | Mints a `flow_token`, creates the `OPENED` session row, sends the Flow message. |
| `WhatsAppClient` | Outbound Cloud API calls: `sendFlow` (interactive flow message) and `sendText` (plain text - used for reminders). |
| `TemporalReminderClient` | Outbound call to the reminder service: `POST /reminders`. |

**`crypto` / `security`**

| Class | Purpose |
|---|---|
| `FlowEncryptionService` | Meta's Flow encryption: RSA-OAEP unwrap of the AES key, AES-128-GCM decrypt of the request and encrypt of the response. |
| `FlowDecryptionException` | Unchecked exception wrapping any crypto failure. |
| `WhatsAppSignatureVerifier` | Verifies the `X-Hub-Signature-256` HMAC header on the raw body. |

**`entity` / `repository`** - JPA mapping of the four tables (Section 4.4) and their Spring Data
repositories.

**`config`** - four `@ConfigurationProperties` records: `WhatsAppProperties` (`whatsapp.*`),
`FlowRegistryProperties` (`flows.*`), `TemporalReminderProperties` (`temporal-workflow-service.*`),
and `FundsReminderProperties` (`reminder.funds.*`, DEMO vs PRODUCTION mode).

**`model`** - request/response records: `ScreenResponse`, `TriggerRequest`,
`ReminderMessageRequest`, and the UI view records (`FlowHistoryView`, `SessionHistoryView`,
`ConversationStepView`, `ConversationOptionView`, `FlowGraphView`, `FlowGraphNodeView`,
`FlowGraphEdgeView`).

**Static resource** - `static/flow-graph.html`, a single-page viewer that draws a flow as a Mermaid
flowchart from `GET /flows/{flowKey}/graph` (open `http://localhost:8082/flow-graph.html`).

**Flow definitions** - `resources/flows/*.json`: the exact JSON you upload to WhatsApp Manager to
create the Flow object. The engine does **not** read these at runtime; they are kept in the repo so
the screens in Meta and the rows in Postgres can be kept in sync (they match "1:1").

### 4.3 HTTP endpoints - complete reference

| Method & path | Auth / transport | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET /webhook` | `hub.verify_token` must equal `whatsapp.verify-token` | Meta's one-time webhook verification; echoes `hub.challenge`. | `200` + challenge | `403` |
| `POST /webhook` | HMAC `X-Hub-Signature-256` | Inbound WhatsApp events. Only `interactive.type == nfm_reply` is acted on. | `200` (empty) | `403` bad signature |
| `POST /webhook/flow` and `/webhook/flow/{flowKey}` | HMAC signature **and** RSA/AES encrypted body | The encrypted data-endpoint. | `200`, body = base64 AES-GCM ciphertext (`text/plain`) | `432` bad signature, `421` decryption failed |
| `POST /screen` and `/screen/{flowKey}` | none (local testing only) | Same as above in plain JSON. | `200` JSON `{screen, data}` | `200` with `{data:{error_message}}` on business errors |
| `POST /trigger` | none | Send a Flow to a customer: `{"to":"919...","flowKey":"AMB_REMINDER_RADIO"}`; `flowKey` optional. | `200 {flow_key, flow_token, meta_response}` | `400` unknown flow / missing `to` / missing config; `502` if Meta rejects |
| `POST /messages/reminder` | none (internal) | Send a plain WhatsApp text: `{"waId":"...","message":"..."}`. Called by the reminder service. | `200` empty | exception -> `500` (this is what makes Temporal retry) |
| `GET /sessions/{flowToken}/history` | none | One session's history rows in order. | list | - |
| `GET /session/{waId}/history` | none | All sessions for a customer, newest first, each with history. | list | - |
| `GET /sessions/{flowToken}/conversation` | none | Path walked + every option that was on offer at each step (`chosen` flag). | list | empty list if unknown |
| `GET /flows/{flowKey}/graph` | none | Whole flow: nodes and edges. | `200` | `400` unknown flow |
| `GET /flow-graph.html` | none | Graph viewer UI. | HTML | - |
| Actuator, Swagger UI | none | springdoc/actuator are on the classpath (Swagger UI at the springdoc default `/swagger-ui/index.html`). | | |

Flow keys in a path (`/webhook/flow/{flowKey}`, `/screen/{flowKey}`) matter **only** for choosing
the fallback entry screen when no session exists yet (Section 4.6). For every real, trigger-originated
session the position comes purely from the `flow_session` row.

### 4.4 Data model (database `chatbot_v3`, schema `flow_engine`)

Created by `db/01_schema.sql`; Hibernate is set to `validate`, so **the app never creates or alters
tables** - if an entity and a table disagree the app refuses to start.

**`flow_node` - one row per screen**

| Column | Type | Meaning |
|---|---|---|
| `node_id` | bigint PK | Hand-assigned, globally unique across flows (1000s for AMB_REMINDER, 2000s for RADIO, use 3000+ next). |
| `flow_code` | varchar(50) | Label of the owning flow (e.g. `AMB_REMINDER_RADIO`). Only used by `GET /flows/{key}/graph`; runtime lookups never use it. |
| `screen_id` | varchar(100) **unique** | Must equal the screen `id` in the Meta Flow JSON. |
| `node_type` | `SCREEN` / `TERMINAL` | `TERMINAL` screens end the flow (customer taps Done). |
| `back_target_node_id` | bigint, nullable | Where Meta's BACK button goes from here (static lookup - see 4.6). |
| `action_code` | varchar(50), nullable | A simulated backend action to run when a customer *arrives* at this node (Section 4.8). |

**`flow_transition` - one row per outgoing option**

| Column | Meaning |
|---|---|
| `transition_id` | identity PK |
| `from_node_id`, `to_node_id` | the edge |
| `option_value` | value the customer's submission must contain to take this edge (e.g. `within_three_days`). `NULL` = unconditional edge. |
| `option_label` | human label, shown by the graph/conversation UI |
| `display_order` | ordering of options |

**`flow_session` - one row per Flow instance sent to a customer**

| Column | Meaning |
|---|---|
| `flow_token` (PK) | UUID minted at trigger time; Meta echoes it in every later call, tying everything together. |
| `wa_id` | Customer's WhatsApp id (phone number). `UNKNOWN` if a call arrives with none (e.g. WhatsApp Manager's Preview tool). |
| `current_node_id` | Where the customer currently is. |
| `status` | `OPENED` -> `IN_PROGRESS` -> `COMPLETED` (`EXPIRED` exists in the enum/check constraint but nothing sets it yet). |
| `context` (jsonb) | Accumulated answers **plus** anything simulated actions add (`payment_link`, `reminder_id`, `reminder_date`, ...). |
| `started_at`, `last_interaction_at`, `completed_at` | timestamps |

Indexed on `wa_id`.

**`flow_node_history` - append-only audit log**

`id`, `flow_token`, `customer_id` (= wa_id), `node_id`, `screen_id`, `action_type`
(`INIT` / `DATA_EXCHANGE` / `BACK` / `COMPLETED`), `selected_value` (the option taken),
`request_data` (jsonb), `response_data` (jsonb, `{screen, data}`), `occurred_at`. Indexed on
`flow_token`.

**Session status lifecycle**

```mermaid
stateDiagram-v2
    [*] --> OPENED: POST /trigger (row created)
    OPENED --> IN_PROGRESS: first INIT or data_exchange
    IN_PROGRESS --> IN_PROGRESS: more taps
    IN_PROGRESS --> COMPLETED: nfm_reply (customer taps Done)
    OPENED --> COMPLETED: nfm_reply
    COMPLETED --> [*]
```

### 4.5 How a flow is defined - the three places that must agree

For a flow to work three artifacts must line up:

1. **Meta Flow JSON** (`resources/flows/*.json`) - the screens and their layout, uploaded in
   WhatsApp Manager. It also fixes the *field names* and *option ids* each screen submits (for
   example `RadioButtonsGroup name="funds_timing_option"` with ids `within_three_days`, ...).
2. **Database graph** (`db/0x_seed_*.sql`) - `flow_node.screen_id` values equal the JSON screen ids,
   `flow_transition.option_value` values equal the JSON option ids.
3. **`application.yaml` -> `flows.definitions.<KEY>`** - registers the key:

   ```yaml
   flows:
     default-flow-key: ${FLOW_DEFAULT_KEY:AMB_REMINDER}
     definitions:
       AMB_REMINDER_RADIO:
         entry-screen-id: PRO_WELCOME_SCREEN
         meta-flow-id: ${WA_FLOW_ID_AMB_REMINDER:2118583488730077}   # Flow ID from WhatsApp Manager
         mode: ${WA_FLOW_MODE_AMB_REMINDER:published}                 # or "draft"
         cta-label: Review AMB with Radio buttons                     # button on the chat message
         body-text: Please review your account balance status         # text of the chat message
   ```

`FlowRegistry` is the read-only facade over this block (`entryScreenId`, `metaFlowId`, `mode`,
`ctaLabel`, `bodyText` with sensible fallbacks, `isRegistered`, `defaultFlowKey`); asking for an
unknown key throws `IllegalArgumentException("Unknown flow key: ...")`.

> Note: both flows' `meta-flow-id` currently read the *same* environment variable
> `WA_FLOW_ID_AMB_REMINDER` (with different defaults), and likewise `mode`. Setting that variable
> would override **both** flows' Meta id. Give the RADIO flow its own variable if you ever need to
> override them independently.

### 4.6 Request lifecycles in detail

#### 4.6.1 Trigger - `POST /trigger`

`TriggerController.trigger`:

1. Resolve `flowKey` (body value, else `flows.default-flow-key`); `400` if not registered.
2. `400` if `to` is blank.
3. `400` (with the list of missing names) if `WA_PHONE_NUMBER_ID`, `WA_ACCESS_TOKEN` or that flow's
   `meta-flow-id` is blank.
4. Call `FlowMessageSenderService.send` (one DB transaction):
   * look up the entry node by `entryScreenId` (throws if the seed script was not run),
   * create the `flow_session` (`OPENED`, `current_node_id` = entry node),
   * call `WhatsAppClient.sendFlow`, which POSTs to
     `{cloud-api-base-url}/{phone-number-id}/messages` with `Authorization: Bearer <token>` and an
     interactive message of type `flow`:
     `flow_message_version 3`, the `flow_token`, `flow_id`, `flow_cta`, `flow_action: navigate`,
     `mode` (draft/published) and `flow_action_payload = {screen: <entry screen>, data: {wa_id: <to>}}`.
5. Return `{flow_key, flow_token, meta_response}`. A Meta HTTP error becomes `502` with Meta's
   status and body.

Because the session row exists *before* the message is sent, later data-endpoint calls can always
find it by `flow_token`.

#### 4.6.2 The data-endpoint - `FlowWebhookController` (encrypted) / `ScreenController` (plain)

Both do the same job and share `FlowRequestDispatcher`; they differ only in transport:

| | `/webhook/flow` | `/screen` |
|---|---|---|
| Signature check | required (`432` if bad) | none |
| Body | Meta's envelope `{encrypted_flow_data, encrypted_aes_key, initial_vector}` | plain `{action, flow_token, screen, data}` |
| Response | base64 AES-GCM ciphertext, `text/plain` | JSON |
| Use | real Meta traffic | curl / Postman / another middleware |

Encrypted path, in order (`FlowWebhookController.process`):

1. Verify `X-Hub-Signature-256` over the **raw** body string -> else HTTP `432`.
2. Parse the envelope; `FlowEncryptionService.decrypt(...)`:
   * Base64-decode the wrapped AES key, unwrap it with the service's RSA private key using
     `RSA/ECB/OAEPPadding` with an **explicit** `OAEPParameterSpec(SHA-256, MGF1, SHA-256)`
     (the JDK's named transformation would silently leave MGF1 at SHA-1, which Meta does not use).
   * AES-128-GCM decrypt the payload with the request IV (128-bit tag).
   * Failure -> HTTP `421`.
3. Read `action` and `flow_token`, call `FlowRequestDispatcher.dispatch`.
4. If dispatch throws, the response payload becomes
   `{"data": {"error_message": "Something went wrong. Please try again."}}` - **still HTTP 200**.
   Per Meta's contract, only signature and decryption failures may surface as non-200.
5. Encrypt the response with the *same* AES key and the request IV **bit-flipped** (`~iv`), return
   base64 as `text/plain`.

The RSA private key is loaded lazily from `whatsapp.rsa-private-key-path` (PKCS#8 PEM), cached in a
`volatile` field with double-checked locking. If the PEM is `ENCRYPTED PRIVATE KEY` the passphrase
(`whatsapp.rsa-private-key-passphrase`) is used; otherwise it is read as a plain key. The default
points at `keys/private_plain.pem` (the pre-decrypted form).

`FlowRequestDispatcher.dispatch` maps the action:

| `action` | Handler | Result |
|---|---|---|
| `ping` | `FlowEngineService.ping()` | `{"data": {"status": "active"}}` - Meta's periodic health check |
| `INIT` | `init(flowToken, data.wa_id, screen-or-default)` | `{screen, data}` |
| `data_exchange` | `dataExchange(flowToken, screen, data)` | `{screen, data}` |
| `BACK` | `back(flowToken, screen)` | `{screen, data}` |
| anything else | throws `IllegalArgumentException` | becomes the error payload above |

#### 4.6.3 `init`

Meta's first call for a Flow instance (also sent by WhatsApp Manager's Preview tool, always with a
synthetic `flow_token` and no `wa_id`).

* If a `flow_session` row exists (the normal, triggered case) it is **trusted**: its
  `current_node_id` is the entry node.
* Otherwise a fallback session is created at the route's default entry screen (logged as a warning),
  with `wa_id` = `UNKNOWN` if missing.
* Status -> `IN_PROGRESS`, `last_interaction_at` updated, and **`context` is cleared** (INIT means
  "start from the top"; the Preview tool reuses one token).
* A history row `INIT` is written. Returns `{screen: <entry>, data: {}}`.

#### 4.6.4 `dataExchange` - the heart of the engine

`FlowEngineService.dataExchange(flowToken, screenId, formData)` (`@Transactional`):

1. Load the **current** node from `screenId`.
2. Load the session (fallback session + warning if missing). Touch `last_interaction_at`;
   `OPENED` -> `IN_PROGRESS`.
3. **Merge the submitted fields into `session.context`** (`putAll`). The context therefore grows
   across screens.
4. Load the current node's outgoing transitions ordered by `display_order` and **match** one
   (`matchTransition`):
   * exactly one transition and its `option_value` is `NULL` -> take it regardless of payload
     (e.g. welcome screen "Continue");
   * else if the payload is empty -> no match;
   * else the **first** transition whose non-null `option_value` equals *any value* in the payload
     (`formData.containsValue(...)`) - the field *name* is irrelevant, only that some submitted value
     matches.
   * No match -> `IllegalStateException` -> error payload to the customer, transaction rolled back.
5. Load the target node. **If it has an `action_code`, run the simulated action now** (Section 4.8),
   *before* the screen is rendered, so the action's output appears in this very response.
6. Set `current_node_id`, save the session.
7. Response = `{screen: <next screen id>, data: <entire context>}`. Note the data returned is the
   whole accumulated context, not just the screen's own fields; screens simply pick the fields they
   declared.
8. Write a `DATA_EXCHANGE` history row: current node, `selected_value` = the matched
   `option_value`, request payload, response.

Everything (session update, action side effects on the DB, history) is one transaction.

#### 4.6.5 `back`

Meta's BACK is a **static lookup**: `flow_node.back_target_node_id` of the current screen. (No
reverse graph walk, no history replay.) Missing target -> error. Session `current_node_id` moves to
the back target; returns that screen with the session's current context; writes a `BACK` history
row. Note the context is **not** rolled back - answers given on the abandoned forward path stay in
it.

#### 4.6.6 Completion - `POST /webhook` with `nfm_reply`

When the customer taps the terminal screen's button (`on-click-action: complete`), Meta does not call
the data-endpoint; it sends an ordinary inbound WhatsApp message to the **messaging webhook** with
`interactive.type == "nfm_reply"` and a `response_json` string. `MessagingWebhookController`:

1. Verifies the signature, then walks `entry[].changes[].value.messages[]`.
2. Ignores anything that is not an `nfm_reply`.
3. Parses `response_json` -> `flow_token`, and calls `FlowEngineService.completeSession(flowToken,
   from, payload)`.

`completeSession` requires the payload to contain `outcome_screen` (every terminal screen's
`complete` payload embeds its own screen id - see `PRO_FUNDS_REMINDER_SET_SCREEN`'s footer in the
JSON). It resolves that screen, sets the session to `COMPLETED` with `completed_at`, points
`current_node_id` at the terminal node and writes a `COMPLETED` history row. (If there is no session
it creates a fallback one, logging a warning.)

`GET /webhook` is Meta's verification handshake: `hub.mode == subscribe` and
`hub.verify_token == whatsapp.verify-token` -> echo `hub.challenge`, else `403`.

### 4.7 Security summary

| Concern | Mechanism | Where |
|---|---|---|
| Is this really from Meta? | HMAC-SHA256 of the **raw** body keyed by the app secret, compared to `X-Hub-Signature-256` with `MessageDigest.isEqual` (constant time). Missing header/secret -> rejected. | `WhatsAppSignatureVerifier` |
| Confidentiality of Flow data | RSA-OAEP(SHA-256/MGF1-SHA-256) key wrap + AES-128-GCM | `FlowEncryptionService` |
| Webhook registration | verify token | `MessagingWebhookController.verify` |
| Calls to Meta | Bearer access token | `WhatsAppClient` default header |

**Not protected:** `/screen`, `/trigger`, `/messages/reminder`, the history/graph endpoints and (on the
other service) `/reminders` have **no authentication**. They are designed for local/POC use and
would need protection (network policy or auth) before any real deployment. See Section 11.

### 4.8 Simulated backend actions (`action_code`)

When a transition lands on a node with a non-null `action_code`, `applySimulatedAction` runs. Each
action mutates the session `context`, so its output flows into the screen data automatically.

| `action_code` | Effect on `context` | Screens that use it |
|---|---|---|
| `GENERATE_FUND_LINK` | `payment_link = https://pay.hdfcbank.com/amb/<8-char id>` (fabricated) | `*_FUND_NOW_SCREEN` |
| `SCHEDULE_FUNDS_REMINDER` | **Real** call to the reminder service; sets `reminder_id` and `reminder_date` | `*_FUNDS_REMINDER_SET_SCREEN` |
| `ROUTE_TO_EXECUTIVE` | `handoff_id = HANDOFF-<id>` (fabricated) | `*_EXECUTIVE_HANDOFF_SCREEN` |
| `CONVERT_SALARY_ACCOUNT` | `request_id = REQ-<id>` (fabricated) | `*_SALARY_OFFER_SCREEN` |
| `LOG_CALLBACK_REQUEST` | `callback_id = CB-<id>` (fabricated) | `*_CALLBACK_LOGGED_SCREEN` |
| anything else | warning logged, no effect | - |

Only `SCHEDULE_FUNDS_REMINDER` is wired to a real system; the others are stand-ins for future
integrations. To add an action: add a `case` in `applySimulatedAction` and put the code on a node.

### 4.9 The funds reminder in detail (DEMO vs PRODUCTION)

Code: the `SCHEDULE_FUNDS_REMINDER` case in `FlowEngineService.applySimulatedAction`,
`resolveReminderDelay`, `FundsReminderProperties`, `TemporalReminderClient`.

**Which option did the customer pick?** `context.funds_timing_option`, one of `within_three_days`,
`within_seven_days`, `within_fifteen_days` (submitted by the timing screen, merged into the context in
step 3 of `dataExchange`).

**How long to wait** - controlled by `reminder.funds.mode` (env var `FUNDS_REMINDER_MODE`,
**default `DEMO`**):

| Option picked | DEMO (default) | PRODUCTION |
|---|---|---|
| `within_three_days` | **3 minutes** | 3 days |
| `within_seven_days` | **5 minutes** | 7 days |
| `within_fifteen_days` | **7 minutes** | 15 days |
| anything else / missing | **5 minutes** | 7 days |

The DEMO mapping is positional (1st, 2nd, 3rd option -> 3, 5, 7). The **on-screen labels still say
"Within 3 / 7 / 15 days"** because they live in the Meta Flow JSON; change the labels in the Meta flow
(and `option_label` in the seed) if the demo audience will be confused.

**Steps executed:**

1. `delay = resolveReminderDelay(option)` (amount + `ChronoUnit`).
2. `remindAt = Instant.now() + delay`.
3. Log: `Funds reminder requested waId=... timingOption=... mode=DEMO delay=3 MINUTES remindAt=...`
4. `TemporalReminderClient.schedule(waId, FUNDS_REMINDER_MESSAGE, remindAt)` -> `POST
   {temporal-workflow-service.base-url}/reminders` with JSON `{waId, message, remindAt}`. The reminder
   text is the constant *"Reminder: please transfer funds to maintain your Average Monthly Balance."*
5. From the `201` response store `context.reminder_id` (the `REM-XXXXXXXX` id).
6. Store `context.reminder_date` = `remindAt` converted to **Asia/Kolkata** as a plain date
   (`yyyy-MM-dd`). (It is a display value only - in DEMO it will simply be today's date - and the
   terminal screen declares it in its data but the sample screen text does not print it.)

**Failure behaviour:** the HTTP call happens *inside* the `dataExchange` transaction. If the reminder
service is down or returns an error, the exception propagates, the transaction rolls back (the
customer's session stays on the previous screen) and the customer sees "Something went wrong. Please
try again." Nothing is scheduled.

The screen text ("We will send you a reminder one day before the expected date") is static text in the
Meta Flow JSON and is **not** what the code does - the reminder fires exactly at `remindAt`.

### 4.10 Reminder delivery - `POST /messages/reminder`

`ReminderDeliveryController` accepts `{waId, message}` (`ReminderMessageRequest`), logs
`Delivering reminder message to=...`, and calls `WhatsAppClient.sendText`, which POSTs a standard
Cloud API text message (`messaging_product: whatsapp`, `type: text`, `text.body`) and logs the Meta
response. If Meta rejects it, `RestClient` throws, the endpoint returns a 5xx, and the reminder
service's activity **retries** (Section 5.7). Note this endpoint is not idempotent: it does not
receive the `reminderId`, so if the first HTTP call succeeded but its response was lost, the retry
sends the same text a second time.

### 4.11 Support and UI endpoints

* **History** (`SessionHistoryController`): maps `flow_node_history` rows to `FlowHistoryView`.
  `/session/{waId}/history` returns every session for that customer, newest first.
* **Conversation view** (`ConversationController.conversation`): for each history row, also loads
  *all* outgoing transitions of that node and marks which one was `chosen` - "the road not taken next
  to the road walked".
* **Graph** (`ConversationController.flowGraph`): loads all `flow_node` rows for `flow_code = key`,
  their outgoing transitions, and returns `{flowKey, entryScreenId, nodes[], edges[]}`.
* **`flow-graph.html`**: pulls that graph and renders a Mermaid flowchart (Mermaid loaded from the
  jsDelivr CDN, so the browser needs internet).

### 4.12 Configuration reference (`application.yaml`)

| Key | Env override | Default | Meaning |
|---|---|---|---|
| `server.port` | - | `8082` | HTTP port |
| `spring.datasource.url/username/password` | - | `jdbc:postgresql://localhost:5434/chatbot_v3?currentSchema=flow_engine`, `chatbot` / `chatbot` | DB connection |
| `spring.jpa.hibernate.ddl-auto` | - | `validate` | Never create/alter schema |
| `temporal-workflow-service.base-url` | `TEMPORAL_WORKFLOW_SERVICE_BASE_URL` | `http://localhost:8083` | Where to POST `/reminders` |
| `reminder.funds.mode` | `FUNDS_REMINDER_MODE` | `DEMO` | `DEMO` = minutes, `PRODUCTION` = days |
| `whatsapp.cloud-api-base-url` | - | `https://graph.facebook.com/v20.0` | Cloud API base |
| `whatsapp.waba-id` | `WA_WABA_ID` | (set in file) | WhatsApp Business Account id |
| `whatsapp.phone-number-id` | `WA_PHONE_NUMBER_ID` | (set in file) | Sender number id (used in the `/messages` URL) |
| `whatsapp.access-token` | `WA_ACCESS_TOKEN` | (set in file) | Bearer token for Cloud API |
| `whatsapp.verify-token` | `WA_VERIFY_TOKEN` | `HDFCCERN` | Webhook verification token |
| `whatsapp.app-secret` | `WA_APP_SECRET` | (set in file) | HMAC key for `X-Hub-Signature-256` |
| `whatsapp.rsa-private-key-path` | `WA_RSA_PRIVATE_KEY_PATH` | `keys/private_plain.pem` | Flow decryption key |
| `whatsapp.rsa-private-key-passphrase` | `WA_RSA_PRIVATE_KEY_PASSPHRASE` | (set in file) | Only if the key is passphrase-encrypted |
| `flows.default-flow-key` | `FLOW_DEFAULT_KEY` | `AMB_REMINDER` | Flow used when none is named |
| `flows.definitions.<KEY>.*` | `WA_FLOW_ID_*`, `WA_FLOW_MODE_*` | see 4.5 | One block per flow |

(Secrets that have defaults in the file are deliberately not repeated here - see Section 11.)

### 4.13 Registering a new flow (checklist)

1. Design the screens and upload the JSON to WhatsApp Manager; note the **Flow ID**; set its
   `endpoint_uri` to `https://<public-host>/webhook/flow` (or `/webhook/flow/<KEY>`).
2. Add a block under `flows.definitions` in `application.yaml`.
3. Write a seed SQL (copy `db/03_...`): use a fresh node-id range (3000+), give every `screen_id` a
   unique name, add `flow_transition` rows whose `option_value`s equal the JSON option ids, set
   `back_target_node_id` for every non-entry screen that can go back, and `action_code` where a
   backend effect is needed.
4. Run the seed. Trigger with `POST /trigger {"to":"...","flowKey":"<KEY>"}`.

No Java change is needed unless the flow needs a **new** simulated action.

---

## 5. Service 2 - `temporal-workflow-service`

### 5.1 What Temporal is (just enough to read the code)

Temporal is a workflow engine that makes long-running code *durable*. The vocabulary used here:

| Term | Meaning in this service |
|---|---|
| **Workflow** | Ordinary-looking Java code (`ReminderWorkflowImpl.remind`) that Temporal executes and *persists step by step* in its event history. It can sleep for days and survive restarts. |
| **Timer / `Workflow.sleep`** | A server-side timer. While "sleeping", no thread is blocked and no memory is held in this app; the Temporal server just has a timer entry. |
| **Activity** | A unit of side-effecting work (`deliverReminder` - HTTP call + DB update). Activities may be retried automatically. |
| **Worker** | The part of *this Spring app* that polls Temporal for tasks and runs the workflow/activity code. |
| **Task queue** | Named queue workers poll. Here: `REMINDER_TASK_QUEUE`. |
| **Replay / determinism** | When a worker (re)loads a workflow it re-executes the code against the recorded history. Workflow code must therefore be deterministic: no `Instant.now()`, no random, no I/O - use `Workflow.currentTimeMillis()`, `Workflow.sleep`, and activities. |
| **Workflow id / run id** | Business-level unique id (`reminder-REM-XXXXXXXX`) and per-execution id. |

Temporal's own state lives in a **separate** Postgres owned by the Temporal server (see
`docker-compose.yml`), never in this app's database.

### 5.2 Package map - every class

Base package `com.hdfc.temporal_workflow_service`.

| Class | Layer | Purpose |
|---|---|---|
| `TemporalWorkflowServiceApplication` | entry | `@SpringBootApplication`; forces UTC default timezone. |
| `controller.ReminderController` | REST | `POST /reminders`, `GET /reminders/{id}`, `GET /reminders?waId=`. |
| `service.ReminderService` | logic | `createReminder` (start workflow + save row), `findById`, `findByWaId`. |
| `model.CreateReminderRequest` | DTO | `{waId, message, remindAt}` with validation (`@NotBlank`, `@NotBlank`, `@NotNull @Future`). |
| `model.CreateReminderResponse` | DTO | `{reminderId, workflowId, remindAt}`. |
| `model.ReminderResponse` | DTO | Full row view, `from(Reminder)` mapper. |
| `entity.Reminder` | JPA | The `reminder` table row. |
| `entity.ReminderStatus` | enum | `SCHEDULED`, `FIRED`, `FAILED`. |
| `repository.ReminderRepository` | JPA | `JpaRepository<Reminder,String>` + `findByWaId`. |
| `workflow.ReminderWorkflow` | Temporal | `@WorkflowInterface` with one `@WorkflowMethod remind(reminderId, waId, message, remindAt)`. |
| `workflow.ReminderWorkflowImpl` | Temporal | The durable timer (`@WorkflowImpl(taskQueues = REMINDER_TASK_QUEUE)`). |
| `workflow.ReminderTaskQueue` | constant | `"REMINDER_TASK_QUEUE"` - single source of truth so the workflow, the worker and the client agree. |
| `activity.ReminderActivities` | Temporal | `@ActivityInterface` with `deliverReminder(reminderId, waId, message, scheduledAt)`. |
| `activity.ReminderActivitiesImpl` | Temporal | Calls the flow engine and marks the row `FIRED` (`@ActivityImpl`, `@Component`). |
| `client.ReminderDeliveryClient` | HTTP | `POST {whatsapp-flow-engine-service.base-url}/messages/reminder`. |

Workers are found automatically: `spring.temporal.workers-auto-discovery.packages` lists this
package, so the `@WorkflowImpl` / `@ActivityImpl` classes are registered on `REMINDER_TASK_QUEUE`
with no manual worker bean.

### 5.3 REST API

| Method & path | Body / params | Success | Errors |
|---|---|---|---|
| `POST /reminders` | `{"waId":"9199...","message":"text","remindAt":"2026-09-19T12:30:00Z"}` | `201` `{reminderId, workflowId, remindAt}` | `400` if `waId`/`message` blank, `remindAt` null or **not in the future** (Bean Validation); `5xx` if Temporal is unreachable |
| `GET /reminders/{id}` | reminder id (`REM-...`) | `200` `ReminderResponse` | `404` |
| `GET /reminders?waId=...` | customer id | `200` list (possibly empty) | - |

`remindAt` is an ISO-8601 instant (UTC). The flow engine sends it as `Instant`.

### 5.4 Data model (database `chatbot_reminder`, schema `reminder_service`)

Created by `db/01_schema.sql` (create the database first - the file's header has the exact
commands). Table **`reminder`**:

| Column | Type | Meaning |
|---|---|---|
| `id` | varchar(32) PK | `REM-` + 8 uppercase hex chars |
| `wa_id` | varchar(32) | customer (indexed) |
| `message` | varchar(1000) | text to send |
| `remind_at` | timestamptz | when it is due |
| `status` | `SCHEDULED` / `FIRED` / `FAILED` (check constraint) | see below |
| `workflow_id` | varchar(100) | `reminder-<id>` |
| `run_id` | varchar(100) | Temporal run id at start |
| `created_at` | timestamptz | insert time |

This table is the *business* view ("list my reminders"). It is not the source of truth for timing -
Temporal is. Status meaning: `SCHEDULED` after creation; `FIRED` after successful delivery;
`FAILED` is defined and allowed by the constraint but **no code sets it today**, so a reminder whose
delivery permanently fails stays `SCHEDULED` (see Section 11).

### 5.5 Creating a reminder - `ReminderService.createReminder`

`@Transactional`:

1. `reminderId = "REM-" + first 8 chars of a random UUID, uppercased`; `workflowId = "reminder-" +
   reminderId`.
2. Build a typed workflow stub with that workflow id and task queue `REMINDER_TASK_QUEUE`.
3. `WorkflowClient.start(workflow::remind, reminderId, waId, message, remindAt)` - **asynchronous
   start**: returns as soon as Temporal has accepted the workflow (yielding a `WorkflowExecution`
   with `runId`); it does not wait for the reminder to fire.
4. Log `Reminder scheduled reminderId=... workflowId=... runId=... waId=... remindAt=... (fires in
   PT2M59.9S)`.
5. Save the `Reminder` row (`SCHEDULED`).
6. Return `201 {reminderId, workflowId, remindAt}`.

Ordering note: the workflow is started **before** the row is saved and the two are not atomic (one is
Temporal, one is Postgres). For DEMO delays this does not matter; the activity also copes with a
missing row (Section 5.7).

### 5.6 The workflow - `ReminderWorkflowImpl`

```java
@Override
public void remind(String reminderId, String waId, String message, Instant remindAt) {
    Instant now = Instant.ofEpochMilli(Workflow.currentTimeMillis());   // deterministic clock
    Duration delay = Duration.between(now, remindAt);
    if (delay.isPositive()) {
        log.info("Reminder timer started ... sleeping={} until={}", ...);
        Workflow.sleep(delay);                                          // durable timer
    } else {
        log.info("Reminder already due ... delivering immediately", ...);
    }
    log.info("Reminder timer fired ... delivering", ...);
    activities.deliverReminder(reminderId, waId, message, remindAt);    // side effect
    log.info("Reminder workflow completed ...", ...);
}
```

Why it is written this way:

* Time comes from `Workflow.currentTimeMillis()`, not `Instant.now()`, so replay is deterministic.
* `Workflow.sleep(delay)` creates a Temporal timer. Restart the reminder service during the wait and
  nothing is lost; when a worker is available again the timer still fires at the right time (if the
  service was down when it fired, the workflow simply proceeds when a worker returns - slightly late,
  not dropped).
* Logging uses `Workflow.getLogger(...)`, a **replay-aware** SLF4J logger: it does not re-emit lines
  while the worker replays history after a restart, so you do not see duplicate "timer started" lines.
* A non-positive delay (already due) skips the sleep and delivers immediately.
* The workflow has no overall timeout - it lives until delivery succeeds or the activity gives up.

### 5.7 The activity - `ReminderActivitiesImpl.deliverReminder`

Activity options (set in the workflow): `startToCloseTimeout = 30 s`, `RetryOptions.maximumAttempts
= 5` (default exponential backoff between attempts).

`@Transactional`. Steps:

1. Log `Delivering reminder reminderId=... waId=... scheduledFor=... attempt=N` (the attempt number
   comes from `Activity.getExecutionContext().getInfo().getAttempt()`).
2. `ReminderDeliveryClient.deliver(waId, message)` -> `POST /messages/reminder` on the flow engine.
   Any non-2xx or connection error throws, which makes Temporal retry (up to 5 attempts).
3. Log `Delivered reminder ...`.
4. Load the `reminder` row: if present set `FIRED` and save (log `Reminder marked FIRED`); if absent
   log a warning `Reminder row not found to mark FIRED`.

**Idempotency.** Because Temporal may retry, the *same* text can in theory be delivered twice (e.g. the
HTTP call succeeded but the response was lost). Marking `FIRED` is idempotent; the delivery call itself
is not de-duplicated by the flow engine today - `reminderId` is passed to the activity (and logged) as
the natural dedupe key, but `/messages/reminder` does not receive or check it.

If all 5 attempts fail the activity fails, the workflow fails (visible in the Temporal UI), and the
`reminder` row is left `SCHEDULED`.

### 5.8 Configuration reference (`application.yaml`)

| Key | Default | Meaning |
|---|---|---|
| `server.port` | `8083` | HTTP port |
| `spring.datasource.*` | `jdbc:postgresql://localhost:5434/chatbot_reminder?currentSchema=reminder_service`, `chatbot`/`chatbot` | own database |
| `spring.jpa.hibernate.ddl-auto` | `validate` | schema owned by SQL script |
| `spring.temporal.connection.target` | `127.0.0.1:7233` | Temporal frontend (gRPC) |
| `spring.temporal.namespace` | `default` | Temporal namespace |
| `spring.temporal.workers-auto-discovery.packages` | `com.hdfc.temporal_workflow_service` | where to find `@WorkflowImpl`/`@ActivityImpl` |
| `whatsapp-flow-engine-service.base-url` | `${WHATSAPP_FLOW_ENGINE_BASE_URL:http://localhost:8082}` | where to deliver fired reminders |

### 5.9 Durability and failure scenarios

| Situation | What happens |
|---|---|
| Reminder service restarted during the wait | Timer is in Temporal; on restart the worker reconnects, the workflow is replayed (no duplicate log lines) and the timer still fires on time. |
| Reminder service down when the timer fires | Temporal holds the task; once a worker is back the workflow continues (late, not lost). |
| Flow engine down at delivery | HTTP call fails -> activity retries (5 attempts, backoff). After that the workflow fails; row stays `SCHEDULED`. |
| WhatsApp rejects the message (e.g. expired token) | Flow engine's `sendText` throws -> 5xx -> same retry path. |
| Temporal server down at scheduling | `POST /reminders` fails; the flow engine surfaces "Something went wrong" to the customer. |
| Postgres (`chatbot_reminder`) down at scheduling | Transaction fails **after** the workflow may already have started -> an orphan workflow can exist with no row; it will still fire. |
| Flow engine's own transaction fails *after* scheduling succeeded | The reminder still exists and will fire even though the customer's session did not advance. |

---

## 6. Infrastructure and ports

### 6.1 Ports

| Port | Component |
|---|---|
| 8080 | `chat-bot-service` (a separate, older service) |
| 8081 | `whatsapp-flow-engine-service` V2 (older) |
| **8082** | **whatsapp-flow-engine-service-v3** |
| **8083** | **temporal-workflow-service** |
| **7233** | Temporal server (gRPC) |
| 8088 | Temporal Web UI (`http://localhost:8088`, mapped from the container's 8080) |
| 5434 | Shared Postgres (databases `chatbot_v3`, `chatbot_reminder`, ...) |
| 9092 | Kafka (leftover, unused) |

### 6.2 `docker-compose.yml` (repo parent directory)

* `temporal-postgres` - Postgres 16 dedicated to Temporal (user/password `temporal`).
* `temporal` - `temporalio/auto-setup:1.24.2`, exposes `7233`.
* `temporal-ui` - `temporalio/ui:2.31.2`, on `8088`.
* `kafka` - single-node KRaft Kafka 3.8.0 on `9092` - **no longer used** by either service.

The application Postgres (`5434`) is intentionally **not** in this compose file; it is a long-lived
container shared by all HDFC chatbot services.

---

## 7. Running everything locally

### 7.1 One-time setup

1. **Postgres** on `localhost:5434` with a role `chatbot`/`chatbot` that can create databases.
2. Create databases and schemas:

   ```
   psql -h localhost -p 5434 -U chatbot -d postgres -c "CREATE DATABASE chatbot_v3 OWNER chatbot;"
   psql -h localhost -p 5434 -U chatbot -d postgres -c "CREATE DATABASE chatbot_reminder OWNER chatbot;"

   psql "postgresql://chatbot:chatbot@localhost:5434/chatbot_v3"       -f whatsapp-flow-engine-service-v3/db/01_schema.sql
   psql "postgresql://chatbot:chatbot@localhost:5434/chatbot_v3"       -f whatsapp-flow-engine-service-v3/db/02_seed_amb_reminder_example.sql
   psql "postgresql://chatbot:chatbot@localhost:5434/chatbot_v3"       -f whatsapp-flow-engine-service-v3/db/03_seed_amb_reminder_radio_example.sql
   psql "postgresql://chatbot:chatbot@localhost:5434/chatbot_reminder" -f temporal-workflow-service/db/01_schema.sql
   ```
3. **Temporal**: from the parent directory, `docker compose up -d temporal-postgres temporal temporal-ui`
   (Kafka is not needed).
4. **RSA key** for Flow decryption at `whatsapp-flow-engine-service-v3/keys/private_plain.pem`
   (only needed for real Meta traffic, not for `/screen` testing).

### 7.2 Start order

1. Temporal server (and its UI).
2. `temporal-workflow-service` (port 8083) - it connects to Temporal at startup.
3. `whatsapp-flow-engine-service-v3` (port 8082).

(Order between the two apps is not strict - the flow engine only calls the reminder service when a
customer reaches the reminder screen.) Both were designed to be started from IntelliJ; from a shell use
`./mvnw spring-boot:run` in each project.

### 7.3 Useful environment variables

| Variable | Service | Effect |
|---|---|---|
| `FUNDS_REMINDER_MODE=PRODUCTION` | flow engine | real day-based delays instead of minutes |
| `TEMPORAL_WORKFLOW_SERVICE_BASE_URL` | flow engine | reminder service address |
| `WHATSAPP_FLOW_ENGINE_BASE_URL` | reminder service | flow engine address |
| `WA_PHONE_NUMBER_ID`, `WA_ACCESS_TOKEN`, `WA_APP_SECRET`, `WA_VERIFY_TOKEN`, `WA_RSA_PRIVATE_KEY_PATH` | flow engine | Meta credentials |
| `WA_FLOW_ID_AMB_REMINDER`, `WA_FLOW_MODE_AMB_REMINDER` | flow engine | Meta flow id / draft-vs-published (shared by both flows, see 4.5) |

> After changing code always **restart the service you changed** and make sure the process you are
> running is built from the branch you think it is (the classpath of the running JVM shows the
> directory). This exact mistake once caused a "no reminder arrived" report - the flow engine was
> running a build that did not yet contain DEMO mode.

---

## 8. Logging guide

Follow one reminder by grepping its `reminderId` (or `waId`). Expected sequence in DEMO mode
(`INFO` level; timestamps/logger prefixes omitted):

| # | Service | Log line (abridged) | Meaning |
|---|---|---|---|
| 1 | flow engine | `Funds reminder requested waId=91... timingOption=within_three_days mode=DEMO delay=3 MINUTES remindAt=...` | Option resolved to a delay |
| 2 | flow engine | `Scheduling reminder waId=... remindAt=...` | About to call the reminder service |
| 3 | reminder svc | `Reminder scheduled reminderId=REM-... workflowId=reminder-REM-... runId=... waId=... remindAt=... (fires in PT2M59.9S)` | Workflow started and row being saved |
| 4 | flow engine | `Scheduled reminder waId=... result=ScheduleResult[reminderId=..., ...]` | Reminder service answered `201` |
| 5 | reminder svc | `Reminder timer started reminderId=... waId=... sleeping=PT2M59S until=...` | Durable timer created |
| 6 | reminder svc | `Reminder timer fired reminderId=... waId=... - delivering` | Timer elapsed |
| 7 | reminder svc | `Delivering reminder reminderId=... waId=... scheduledFor=... attempt=1` | Activity started (attempt N) |
| 8 | flow engine | `Delivering reminder message to=...` | `/messages/reminder` hit |
| 9 | flow engine | `Sending text message to=... message=...` then `Text message response to=... response={...}` | The Cloud API call and Meta's reply |
| 10 | reminder svc | `Delivered reminder reminderId=... waId=...`, `Reminder marked FIRED ...` | Success |
| 11 | reminder svc | `Reminder workflow completed reminderId=... waId=...` | Workflow finished |

Other lines worth knowing on the flow engine: `Get data {raw body}` / `Flow data-endpoint request
(decrypted)` / `Flow data-endpoint response (before encryption)` for every data-endpoint call;
`Sending Flow trigger message ...` and its response for `/trigger`; `Flow completed: from=...
flow_token=...` on `nfm_reply`; warnings for missing sessions, bad signatures and unknown action codes.

> The flow engine logs full request bodies, phone numbers and decrypted payloads at INFO. Fine for a
> POC; scrub or lower this before handling real customer data.

---

## 9. Testing the reminder without WhatsApp

You can exercise the whole scheduling half using only `curl`, no Meta involved (the *delivery* half
will still try to call Meta at the end, so it will fail there unless valid WhatsApp credentials are
configured - which is itself a useful check of the retry path).

**Schedule directly against the reminder service:**

```bash
curl -X POST http://localhost:8083/reminders \
  -H "Content-Type: application/json" \
  -d '{"waId":"919999999999","message":"test","remindAt":"2030-01-01T00:00:00Z"}'
curl "http://localhost:8083/reminders?waId=919999999999"
```

**Drive the flow through the plain endpoint** (see `AMB_REMINDER_RADIO_FLOW_JOURNEY.md` for every branch):

```bash
# 1. welcome -> menu
curl -X POST localhost:8082/screen/AMB_REMINDER_RADIO -H "Content-Type: application/json" \
  -d '{"action":"data_exchange","flow_token":"T1","screen":"PRO_WELCOME_SCREEN","data":{}}'
# 2. menu -> timing screen
curl -X POST localhost:8082/screen/AMB_REMINDER_RADIO -H "Content-Type: application/json" \
  -d '{"action":"data_exchange","flow_token":"T1","screen":"PRO_AMB_MENU_SCREEN","data":{"amb_menu_option":"funds_shortly"}}'
# 3. timing -> reminder set (this is the call that schedules the reminder)
curl -X POST localhost:8082/screen/AMB_REMINDER_RADIO -H "Content-Type: application/json" \
  -d '{"action":"data_exchange","flow_token":"T1","screen":"PRO_FUNDS_TIMING_SCREEN","data":{"funds_timing_option":"within_three_days"}}'
```

Step 3's response contains `reminder_id` and `reminder_date`. With a session that has no `wa_id`
(`/screen` with an unknown token) the reminder targets `UNKNOWN`, so for a real end-to-end test
create the session with `POST /trigger` first (or watch the logs and Temporal UI).

**Inspect the timer:** open `http://localhost:8088`, find workflow `reminder-REM-...`; its history
shows `WorkflowExecutionStarted`, a `TimerStarted` event, and (after firing) `TimerFired`,
`ActivityTaskScheduled/Started/Completed`, `WorkflowExecutionCompleted`.

---

## 10. Troubleshooting: "I did not get the reminder"

Work through it in this order - each step narrows the problem.

1. **Is the flow engine running the right code?** Its first log line at the reminder step must read
   `Funds reminder requested ... mode=DEMO delay=3 MINUTES`. If that line is missing, or says
   `DAYS`, the running build has no DEMO mode - rebuild/restart from the current branch.
2. **Was a reminder created?** Look for `Reminder scheduled reminderId=...` in the reminder service and
   `GET /reminders?waId=<number>`. None -> the call from the flow engine failed (is 8083 up? check
   `TEMPORAL_WORKFLOW_SERVICE_BASE_URL`; is Temporal on 7233 reachable?).
3. **Is the timer running?** Temporal UI (`:8088`) -> the workflow should be `Running` with a pending
   timer. If the workflow is `Running` but nothing happens at the due time, the reminder service
   (worker) is not running or cannot reach Temporal.
4. **Did the timer fire?** `Reminder timer fired ...` then `Delivering reminder ... attempt=N`. Attempt
   numbers above 1 mean the flow engine returned errors.
5. **Did the flow engine receive it?** `Delivering reminder message to=...` and then
   `Text message response`. If the request never arrives, check `whatsapp-flow-engine-service.base-url`
   in the reminder service.
6. **Did Meta accept it?** The `Text message response` line has Meta's answer. An error instead means:
   * **expired/invalid access token** (`WA_ACCESS_TOKEN`) - temporary tokens expire quickly;
   * **wrong `waId`** - e.g. `UNKNOWN`, meaning the session had no real `wa_id`;
   * **outside the 24-hour customer-service window** - WhatsApp only allows *free-form* text within 24
     hours of the customer's last message to you. With the DEMO minutes-long delays you are inside the
     window; with PRODUCTION delays of days you generally are not (see Section 11);
   * the recipient not being an allowed test number while the app is in development mode.
7. **Message sent but not seen?** Check the number's WhatsApp chat with the business number; delivery
   receipts arrive as webhooks on `POST /webhook` (statuses are currently ignored by the code).

---

## 11. Known limitations and suggested next steps

Stated plainly so nobody is surprised later. None of these stop the DEMO working.

**Functional**

1. **24-hour messaging window (production blocker).** `sendText` sends a free-form text. WhatsApp only
   permits that within 24 hours of the customer's last inbound message; a reminder days later normally
   needs a pre-approved **template message** instead. DEMO minutes are fine; PRODUCTION days will
   likely be rejected by Meta unless the customer wrote in recently.
2. **`FAILED` is never set.** The enum/constraint has it, but exhausted retries leave the row
   `SCHEDULED`. A workflow-failure handler (try/catch around the activity, or a failure listener) should
   update the status.
3. **No idempotency on scheduling.** A retried Meta request for the same screen, or the customer going
   BACK and choosing again, schedules another reminder (each gets a new random `REM-` id and workflow).
   There is no "one active reminder per session" rule and no cancel/reschedule.
4. **Delivery is not de-duplicated.** `/messages/reminder` does not receive the `reminderId`, so an
   activity retry after a lost response can send the text twice.
5. **External call inside a DB transaction.** The Temporal scheduling HTTP call happens inside
   `dataExchange`'s transaction; a later failure rolls back the session but not the already-created
   reminder (orphan). Consider an outbox or scheduling after commit.
6. **`reminder_date` is a date only** (IST) and the terminal screen text says "one day before the
   expected date" although the code fires exactly at `remindAt`. In DEMO mode the labels say days while
   the delay is minutes.
7. **`EXPIRED` status is unused;** no job expires stale `OPENED`/`IN_PROGRESS` sessions.
8. **BACK does not roll back context.** Answers from the abandoned path remain in the session context.
9. **Delivery-status webhooks are ignored** (only `nfm_reply` is processed), so the system cannot tell
   whether a delivered reminder was read/failed downstream.

**Security / hygiene**

10. **Default secrets in `application.yaml`.** `WA_ACCESS_TOKEN`, `WA_APP_SECRET` and the RSA key
    passphrase have real-looking *default values written in the file*, and `README.md` claims nothing
    sensitive is hardcoded - the two contradict. Treat those values as compromised if the repo is ever
    shared: rotate them, remove the defaults, and inject via environment/secret manager.
11. **Unauthenticated internal endpoints** (`/trigger`, `/screen`, `/messages/reminder`, history/graph on
    the flow engine; `/reminders` on the reminder service). Fine for a local POC; needs network
    restriction or authentication before real use. `/screen` also bypasses signature and encryption by
    design.
12. **PII in logs** (phone numbers, full payloads at INFO).

**Housekeeping**

13. `docker-compose.yml` still starts an unused Kafka container.
14. `AMB_REMINDER_FLOW_JOURNEY.md` / `AMB_REMINDER_RADIO_FLOW_JOURNEY.md` still describe the timing
    step as a simulated `computeReminderDate()` of +3/+7/+15 days; the real behaviour is now the
    Temporal-backed scheduling described here.
15. Both flows read `WA_FLOW_ID_AMB_REMINDER` / `WA_FLOW_MODE_AMB_REMINDER` (Section 4.5).
16. `ReminderWorkflow`'s Javadoc still says "publishes the reminder event" (a leftover from the Kafka
    design); it now calls the flow engine over HTTP.
17. `temporal-workflow-service` has no Git remote configured, and its default branch is `master`
    (the flow engine uses `main`).
18. Tests: the reminder service only has the default Spring context-load test; there are no unit tests
    for the delay mapping or the workflow (Temporal's `temporal-testing` test-server dependency is
    already on the classpath and could be used for time-skipping workflow tests).

---

## 12. Glossary

| Term | Meaning |
|---|---|
| **AMB** | Average Monthly Balance - the minimum balance a savings account must maintain. |
| **Flow (WhatsApp Flow)** | Meta's feature for multi-screen forms inside WhatsApp. |
| **Data-endpoint** | The HTTPS URL of *your* server that Meta calls for each Flow step. Here `/webhook/flow`. |
| **`flow_token`** | Opaque id minted when a Flow is sent; echoed by Meta on every call; the primary key of `flow_session`. |
| **`wa_id`** | The customer's WhatsApp id (their phone number in international format). |
| **`nfm_reply`** | The inbound WhatsApp message Meta sends when a customer completes a Flow. |
| **INIT / data_exchange / BACK / ping** | The four actions Meta's data-endpoint protocol uses. |
| **Node / transition** | A screen / an option that leads from one screen to another. |
| **Terminal screen** | A last screen; tapping its button completes the Flow. |
| **Context** | The JSON blob on the session accumulating all answers and action outputs. |
| **Simulated action / `action_code`** | Backend side-effect run when arriving at a node. |
| **DEMO / PRODUCTION mode** | `reminder.funds.mode`: reminders in minutes (3/5/7) vs days (3/7/15). |
| **Workflow / activity / worker / task queue / timer** | Temporal concepts - see Section 5.1. |
| **Cloud API** | Meta's HTTP API for sending WhatsApp messages. |
| **OAEP / GCM** | RSA padding scheme / AES authenticated-encryption mode used by Meta's Flow encryption. |
