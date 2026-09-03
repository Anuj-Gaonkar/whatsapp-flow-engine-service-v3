# AMB_REMINDER_RADIO — `/screen` journey reference

Copy-pasteable walkthrough of the `AMB_REMINDER_RADIO` flow (`PRO_*` screens, entry screen
`PRO_WELCOME_SCREEN`) via the plain, non-encrypted `POST /screen/AMB_REMINDER_RADIO` endpoint - no
RSA keys, HMAC signing, or real Meta traffic involved. Assumes the service is running locally on
`http://localhost:8082`, `db/01_schema.sql` and `db/03_seed_amb_reminder_radio_example.sql` have
both been run.

Unlike `AMB_REMINDER`, this flow key is **not** `flows.default-flow-key` - always use the
`/screen/AMB_REMINDER_RADIO` path (bare `/screen` falls back to `AMB_REMINDER`'s entry screen
instead).

Same request/response contract as `/webhook/flow` after decryption - `action`/`flow_token`/
`screen`/`data` in, `{screen, data}` out, no envelope, no signature header. Pick any `flow_token`
per session; response `data` is always the session's whole accumulated context, not just the
current screen's own fields.

This is the same AMB Reminder journey as `AMB_REMINDER` (see `AMB_REMINDER_FLOW_JOURNEY.md`),
redesigned for the Flow medium's native `RadioButtonsGroup` screens: each of `AMB_REMINDER`'s 4
ack-screen-then-menu pairs is merged into one `PRO_*` screen here, so there's one fewer
`data_exchange` hop per branch than the `AMB_REMINDER` equivalent.

---

## 0. Entry — `PRO_WELCOME_SCREEN` → `PRO_AMB_MENU_SCREEN`

Common to every branch below.

**Request**
```json
{
  "action": "data_exchange",
  "flow_token": "TEST-PRO-0001",
  "screen": "PRO_WELCOME_SCREEN",
  "data": {}
}
```

**Response**
```json
{
  "screen": "PRO_AMB_MENU_SCREEN",
  "data": {}
}
```

`PRO_AMB_MENU_SCREEN` submits one field, `amb_menu_option`, with one of 5 values - each picks a
different branch below.

---

## 1. Branch: `fund_now` — full walkthrough

**Request** — `PRO_AMB_MENU_SCREEN` → `PRO_FUND_NOW_SCREEN`
```json
{
  "action": "data_exchange",
  "flow_token": "TEST-PRO-0001",
  "screen": "PRO_AMB_MENU_SCREEN",
  "data": { "amb_menu_option": "fund_now" }
}
```

**Response** — `PRO_FUND_NOW_SCREEN` is terminal and carries `action_code=GENERATE_FUND_LINK`:
```json
{
  "screen": "PRO_FUND_NOW_SCREEN",
  "data": {
    "amb_menu_option": "fund_now",
    "payment_link": "https://pay.hdfcbank.com/amb/A1B2C3D4"
  }
}
```

Check what got recorded:
```
GET /sessions/TEST-PRO-0001/history
```

The other four branches off `PRO_AMB_MENU_SCREEN` are structurally identical - same shape of
call, just a different `amb_menu_option` value and destination. Covered as reference tables below.

---

## 2. Branch: `funds_shortly`

```
flow_token = "TEST-PRO-0002"
```

No separate ack screen here - `PRO_FUNDS_TIMING_SCREEN` is reached directly.

| Step | Request | Response `screen` |
|---|---|---|
| 1 | `PRO_AMB_MENU_SCREEN`, `amb_menu_option=funds_shortly` | `PRO_FUNDS_TIMING_SCREEN` |
| 2 | `PRO_FUNDS_TIMING_SCREEN`, `funds_timing_option=within_seven_days` | `PRO_FUNDS_REMINDER_SET_SCREEN` (terminal) |

`funds_timing_option` accepts `within_three_days` / `within_seven_days` / `within_fifteen_days` -
all three route to the same terminal screen; only the fabricated `reminder_date` differs.

Step 2 in full, since it carries the `action_code=SCHEDULE_FUNDS_REMINDER` effect:
```json
{
  "action": "data_exchange",
  "flow_token": "TEST-PRO-0002",
  "screen": "PRO_FUNDS_TIMING_SCREEN",
  "data": { "funds_timing_option": "within_seven_days" }
}
```
```json
{
  "screen": "PRO_FUNDS_REMINDER_SET_SCREEN",
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
flow_token = "TEST-PRO-0003"
```

| Step | Request | Response `screen` |
|---|---|---|
| 1 | `PRO_AMB_MENU_SCREEN`, `amb_menu_option=cash_flow_constraint` | `PRO_CASH_FLOW_MENU_SCREEN` |
| 2 | `PRO_CASH_FLOW_MENU_SCREEN`, `cash_flow_option=...` | see below |

