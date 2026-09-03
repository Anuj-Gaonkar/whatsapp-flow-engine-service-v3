package com.hdfc.flowengine.service;

import com.hdfc.flowengine.config.FlowRegistryProperties;
import com.hdfc.flowengine.config.FlowRegistryProperties.FlowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * flow_key -> config lookup (entry screen, Meta Flow ID, draft/published mode), backed entirely
 * by the {@code flows.*} block in application.yaml. Registering a new WhatsApp Flow (e.g.
 * INACTIVE_SALARY alongside AMB_REMINDER) means adding one block to that config plus that flow's
 * screens/transitions in the database - no code change, no new controller method. flow_key is
 * the same string stored in flow_node.flow_code for that flow's screens.
 */
@Component
@RequiredArgsConstructor
public class FlowRegistry {

	private final FlowRegistryProperties properties;

	public String defaultFlowKey() {
		return properties.defaultFlowKey();
	}

	public String entryScreenId(String flowKey) {
		return definition(flowKey).entryScreenId();
	}

	public String metaFlowId(String flowKey) {
		return definition(flowKey).metaFlowId();
	}

	public String mode(String flowKey) {
		String mode = definition(flowKey).mode();
		return (mode == null || mode.isBlank()) ? "published" : mode;
	}

	public String ctaLabel(String flowKey) {
		String label = definition(flowKey).ctaLabel();
		return (label == null || label.isBlank()) ? "Continue" : label;
	}

	public String bodyText(String flowKey) {
		String text = definition(flowKey).bodyText();
		return (text == null || text.isBlank()) ? "Please review the details below" : text;
	}

	public boolean isRegistered(String flowKey) {
		return properties.definitions() != null && properties.definitions().containsKey(flowKey);
	}

	private FlowDefinition definition(String flowKey) {
		FlowDefinition definition = properties.definitions() == null ? null : properties.definitions().get(flowKey);
		if (definition == null) {
			throw new IllegalArgumentException("Unknown flow key: " + flowKey);
		}
		return definition;
	}
}
