-- whatsapp-flow-engine-service-v3 - flow graph: AMB_REMINDER_RADIO
--
-- flow_node/flow_transition data for the "AMB_REMINDER_RADIO" flow key already registered in
-- application.yaml's flows.definitions block, matching src/main/resources/flows/
-- amb_reminder_radio_flow.json 1:1 - the same AMB Reminder journey as AMB_REMINDER (see
-- db/02_seed_amb_reminder_example.sql), redesigned for the Flow medium's native RadioButtonsGroup
-- screens: each of AMB_REMINDER's 4 ack-screen-then-menu pairs (e.g.
-- FULL_FUNDS_SHORTLY_ACK_SCREEN -> FULL_CASH_FLOW_MENU_SCREEN) is merged into one PRO_* screen
-- here, so this graph has 18 screens instead of AMB_REMINDER's 22 for the same set of outcomes.
--
-- Uses the 2000-2170 node_id range - AMB_REMINDER already owns 1000-1210, kept disjoint so the
-- two graphs never collide. The next registered flow should use 3000+.
--
-- Run once, after db/01_schema.sql:
--   psql "postgresql://chatbot:chatbot@localhost:5434/chatbot_v3" -f db/03_seed_amb_reminder_radio_example.sql
--
-- Guarded to no-op if PRO_WELCOME_SCREEN already exists, so it's safe to re-run.

SET search_path TO flow_engine;

DO $$
BEGIN
IF NOT EXISTS (SELECT 1 FROM flow_engine.flow_node WHERE screen_id = 'PRO_WELCOME_SCREEN') THEN

INSERT INTO flow_engine.flow_node (node_id, flow_code, screen_id, node_type, back_target_node_id, action_code) VALUES
(2000, 'AMB_REMINDER_RADIO', 'PRO_WELCOME_SCREEN',           'SCREEN',   NULL, NULL),
(2010, 'AMB_REMINDER_RADIO', 'PRO_AMB_MENU_SCREEN',          'SCREEN',   2000, NULL),
(2020, 'AMB_REMINDER_RADIO', 'PRO_FUND_NOW_SCREEN',          'TERMINAL', 2010, 'GENERATE_FUND_LINK'),
(2030, 'AMB_REMINDER_RADIO', 'PRO_FUNDS_TIMING_SCREEN',      'SCREEN',   2010, NULL),
(2040, 'AMB_REMINDER_RADIO', 'PRO_FUNDS_REMINDER_SET_SCREEN','TERMINAL', 2030, 'SCHEDULE_FUNDS_REMINDER'),
(2050, 'AMB_REMINDER_RADIO', 'PRO_CASH_FLOW_MENU_SCREEN',    'SCREEN',   2010, NULL),
(2060, 'AMB_REMINDER_RADIO', 'PRO_EXECUTIVE_HANDOFF_SCREEN', 'TERMINAL', 2050, 'ROUTE_TO_EXECUTIVE'),
(2070, 'AMB_REMINDER_RADIO', 'PRO_CHARGES_INFO_SCREEN',      'TERMINAL', 2050, NULL),
(2080, 'AMB_REMINDER_RADIO', 'PRO_INFO_MENU_SCREEN',         'SCREEN',   2010, NULL),
(2090, 'AMB_REMINDER_RADIO', 'PRO_INFO_REDIRECT_SCREEN',     'TERMINAL', 2080, NULL),
(2100, 'AMB_REMINDER_RADIO', 'PRO_ACCOUNT_UPGRADE_SCREEN',   'TERMINAL', 2080, NULL),
(2110, 'AMB_REMINDER_RADIO', 'PRO_CHURN_REASON_SCREEN',      'SCREEN',   2010, NULL),
(2120, 'AMB_REMINDER_RADIO', 'PRO_OTHER_REASON_SCREEN',      'SCREEN',   2110, NULL),
(2130, 'AMB_REMINDER_RADIO', 'PRO_CALLBACK_CONFIRM_SCREEN',  'SCREEN',   2110, NULL),
(2140, 'AMB_REMINDER_RADIO', 'PRO_CALLBACK_LOGGED_SCREEN',   'TERMINAL', 2130, 'LOG_CALLBACK_REQUEST'),
(2150, 'AMB_REMINDER_RADIO', 'PRO_SALARY_OFFER_SCREEN',      'TERMINAL', 2110, 'CONVERT_SALARY_ACCOUNT'),
(2160, 'AMB_REMINDER_RADIO', 'PRO_RETENTION_APPEAL_SCREEN',  'TERMINAL', 2110, NULL),
(2170, 'AMB_REMINDER_RADIO', 'PRO_VISIT_BRANCH_SCREEN',      'TERMINAL', 2110, NULL)
ON CONFLICT (node_id) DO NOTHING;

INSERT INTO flow_engine.flow_transition (from_node_id, option_value, option_label, to_node_id, display_order) VALUES
(2000, NULL,                            NULL,                                          2010, 1),

(2010, 'fund_now',                      'I''ll fund my account now',                  2020, 1),
(2010, 'funds_shortly',                 'I expect funds shortly',                     2030, 2),
(2010, 'cash_flow_constraint',          'I''m facing a cash flow issue',               2050, 3),
(2010, 'unaware_requirement',           'I wasn''t aware of this',                     2080, 4),
(2010, 'inactive_account',              'This account is inactive',                   2110, 5),

(2030, 'within_three_days',             'Within 3 days',                              2040, 1),
(2030, 'within_seven_days',             'Within 7 days',                              2040, 2),
(2030, 'within_fifteen_days',           'Within 15 days',                             2040, 3),

(2050, 'remind_me_later',               'Remind me later',                            2030, 1),
(2050, 'speak_to_executive',            'Speak to an executive',                      2060, 2),
(2050, 'understand_charges',            'Understand applicable charges',              2070, 3),

(2080, 'amb_charges',                   'AMB charges',                                2090, 1),
(2080, 'easy_ways_to_maintain_balance', 'Easy ways to maintain balance',               2090, 2),
(2080, 'upgrade_benefits',              'Upgrade benefits',                           2100, 3),

(2110, 'salary_moved',                  'Salary moved elsewhere',                     2150, 1),
(2110, 'better_offer_elsewhere',        'Better offer elsewhere',                     2160, 2),
(2110, 'account_no_longer_needed',      'Account no longer needed',                   2170, 3),
(2110, 'service_concern',               'Service concern',                            2130, 4),
(2110, 'other',                         'Other (please specify)',                     2120, 5),

(2120, NULL,                            NULL,                                          2130, 1),
(2130, NULL,                            NULL,                                          2140, 1);

END IF;
END $$;
