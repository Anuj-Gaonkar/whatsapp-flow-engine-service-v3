package com.hdfc.flowengine.service;

import com.hdfc.flowengine.entity.FlowNode;
import com.hdfc.flowengine.entity.FlowNodeHistory;
import com.hdfc.flowengine.entity.FlowSession;
import com.hdfc.flowengine.entity.FlowSessionStatus;
import com.hdfc.flowengine.entity.FlowTransition;
import com.hdfc.flowengine.entity.HistoryActionType;
import com.hdfc.flowengine.model.ScreenResponse;
import com.hdfc.flowengine.repository.FlowNodeHistoryRepository;
import com.hdfc.flowengine.repository.FlowNodeRepository;
import com.hdfc.flowengine.repository.FlowSessionRepository;
import com.hdfc.flowengine.repository.FlowTransitionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives every registered WhatsApp Flow's screens - one method per action Meta's data-endpoint
 * can send (INIT/data_exchange/BACK), plus ping and completion. Flow-agnostic: which flow a
 * screen belongs to is never checked here, only screen_id/node_id, which are unique across every
 * registered flow (see FlowRegistry). Every call that touches a session also writes a
 * FlowNodeHistory row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlowEngineService {

	// wa_id is NOT NULL on flow_session, but WhatsApp Manager's own Preview/Flow Builder testing
	// tool calls INIT directly with a synthetic flow_token and no wa_id at all - it isn't
	// simulating a real customer, just exercising the flow's screens.
	private static final String UNKNOWN_WA_ID_PLACEHOLDER = "UNKNOWN";

	private static final String ACTION_GENERATE_FUND_LINK = "GENERATE_FUND_LINK";
	private static final String ACTION_SCHEDULE_FUNDS_REMINDER = "SCHEDULE_FUNDS_REMINDER";
	private static final String ACTION_ROUTE_TO_EXECUTIVE = "ROUTE_TO_EXECUTIVE";
	private static final String ACTION_CONVERT_SALARY_ACCOUNT = "CONVERT_SALARY_ACCOUNT";
	private static final String ACTION_LOG_CALLBACK_REQUEST = "LOG_CALLBACK_REQUEST";

	private static final String FUNDS_TIMING_OPTION_FIELD = "funds_timing_option";

	private final FlowNodeRepository flowNodeRepository;
	private final FlowTransitionRepository flowTransitionRepository;
	private final FlowSessionRepository flowSessionRepository;
	private final FlowNodeHistoryRepository flowNodeHistoryRepository;

	/**
	 * Meta's first call for a Flow instance. Normally the flow_session row already exists
	 * (created OPENED by POST /trigger when the Flow message was sent), with current_node_id
	 * already pointing at the correct entry node - so that row is trusted over
	 * defaultEntryScreenId, which only reflects which route Meta happened to call back on. If no
	 * matching session is found (the Flow was opened outside this service's own trigger, e.g.
	 * WhatsApp Manager's Preview button), a fallback session is created at defaultEntryScreenId
	 * instead, so the interaction isn't silently dropped.
	 */
	@Transactional
	public ScreenResponse init(String flowToken, String waId, String defaultEntryScreenId) {
		Instant now = Instant.now();
		Optional<FlowSession> existing = flowSessionRepository.findById(flowToken);

		FlowSession session;
		FlowNode entryNode;
		if (existing.isPresent()) {
			session = existing.get();
			entryNode = loadNodeById(session.getCurrentNodeId());
		} else {
			log.warn("No flow_session found for flow_token={} on INIT; creating a fallback session at {}",
					flowToken, defaultEntryScreenId);
			entryNode = loadNodeByScreenId(defaultEntryScreenId);
			session = FlowSession.builder()
					.flowToken(flowToken)
					.waId(resolveWaId(waId))
					.startedAt(now)
					.build();
		}
		session.setCurrentNodeId(entryNode.getNodeId());
		session.setStatus(FlowSessionStatus.IN_PROGRESS);
		session.setLastInteractionAt(now);
		// INIT means the flow is being (re)started from the top - clear any answers left over
		// from a previous pass (WhatsApp Manager's Preview tool always reopens with the same
		// synthetic flow_token).
		session.getContext().clear();
		flowSessionRepository.save(session);

		ScreenResponse response = new ScreenResponse(entryNode.getScreenId(), Map.of());
		recordHistory(flowToken, session.getWaId(), entryNode, HistoryActionType.INIT, null, response, null);
		return response;
	}

	/**
	 * Handles a screen tap. Matches the submitted payload against the current screen's outgoing
	 * FlowTransition rows, merges the payload into the session's context, and moves
	 * current_node_id to whichever screen the match resolves to.
	 */
	@Transactional
	public ScreenResponse dataExchange(String flowToken, String screenId, Map<String, Object> formData) {
		FlowNode current = loadNodeByScreenId(screenId);
		Instant now = Instant.now();

		FlowSession session = flowSessionRepository.findById(flowToken).orElseGet(() -> {
			log.warn("No flow_session found for flow_token={} on data_exchange (screen={}); creating a "
					+ "fallback session", flowToken, screenId);
			return FlowSession.builder()
					.flowToken(flowToken)
					.waId(resolveWaId(null))
					.currentNodeId(current.getNodeId())
					.status(FlowSessionStatus.OPENED)
					.startedAt(now)
					.build();
		});
		session.setLastInteractionAt(now);
		if (session.getStatus() == FlowSessionStatus.OPENED) {
			session.setStatus(FlowSessionStatus.IN_PROGRESS);
		}
		if (formData != null) {
			session.getContext().putAll(formData);
		}

		List<FlowTransition> options = flowTransitionRepository.findByFromNodeIdOrderByDisplayOrderAsc(current.getNodeId());
		FlowTransition transition = matchTransition(options, formData)
				.orElseThrow(() -> new IllegalStateException(
						"No transition matched for screen " + screenId + " with payload " + formData));

		FlowNode next = loadNodeById(transition.getToNodeId());
		if (next.getActionCode() != null) {
			applySimulatedAction(next.getActionCode(), session);
		}
		session.setCurrentNodeId(next.getNodeId());
		flowSessionRepository.save(session);

		ScreenResponse response = new ScreenResponse(next.getScreenId(), session.getContext());
		recordHistory(flowToken, session.getWaId(), current, HistoryActionType.DATA_EXCHANGE, formData, response,
				transition.getOptionValue());
		return response;
	}

	/**
	 * A handful of nodes simulate a backend call before their data is rendered - fabricated
	 * fields are merged into the session's context, so they flow into the next screen's returned
	 * data exactly like any customer-submitted field would. Add a case here when a newly
	 * registered flow needs its own simulated backend action.
	 */
	private void applySimulatedAction(String actionCode, FlowSession session) {
		Map<String, Object> context = session.getContext();
		switch (actionCode) {
			case ACTION_GENERATE_FUND_LINK ->
					context.put("payment_link", "https://pay.hdfcbank.com/amb/" + shortId());
			case ACTION_SCHEDULE_FUNDS_REMINDER -> {
				context.put("reminder_id", "REM-" + shortId());
				context.put("reminder_date", computeReminderDate(context.get(FUNDS_TIMING_OPTION_FIELD)));
			}
			case ACTION_ROUTE_TO_EXECUTIVE -> context.put("handoff_id", "HANDOFF-" + shortId());
			case ACTION_CONVERT_SALARY_ACCOUNT -> context.put("request_id", "REQ-" + shortId());
			case ACTION_LOG_CALLBACK_REQUEST -> context.put("callback_id", "CB-" + shortId());
			default -> log.warn("Unknown action_code {} on a flow_node - no simulated side effect applied", actionCode);
		}
	}

	private String shortId() {
		return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}

	private String computeReminderDate(Object timingOption) {
		int days = switch (String.valueOf(timingOption)) {
			case "within_three_days" -> 3;
			case "within_seven_days" -> 7;
			case "within_fifteen_days" -> 15;
			default -> 7;
		};
		return LocalDate.now().plusDays(days).toString();
	}

	/**
	 * Handles Meta's BACK action - a static lookup (FlowNode.backTargetNodeId) rather than a
	 * reverse graph walk or history replay.
	 */
	@Transactional
	public ScreenResponse back(String flowToken, String screenId) {
		FlowSession session = loadSession(flowToken);
		FlowNode current = loadNodeByScreenId(screenId);
		session.setLastInteractionAt(Instant.now());

		Long backTargetNodeId = current.getBackTargetNodeId();
		if (backTargetNodeId == null) {
			throw new IllegalStateException("Screen " + screenId + " has no back target");
		}
		FlowNode backNode = loadNodeById(backTargetNodeId);
		session.setCurrentNodeId(backNode.getNodeId());
		flowSessionRepository.save(session);

		ScreenResponse response = new ScreenResponse(backNode.getScreenId(), session.getContext());
		recordHistory(flowToken, session.getWaId(), current, HistoryActionType.BACK, null, response, null);
		return response;
	}

	/** Meta's periodic health check on the data-endpoint itself - not tied to any session. */
	public Map<String, Object> ping() {
		return Map.of("status", "active");
	}

	/**
	 * Called once the terminal screen's {@code complete} nfm_reply arrives on the plain
	 * messaging webhook - the authoritative "customer tapped Done" signal. Every terminal screen
	 * is expected to embed a literal {@code outcome_screen} field (its own screen id) in its
	 * complete payload, so resolution is a direct, depth-independent screen_id lookup.
	 */
	@Transactional
	public void completeSession(String flowToken, String waIdFallback, Map<String, Object> rawPayload) {
		Instant now = Instant.now();
		Object outcomeScreen = rawPayload != null ? rawPayload.get("outcome_screen") : null;
		if (outcomeScreen == null) {
			throw new IllegalArgumentException("nfm_reply payload has no outcome_screen field: " + rawPayload);
		}
		FlowNode terminalNode = loadNodeByScreenId(String.valueOf(outcomeScreen));

		FlowSession session = flowSessionRepository.findById(flowToken).orElseGet(() -> {
			log.warn("No flow_session found for flow_token={} on completion; creating a fallback session", flowToken);
			return FlowSession.builder()
					.flowToken(flowToken)
					.waId(resolveWaId(waIdFallback))
					.startedAt(now)
					.build();
		});
		session.setCurrentNodeId(terminalNode.getNodeId());
		session.setStatus(FlowSessionStatus.COMPLETED);
		session.setLastInteractionAt(now);
		session.setCompletedAt(now);
		flowSessionRepository.save(session);

		ScreenResponse response = new ScreenResponse(terminalNode.getScreenId(), Map.of());
		recordHistory(flowToken, session.getWaId(), terminalNode, HistoryActionType.COMPLETED, rawPayload, response,
				null);
	}

	/**
	 * A node with exactly one, unconditional outgoing edge (option_value null) is taken
	 * regardless of payload. Otherwise, match whichever submitted field's value equals a
	 * transition's option_value - each data_exchange call only ever submits one meaningful
	 * field, and this doesn't care which field name it came from, only that some submitted value
	 * matches.
	 */
	private Optional<FlowTransition> matchTransition(List<FlowTransition> options, Map<String, Object> formData) {
		if (options.size() == 1 && options.get(0).getOptionValue() == null) {
			return Optional.of(options.get(0));
		}
		if (formData == null || formData.isEmpty()) {
			return Optional.empty();
		}
		return options.stream()
				.filter(t -> t.getOptionValue() != null && formData.containsValue(t.getOptionValue()))
				.findFirst();
	}

	private void recordHistory(String flowToken, String customerId, FlowNode node, HistoryActionType actionType,
			Map<String, Object> requestData, ScreenResponse response, String selectedValue) {
		Map<String, Object> responseData = new LinkedHashMap<>();
		responseData.put("screen", response.screen());
		responseData.put("data", response.data());

		flowNodeHistoryRepository.save(FlowNodeHistory.builder()
				.flowToken(flowToken)
				.customerId(customerId)
				.nodeId(node.getNodeId())
				.screenId(node.getScreenId())
				.actionType(actionType)
				.selectedValue(selectedValue)
				.requestData(requestData)
				.responseData(responseData)
				.occurredAt(Instant.now())
				.build());
	}

	private FlowNode loadNodeByScreenId(String screenId) {
		return flowNodeRepository.findByScreenId(screenId)
				.orElseThrow(() -> new IllegalStateException("Unknown screen " + screenId));
	}

	private FlowNode loadNodeById(Long nodeId) {
		return flowNodeRepository.findById(nodeId)
				.orElseThrow(() -> new IllegalStateException("Unknown node " + nodeId));
	}

	private String resolveWaId(String waId) {
		return (waId == null || waId.isBlank()) ? UNKNOWN_WA_ID_PLACEHOLDER : waId;
	}

	private FlowSession loadSession(String flowToken) {
		return flowSessionRepository.findById(flowToken)
				.orElseThrow(() -> new IllegalArgumentException("Unknown flow session " + flowToken));
	}
}