`cash_flow_option` at step 2:

| Value | Next screen | Terminal? | `action_code` |
|---|---|---|---|
| `remind_me_later` | `PRO_FUNDS_TIMING_SCREEN` | No - re-enters branch 2's timing screen | - |
| `speak_to_executive` | `PRO_EXECUTIVE_HANDOFF_SCREEN` | Yes | `ROUTE_TO_EXECUTIVE` (adds `handoff_id`) |
| `understand_charges` | `PRO_CHARGES_INFO_SCREEN` | Yes | - |

Tip: step 2 can be repeated on the same `flow_token` with a different `cash_flow_option` each
time to explore all three without restarting the session, since `data_exchange` looks up the node
by the request's own `screen` field, not by `current_node_id`.

---

## 4. Branch: `unaware_requirement`

```
flow_token = "TEST-PRO-0004"
```

| Step | Request | Response `screen` |
|---|---|---|
| 1 | `PRO_AMB_MENU_SCREEN`, `amb_menu_option=unaware_requirement` | `PRO_INFO_MENU_SCREEN` |
| 2 | `PRO_INFO_MENU_SCREEN`, `info_option=...` | see below |

`info_option` at step 2 (all terminal, no `action_code`):

| Value | Next screen |
|---|---|
| `amb_charges` | `PRO_INFO_REDIRECT_SCREEN` |
| `easy_ways_to_maintain_balance` | `PRO_INFO_REDIRECT_SCREEN` |
| `upgrade_benefits` | `PRO_ACCOUNT_UPGRADE_SCREEN` |

---

## 5. Branch: `inactive_account`

```
flow_token = "TEST-PRO-0005"
```

| Step | Request | Response `screen` |
|---|---|---|
| 1 | `PRO_AMB_MENU_SCREEN`, `amb_menu_option=inactive_account` | `PRO_CHURN_REASON_SCREEN` |
| 2 | `PRO_CHURN_REASON_SCREEN`, `churn_reason_option=...` | see below |

`churn_reason_option` at step 2:

| Value | Next screen | Terminal? | `action_code` |
|---|---|---|---|
| `salary_moved` | `PRO_SALARY_OFFER_SCREEN` | Yes | `CONVERT_SALARY_ACCOUNT` (adds `request_id`) |
| `better_offer_elsewhere` | `PRO_RETENTION_APPEAL_SCREEN` | Yes | - |
| `account_no_longer_needed` | `PRO_VISIT_BRANCH_SCREEN` | Yes | - |
| `service_concern` | `PRO_CALLBACK_CONFIRM_SCREEN` | No - one more step | - |
| `other` | `PRO_OTHER_REASON_SCREEN` | No - one more step | - |

Two sub-paths continue past step 2:

**`service_concern`** — step 3: `PRO_CALLBACK_CONFIRM_SCREEN`, `data: {}` (single unconditional
edge) → `PRO_CALLBACK_LOGGED_SCREEN` (terminal, `action_code=LOG_CALLBACK_REQUEST`, adds
`callback_id`).

**`other`** — step 3: `PRO_OTHER_REASON_SCREEN`, `{"other_reason": "<free text>"}` →
`PRO_CALLBACK_CONFIRM_SCREEN` (single unconditional edge) → step 4: `PRO_CALLBACK_CONFIRM_SCREEN`,
`data: {}` → `PRO_CALLBACK_LOGGED_SCREEN` (same terminal as above).

Full step 3 example for `other`:
```json
{
  "action": "data_exchange",
  "flow_token": "TEST-PRO-0005",
  "screen": "PRO_OTHER_REASON_SCREEN",
  "data": { "other_reason": "Moving to a different city" }
}
```
```json
{
  "screen": "PRO_CALLBACK_CONFIRM_SCREEN",
  "data": {
    "amb_menu_option": "inactive_account",
    "churn_reason_option": "other",
    "other_reason": "Moving to a different city"
  }
}
```

---

## Completion — not part of `/screen`

Same as `AMB_REMINDER` (see `AMB_REMINDER_FLOW_JOURNEY.md`'s Completion section) - the customer
tapping **"Done"** arrives on `POST /webhook` as an `nfm_reply`, HMAC-signed regardless of whether
`/screen` or `/webhook/flow` is being used for the screen navigation itself. `outcome_screen` in
that payload must be one of this flow's own terminal `PRO_*` screen ids (e.g.
`PRO_FUND_NOW_SCREEN`) for `FlowEngineService.completeSession()` to resolve it.
