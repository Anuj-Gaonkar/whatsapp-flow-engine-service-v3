package com.hdfc.flowengine.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code flows.*} block in application.yaml - the list of WhatsApp Flows this service
 * knows how to trigger and drive. Each entry is keyed by a flow key (e.g. "AMB_REMINDER",
 * "INACTIVE_SALARY") which is also the value stored in flow_node.flow_code for that flow's
 * screens. Registering a new flow is purely config: add a block here (plus its screens/
 * transitions in the database), no code change needed.
 */
@ConfigurationProperties(prefix = "flows")
public record FlowRegistryProperties(
		String defaultFlowKey,
		Map<String, FlowDefinition> definitions
) {

	public record FlowDefinition(
			String entryScreenId,
			String metaFlowId,
			String mode,
			String ctaLabel,
			String bodyText
	) {
	}
}
