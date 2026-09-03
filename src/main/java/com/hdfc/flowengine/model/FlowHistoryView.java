package com.hdfc.flowengine.model;

import com.hdfc.flowengine.entity.HistoryActionType;
import java.time.Instant;
import java.util.Map;

/** REST-facing shape for one flow_node_history row. */
public record FlowHistoryView(
		String screenId,
		HistoryActionType actionType,
		String selectedValue,
		Map<String, Object> requestData,
		Map<String, Object> responseData,
		Instant occurredAt
) {
}
