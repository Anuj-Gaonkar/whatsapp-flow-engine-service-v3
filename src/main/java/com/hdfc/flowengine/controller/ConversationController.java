package com.hdfc.flowengine.controller;

import com.hdfc.flowengine.entity.FlowNode;
import com.hdfc.flowengine.entity.FlowNodeHistory;
import com.hdfc.flowengine.entity.FlowTransition;
import com.hdfc.flowengine.model.ConversationOptionView;
import com.hdfc.flowengine.model.ConversationStepView;
import com.hdfc.flowengine.model.FlowGraphEdgeView;
import com.hdfc.flowengine.model.FlowGraphNodeView;
import com.hdfc.flowengine.model.FlowGraphView;
import com.hdfc.flowengine.repository.FlowNodeHistoryRepository;
import com.hdfc.flowengine.repository.FlowNodeRepository;
import com.hdfc.flowengine.repository.FlowTransitionRepository;
import com.hdfc.flowengine.service.FlowRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * UI-rendering endpoints that go beyond SessionHistoryController's raw log by resolving every
 * option that was (or could have been) on offer, not just the one a customer picked:
 * <p>
 * GET /sessions/{flowToken}/conversation - one session's actual path, each screen paired with
 * every option that was available there (the road not taken alongside the road walked).
 * <p>
 * GET /flows/{flowKey}/graph - a whole registered flow's definition: every screen and every
 * outgoing option reachable from its entry screen, independent of any one customer's session.
 */
@RestController
@RequiredArgsConstructor
public class ConversationController {

	private final FlowNodeHistoryRepository flowNodeHistoryRepository;
	private final FlowNodeRepository flowNodeRepository;
	private final FlowTransitionRepository flowTransitionRepository;
	private final FlowRegistry flowRegistry;

	@GetMapping("/sessions/{flowToken}/conversation")
	public List<ConversationStepView> conversation(@PathVariable String flowToken) {
		List<FlowNodeHistory> history = flowNodeHistoryRepository.findByFlowTokenOrderByOccurredAtAsc(flowToken);
		if (history.isEmpty()) {
			return List.of();
		}

		List<Long> visitedNodeIds = history.stream().map(FlowNodeHistory::getNodeId).distinct().toList();
		List<FlowTransition> outgoing = flowTransitionRepository.findByFromNodeIdIn(visitedNodeIds);

		Map<Long, String> screenIdByNodeId = screenIdByNodeId(outgoing.stream()
				.map(FlowTransition::getToNodeId)
				.distinct()
				.toList());

		Map<Long, List<FlowTransition>> optionsByFromNodeId = new LinkedHashMap<>();
		outgoing.stream()
				.sorted(Comparator.comparing(FlowTransition::getDisplayOrder))
				.forEach(t -> optionsByFromNodeId.computeIfAbsent(t.getFromNodeId(), k -> new ArrayList<>()).add(t));

		return history.stream()
				.map(row -> toConversationStep(row, optionsByFromNodeId.getOrDefault(row.getNodeId(), List.of()), screenIdByNodeId))
				.toList();
	}

	@GetMapping("/flows/{flowKey}/graph")
	public ResponseEntity<?> flowGraph(@PathVariable String flowKey) {
		if (!flowRegistry.isRegistered(flowKey)) {
			return ResponseEntity.badRequest().body(Map.of("error", "Unknown flow key: " + flowKey));
		}

		List<FlowNode> nodes = flowNodeRepository.findByFlowCode(flowKey);
		Map<Long, String> screenIdByNodeId = nodes.stream()
				.collect(Collectors.toMap(FlowNode::getNodeId, FlowNode::getScreenId));

		List<FlowGraphNodeView> nodeViews = nodes.stream()
				.map(n -> new FlowGraphNodeView(
						n.getScreenId(),
						n.getNodeType(),
						n.getActionCode(),
						n.getBackTargetNodeId() == null ? null : screenIdByNodeId.get(n.getBackTargetNodeId())))
				.toList();

		List<Long> nodeIds = nodes.stream().map(FlowNode::getNodeId).toList();
		List<FlowGraphEdgeView> edgeViews = flowTransitionRepository.findByFromNodeIdIn(nodeIds).stream()
				.sorted(Comparator.comparing(FlowTransition::getFromNodeId).thenComparing(FlowTransition::getDisplayOrder))
				.map(t -> new FlowGraphEdgeView(
						screenIdByNodeId.get(t.getFromNodeId()),
						screenIdByNodeId.getOrDefault(t.getToNodeId(), "UNKNOWN_NODE_" + t.getToNodeId()),
						t.getOptionValue(),
						t.getOptionLabel(),
						t.getDisplayOrder()))
				.toList();

		return ResponseEntity.ok(new FlowGraphView(flowKey, flowRegistry.entryScreenId(flowKey), nodeViews, edgeViews));
	}

	private ConversationStepView toConversationStep(FlowNodeHistory row, List<FlowTransition> options,
			Map<Long, String> screenIdByNodeId) {
		List<ConversationOptionView> optionViews = options.stream()
				.map(t -> new ConversationOptionView(
						t.getOptionValue(),
						t.getOptionLabel(),
						screenIdByNodeId.get(t.getToNodeId()),
						Objects.equals(t.getOptionValue(), row.getSelectedValue())))
				.toList();

		return new ConversationStepView(
				row.getScreenId(),
				row.getActionType(),
				row.getSelectedValue(),
				optionViews,
				row.getRequestData(),
				row.getResponseData(),
				row.getOccurredAt());
	}

	private Map<Long, String> screenIdByNodeId(List<Long> nodeIds) {
		if (nodeIds.isEmpty()) {
			return Map.of();
		}
		return flowNodeRepository.findAllById(nodeIds).stream()
				.collect(Collectors.toMap(FlowNode::getNodeId, FlowNode::getScreenId));
	}
}
