# AMB_REMINDER — `/screen` journey reference

Copy-pasteable walkthrough of the `AMB_REMINDER` flow (`FULL_*` screens, entry screen
`FULL_WELCOME_SCREEN`) via the plain, non-encrypted `POST /screen` endpoint - no RSA keys, HMAC
signing, or real Meta traffic involved. Assumes the service is running locally on
`http://localhost:8082`, `db/01_schema.sql` and `db/02_seed_amb_reminder_example.sql` have both
been run, and `AMB_REMINDER` is `flows.default-flow-key` (the config default), so `/screen` and
`/screen/AMB_REMINDER` are interchangeable below.

Every request is `POST` with `Content-Type: application/json`. No `X-Hub-Signature-256` header,
no `encrypted_flow_data`/`encrypted_aes_key`/`initial_vector` envelope - just the same
`action`/`flow_token`/`screen`/`data` body `/webhook/flow` would receive after decryption.

Pick any `flow_token` per session - it doesn't need a prior `POST /trigger` call first;
`FlowEngineService.dataExchange()` creates a fallback session the first time it sees an unknown
token. Response `data` is always the session's **whole accumulated context**, not just the
current screen's own fields - every prior `data_exchange` payload (and anything an `action_code`
fabricated) stays in there for the rest of the session.

---

## 0. Entry — `FULL_WELCOME_SCREEN` → `FULL_AMB_MENU_SCREEN`

Common to every branch below.

**Request**
```json
{
  "action": "data_exchange",
  "flow_token": "TEST-AMB-0001",
  "screen": "FULL_WELCOME_SCREEN",
  "data": {}
}
```

**Response**
```json
{
  "screen": "FULL_AMB_MENU_SCREEN",
  "data": {}
}
```

`FULL_AMB_MENU_SCREEN` submits one field, `amb_menu_option`, with one of 5 values - each picks a
different branch below.

---

## 1. Branch: `fund_now` — full walkthrough

**Request** — `FULL_AMB_MENU_SCREEN` → `FULL_FUND_NOW_SCREEN`
```json
{
  "action": "data_exchange",
  "flow_token": "TEST-AMB-0001",
  "screen": "FULL_AMB_MENU_SCREEN",
  "data": { "amb_menu_option": "fund_now" }
}
```

**Response** — `FULL_FUND_NOW_SCREEN` is terminal and carries `action_code=GENERATE_FUND_LINK`,
so `FlowEngineService.applySimulatedAction()` fires before the response is built, fabricating
`payment_link` into the session context:
```json
{
  "screen": "FULL_FUND_NOW_SCREEN",
  "data": {
    "amb_menu_option": "fund_now",
    "payment_link": "https://pay.hdfcbank.com/amb/A1B2C3D4"
  }
}
```

`flow_session.status` is now `IN_PROGRESS`, `current_node_id` = `FULL_FUND_NOW_SCREEN`'s node.
`/screen` never marks a session `COMPLETED` - only `POST /webhook`'s `nfm_reply` handling does
(see the bottom of this doc). Check what got recorded:

```
GET /sessions/TEST-AMB-0001/history
```

The other four branches off `FULL_AMB_MENU_SCREEN` are structurally identical to this one - same
shape of call, just a different `amb_menu_option` value and a different destination screen. They're
covered as reference tables below rather than repeated in full.

---

## 2. Branch: `funds_shortly`

```
flow_token = "TEST-AMB-0002"
```

| Step | Request (`screen` / submitted field) | Response `screen` |
|---|---|---|
| 1 | `FULL_AMB_MENU_SCREEN`, `amb_menu_option=funds_shortly` | `FULL_FUNDS_SHORTLY_ACK_SCREEN` |
| 2 | `FULL_FUNDS_SHORTLY_ACK_SCREEN`, `data: {}` (single unconditional edge) | `FULL_FUNDS_TIMING_SCREEN` |
| 3 | `FULL_FUNDS_TIMING_SCREEN`, `funds_timing_option=within_seven_days` | `FULL_FUNDS_REMINDER_SET_SCREEN` (terminal) |

`funds_timing_option` accepts `within_three_days` / `within_seven_days` / `within_fifteen_days` -
all three route to the same terminal screen; only the fabricated `reminder_date` differs
(`computeReminderDate()`: +3 / +7 / +15 days from today).

Step 3 request/response in full, since it carries the `action_code=SCHEDULE_FUNDS_REMINDER` effect:
```json
{
  "action": "data_exchange",
  "flow_token": "TEST-AMB-0002",
  "screen": "FULL_FUNDS_TIMING_SCREEN",
  "data": { "funds_timing_option": "within_seven_days" }
}
```
```json
{
  "screen": "FULL_FUNDS_REMINDER_SET_SCREEN",
  "data": {
    "amb_menu_option": "funds_shortly",
    "funds_timing_option": "within_seven_days",
    "reminder_id": "REM-A1B2C3D4",
    "reminder_date": "2026-09-09"
  }
}
```

---

## 3. Branch: `cash_flow_constraint`

```
flow_token = "TEST-AMB-0003"
```

