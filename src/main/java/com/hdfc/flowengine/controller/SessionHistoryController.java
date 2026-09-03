package com.hdfc.flowengine.controller;

import com.hdfc.flowengine.entity.FlowNodeHistory;
import com.hdfc.flowengine.entity.FlowSession;
import com.hdfc.flowengine.model.FlowHistoryView;
import com.hdfc.flowengine.model.SessionHistoryView;
import com.hdfc.flowengine.repository.FlowNodeHistoryRepository;
import com.hdfc.flowengine.repository.FlowSessionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /sessions/{flowToken}/history - one session's node access history.
 * GET /session/{waId}/history - every session that customer has ever had, each with its own
 * history attached, most recently started first.
 */
@RestController
@RequiredArgsConstructor
public class SessionHistoryController {

	private final FlowSessionRepository flowSessionRepository;
	private final FlowNodeHistoryRepository flowNodeHistoryRepository;

	@GetMapping("/sessions/{flowToken}/history")
	public List<FlowHistoryView> sessionHistory(@PathVariable String flowToken) {
		return historyViews(flowToken);
	}

	@GetMapping("/session/{waId}/history")
	public List<SessionHistoryView> customerHistory(@PathVariable String waId) {
		List<FlowSession> sessions = flowSessionRepository.findByWaIdOrderByStartedAtDesc(waId);
		return sessions.stream()
				.map(session -> new SessionHistoryView(
						session.getFlowToken(),
						session.getWaId(),
						session.getStatus(),
						session.getStartedAt(),
						session.getLastInteractionAt(),
						session.getCompletedAt(),
						historyViews(session.getFlowToken())))
				.toList();
	}

	private List<FlowHistoryView> historyViews(String flowToken) {
		return flowNodeHistoryRepository.findByFlowTokenOrderByOccurredAtAsc(flowToken).stream()
				.map(this::toView)
				.toList();
	}

	private FlowHistoryView toView(FlowNodeHistory row) {
		return new FlowHistoryView(
				row.getScreenId(),
				row.getActionType(),
				row.getSelectedValue(),
				row.getRequestData(),
				row.getResponseData(),
				row.getOccurredAt());
	}
}
