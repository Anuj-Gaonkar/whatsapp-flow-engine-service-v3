package com.hdfc.flowengine.model;

import java.util.List;

/**
 * One registered flow's whole possible-paths graph - every screen and every outgoing option
 * reachable from it, regardless of whether any customer ever walked that path. For a
 * flowchart-style UI, as opposed to ConversationStepView's one-customer's-actual-path view.
 */
public record FlowGraphView(
		String flowKey,
		String entryScreenId,
		List<FlowGraphNodeView> nodes,
		List<FlowGraphEdgeView> edges
) {
}
