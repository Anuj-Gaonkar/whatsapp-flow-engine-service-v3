package com.hdfc.flowengine.model;

import com.hdfc.flowengine.entity.FlowSessionStatus;
import java.time.Instant;
import java.util.List;

/** One WhatsApp Flow instance for a customer, with its full interaction history attached. */
public record SessionHistoryView(
		String flowToken,
		String waId,
		FlowSessionStatus status,
		Instant startedAt,
		Instant lastInteractionAt,
		Instant completedAt,
		List<FlowHistoryView> history
) {
}
