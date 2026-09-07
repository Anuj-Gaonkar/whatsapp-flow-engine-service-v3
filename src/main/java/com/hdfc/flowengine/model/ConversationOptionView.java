package com.hdfc.flowengine.model;

/**
 * One option that was on offer at a conversation step - every outgoing FlowTransition from that
 * screen, not just the one the customer picked. {@code chosen} is true for the option whose
 * optionValue matches the step's selectedValue (both null for a screen with a single,
 * unconditional transition).
 */
public record ConversationOptionView(
		String optionValue,
		String optionLabel,
		String targetScreenId,
		boolean chosen
) {
}