| Step | Request | Response `screen` |
|---|---|---|
| 1 | `FULL_AMB_MENU_SCREEN`, `amb_menu_option=cash_flow_constraint` | `FULL_CASH_FLOW_ACK_SCREEN` |
| 2 | `FULL_CASH_FLOW_ACK_SCREEN`, `data: {}` | `FULL_CASH_FLOW_MENU_SCREEN` |
| 3 | `FULL_CASH_FLOW_MENU_SCREEN`, `cash_flow_option=...` | see below |

`cash_flow_option` at step 3:

| Value | Next screen | Terminal? | `action_code` |
|---|---|---|---|
| `remind_me_later` | `FULL_FUNDS_TIMING_SCREEN` | No - re-enters branch 2's timing screen | - |
| `speak_to_executive` | `FULL_EXECUTIVE_HANDOFF_SCREEN` | Yes | `ROUTE_TO_EXECUTIVE` (adds `handoff_id`) |
| `understand_charges` | `FULL_CHARGES_INFO_SCREEN` | Yes | - |

Tip: since `data_exchange` looks up the node by the request's own `screen` field (not by
`current_node_id`), step 3 can be repeated on the same `flow_token` with a different
`cash_flow_option` each time to explore all three without restarting the session.

---

## 4. Branch: `unaware_requirement`

```
flow_token = "TEST-AMB-0004"
```

| Step | Request | Response `screen` |
|---|---|---|
| 1 | `FULL_AMB_MENU_SCREEN`, `amb_menu_option=unaware_requirement` | `FULL_UNAWARE_ACK_SCREEN` |
| 2 | `FULL_UNAWARE_ACK_SCREEN`, `data: {}` | `FULL_INFO_MENU_SCREEN` |
| 3 | `FULL_INFO_MENU_SCREEN`, `info_option=...` | see below |

`info_option` at step 3 (all terminal, no `action_code`):

| Value | Next screen |
|---|---|
| `amb_charges` | `FULL_INFO_REDIRECT_SCREEN` |
| `easy_ways_to_maintain_balance` | `FULL_INFO_REDIRECT_SCREEN` |
| `upgrade_benefits` | `FULL_ACCOUNT_UPGRADE_SCREEN` |

---

## 5. Branch: `inactive_account`

```
flow_token = "TEST-AMB-0005"
```

| Step | Request | Response `screen` |
|---|---|---|
| 1 | `FULL_AMB_MENU_SCREEN`, `amb_menu_option=inactive_account` | `FULL_CHURN_ACK_SCREEN` |
| 2 | `FULL_CHURN_ACK_SCREEN`, `data: {}` | `FULL_CHURN_REASON_SCREEN` |
| 3 | `FULL_CHURN_REASON_SCREEN`, `churn_reason_option=...` | see below |

`churn_reason_option` at step 3:

| Value | Next screen | Terminal? | `action_code` |
|---|---|---|---|
| `salary_moved` | `FULL_SALARY_OFFER_SCREEN` | Yes | `CONVERT_SALARY_ACCOUNT` (adds `request_id`) |
| `better_offer_elsewhere` | `FULL_RETENTION_APPEAL_SCREEN` | Yes | - |
| `account_no_longer_needed` | `FULL_VISIT_BRANCH_SCREEN` | Yes | - |
| `service_concern` | `FULL_CALLBACK_CONFIRM_SCREEN` | No - one more step | - |
| `other` | `FULL_OTHER_REASON_SCREEN` | No - one more step | - |

Two sub-paths continue past step 3:

**`service_concern`** — step 4: `FULL_CALLBACK_CONFIRM_SCREEN`, `data: {}` (single unconditional
edge) → `FULL_CALLBACK_LOGGED_SCREEN` (terminal, `action_code=LOG_CALLBACK_REQUEST`, adds
`callback_id`).

**`other`** — step 4: `FULL_OTHER_REASON_SCREEN`, `{"other_reason": "<free text>"}` →
`FULL_CALLBACK_CONFIRM_SCREEN` (single unconditional edge) → step 5: `FULL_CALLBACK_CONFIRM_SCREEN`,
`data: {}` → `FULL_CALLBACK_LOGGED_SCREEN` (same terminal as above).

Full step 4 example for `other`:
```json
{
  "action": "data_exchange",
  "flow_token": "TEST-AMB-0005",
  "screen": "FULL_OTHER_REASON_SCREEN",
  "data": { "other_reason": "Moving to a different city" }
}
```
```json
{
  "screen": "FULL_CALLBACK_CONFIRM_SCREEN",
  "data": {
    "amb_menu_option": "inactive_account",
    "churn_reason_option": "other",
    "other_reason": "Moving to a different city"
  }
}
```

---

## Completion — not part of `/screen`

The customer tapping **"Done"** on any terminal screen never goes through `/screen` (or
`/webhook/flow`) at all - Meta delivers it as an ordinary inbound message on `POST /webhook`, with
`interactive.type == "nfm_reply"` and `nfm_reply.response_json.outcome_screen` set to that
terminal screen's own id. That's the only thing that moves `flow_session.status` to `COMPLETED`.
`/webhook` always verifies `X-Hub-Signature-256` (independent of `/screen`/`/webhook/flow` being
plaintext or encrypted), so simulating it locally means computing a real HMAC-SHA256 over the raw
body with `whatsapp.app-secret`. See the completion example in the `/screen` testing notes for the
exact `curl` shape.
