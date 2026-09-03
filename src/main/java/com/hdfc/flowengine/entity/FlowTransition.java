package com.hdfc.flowengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "flow_transition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlowTransition {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "transition_id")
	private Long transitionId;

	@Column(name = "from_node_id", nullable = false)
	private Long fromNodeId;

	@Column(name = "option_value", length = 100)
	private String optionValue;

	@Column(name = "option_label")
	private String optionLabel;

	@Column(name = "to_node_id", nullable = false)
	private Long toNodeId;

	@Column(name = "display_order", nullable = false)
	private Integer displayOrder;
}
