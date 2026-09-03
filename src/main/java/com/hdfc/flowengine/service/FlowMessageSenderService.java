package com.hdfc.flowengine.service;

import com.hdfc.flowengine.entity.FlowSession;
import com.hdfc.flowengine.entity.FlowSessionStatus;
import com.hdfc.flowengine.repository.FlowNodeRepository;
import com.hdfc.flowengine.repository.FlowSessionRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints a flow_token, creates the flow_session row (OPENED - Meta hasn't called INIT yet), and
 * sends the Flow message. Called from POST /trigger. Once Meta's INIT call arrives,
 * FlowEngineService.init() looks this row up by flow_token, trusts its current_node_id (set
 * below), and moves it to IN_PROGRESS; if no trigger call happened first (e.g. Meta's own Flow
 * preview tool), it falls back to creating one there instead.
 */
@Service
@RequiredArgsConstructor
public class FlowMessageSenderService {

	private final WhatsAppClient whatsAppClient;
	private final FlowRegistry flowRegistry;
	private final FlowSessionRepository flowSessionRepository;
	private final FlowNodeRepository flowNodeRepository;

	@Transactional
	public SendResult send(String flowKey, String waId) {
		String entryScreenId = flowRegistry.entryScreenId(flowKey);
		String metaFlowId = flowRegistry.metaFlowId(flowKey);
		String mode = flowRegistry.mode(flowKey);
		String flowToken = UUID.randomUUID().toString();
		Instant now = Instant.now();

		Long entryNodeId = flowNodeRepository.findByScreenId(entryScreenId)
				.orElseThrow(() -> new IllegalStateException("Entry screen " + entryScreenId + " is not seeded"))
				.getNodeId();

		flowSessionRepository.save(FlowSession.builder()
				.flowToken(flowToken)
				.waId(waId)
				.currentNodeId(entryNodeId)
				.status(FlowSessionStatus.OPENED)
				.startedAt(now)
				.lastInteractionAt(now)
				.build());

		Map<String, Object> metaResponse = whatsAppClient.sendFlow(waId, flowToken, metaFlowId, entryScreenId, mode,
				flowRegistry.ctaLabel(flowKey), flowRegistry.bodyText(flowKey));
		return new SendResult(flowToken, metaResponse);
	}

	public record SendResult(String flowToken, Map<String, Object> metaResponse) {
	}
}
