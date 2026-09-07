package com.hdfc.flowengine.model;

import com.hdfc.flowengine.entity.HistoryActionType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One screen visited in an actual session (same ordering as FlowHistoryView), plus every option
 * that was available at that screen - not just the one selected - so a UI can render the road not
 * taken alongside the road actually walked.
 */
public record ConversationStepView(
		String screenId,
		HistoryActionType actionType,
		String selectedValue,
		List<ConversationOptionView> options,
		Map<String, Object> requestData,
		Map<String, Object> responseData,
		Instant occurredAt
) {
}
