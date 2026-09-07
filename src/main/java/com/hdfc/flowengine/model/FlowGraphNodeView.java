package com.hdfc.flowengine.model;

import com.hdfc.flowengine.entity.NodeType;

/** One screen in a flow's full definition graph. */
public record FlowGraphNodeView(
		String screenId,
		NodeType nodeType,
		String actionCode,
		String backTargetScreenId
) {
}
