package com.hdfc.flowengine.model;

/** One outgoing option (FlowTransition) in a flow's full definition graph. */
public record FlowGraphEdgeView(
		String fromScreenId,
		String toScreenId,
		String optionValue,
		String optionLabel,
		int displayOrder
) {
}
