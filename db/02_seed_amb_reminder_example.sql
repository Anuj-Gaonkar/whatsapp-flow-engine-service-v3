-- whatsapp-flow-engine-service-v3 - example flow graph: AMB_REMINDER
--
-- This is the flow_node/flow_transition data for the "AMB_REMINDER" flow key already registered
-- in application.yaml's flows.definitions block, matching src/main/resources/flows/
-- amb_reminder_flow.json 1:1 (upload that JSON as the Flow object in WhatsApp Manager, point its
-- endpoint_uri at this service's /webhook/flow, and set WA_FLOW_ID_AMB_REMINDER to its Flow ID).
--
-- Registering a NEW flow (e.g. INACTIVE_SALARY) means writing a script like this one for its own
-- screens/transitions - flow_code is just a label (not read by any query at runtime, every
-- lookup goes by screen_id/node_id, both globally unique across every flow) but keeping it
-- distinct per flow, and giving each flow its own disjoint node_id range, keeps this readable and
-- leaves room to insert a screen later without renumbering. This script uses 1000-1210; the next
-- flow could use 2000-2170, and so on.
--
-- Run once, after db/01_schema.sql:
--   psql "postgresql://chatbot:chatbot@localhost:5434/chatbot_v3" -f db/02_seed_amb_reminder_example.sql
--
-- Guarded to no-op if AMB_REMINDER's entry screen already exists, so it's safe to re-run.

SET search_path TO flow_engine;

DO $$
BEGIN
IF NOT EXISTS (SELECT 1 FROM flow_engine.flow_node WHERE screen_id = 'FULL_WELCOME_SCREEN') THEN

INSERT INTO flow_engine.flow_node (node_id, flow_code, screen_id, node_type, back_target_node_id, action_code) VALUES
(1000, 'AMB_REMINDER', 'FULL_WELCOME_SCREEN',            'SCREEN',   NULL, NULL),
(1010, 'AMB_REMINDER', 'FULL_AMB_MENU_SCREEN',            'SCREEN',   1000, NULL),
(1020, 'AMB_REMINDER', 'FULL_FUND_NOW_SCREEN',            'TERMINAL', 1010, 'GENERATE_FUND_LINK'),
(1030, 'AMB_REMINDER', 'FULL_FUNDS_SHORTLY_ACK_SCREEN',   'SCREEN',   1010, NULL),
(1040, 'AMB_REMINDER', 'FULL_FUNDS_TIMING_SCREEN',        'SCREEN',   1030, NULL),
(1050, 'AMB_REMINDER', 'FULL_FUNDS_REMINDER_SET_SCREEN',  'TERMINAL', 1040, 'SCHEDULE_FUNDS_REMINDER'),
(1060, 'AMB_REMINDER', 'FULL_CASH_FLOW_ACK_SCREEN',       'SCREEN',   1010, NULL),
(1070, 'AMB_REMINDER', 'FULL_CASH_FLOW_MENU_SCREEN',      'SCREEN',   1060, NULL),
(1080, 'AMB_REMINDER', 'FULL_EXECUTIVE_HANDOFF_SCREEN',   'TERMINAL', 1070, 'ROUTE_TO_EXECUTIVE'),
(1090, 'AMB_REMINDER', 'FULL_CHARGES_INFO_SCREEN',        'TERMINAL', 1070, NULL),
(1100, 'AMB_REMINDER', 'FULL_UNAWARE_ACK_SCREEN',         'SCREEN',   1010, NULL),
(1110, 'AMB_REMINDER', 'FULL_INFO_MENU_SCREEN',           'SCREEN',   1100, NULL),
(1120, 'AMB_REMINDER', 'FULL_INFO_REDIRECT_SCREEN',       'TERMINAL', 1110, NULL),
(1130, 'AMB_REMINDER', 'FULL_ACCOUNT_UPGRADE_SCREEN',     'TERMINAL', 1110, NULL),
(1140, 'AMB_REMINDER', 'FULL_CHURN_ACK_SCREEN',           'SCREEN',   1010, NULL),
(1150, 'AMB_REMINDER', 'FULL_CHURN_REASON_SCREEN',        'SCREEN',   1140, NULL),
(1160, 'AMB_REMINDER', 'FULL_OTHER_REASON_SCREEN',        'SCREEN',   1150, NULL),
(1170, 'AMB_REMINDER', 'FULL_CALLBACK_CONFIRM_SCREEN',    'SCREEN',   1150, NULL),
(1180, 'AMB_REMINDER', 'FULL_CALLBACK_LOGGED_SCREEN',     'TERMINAL', 1170, 'LOG_CALLBACK_REQUEST'),
(1190, 'AMB_REMINDER', 'FULL_SALARY_OFFER_SCREEN',        'TERMINAL', 1150, 'CONVERT_SALARY_ACCOUNT'),
(1200, 'AMB_REMINDER', 'FULL_RETENTION_APPEAL_SCREEN',    'TERMINAL', 1150, NULL),
(1210, 'AMB_REMINDER', 'FULL_VISIT_BRANCH_SCREEN',        'TERMINAL', 1150, NULL)
ON CONFLICT (node_id) DO NOTHING;

INSERT INTO flow_engine.flow_transition (from_node_id, option_value, option_label, to_node_id, display_order) VALUES
(1000, NULL,                            NULL,                                          1010, 1),

(1010, 'fund_now',                      'I will fund my account now',                 1020, 1),
(1010, 'funds_shortly',                 'I expect funds shortly',                     1030, 2),
(1010, 'cash_flow_constraint',          'I''m facing temporary cash flow constraints',1060, 3),
(1010, 'unaware_requirement',           'I wasn''t aware of the balance requirement', 1100, 4),
(1010, 'inactive_account',              'I no longer actively use this account',      1140, 5),

(1030, NULL,                            NULL,                                          1040, 1),
(1040, 'within_three_days',             'Within 3 days',                              1050, 1),
(1040, 'within_seven_days',             'Within 7 days',                              1050, 2),
(1040, 'within_fifteen_days',           'Within 15 days',                             1050, 3),

(1060, NULL,                            NULL,                                          1070, 1),
(1070, 'remind_me_later',               'Remind me later',                            1040, 1),
(1070, 'speak_to_executive',            'Speak to an executive',                      1080, 2),
(1070, 'understand_charges',            'Understand applicable charges',              1090, 3),

(1100, NULL,                            NULL,                                          1110, 1),
(1110, 'amb_charges',                   'AMB charges',                                 1120, 1),
(1110, 'easy_ways_to_maintain_balance', 'Easy ways to maintain balance',               1120, 2),
(1110, 'upgrade_benefits',              'Upgrade benefits',                           1130, 3),

(1140, NULL,                            NULL,                                          1150, 1),
(1150, 'salary_moved',                  'Salary moved elsewhere',                     1190, 1),
(1150, 'better_offer_elsewhere',        'Better offer elsewhere',                     1200, 2),
(1150, 'account_no_longer_needed',      'Account no longer needed',                   1210, 3),
(1150, 'service_concern',               'Service concern',                            1170, 4),
(1150, 'other',                         'Other (please specify)',                     1160, 5),
(1160, NULL,                            NULL,                                          1170, 1),
(1170, NULL,                            NULL,                                          1180, 1);

END IF;
END $$;
