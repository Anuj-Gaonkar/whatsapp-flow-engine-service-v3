package com.hdfc.flowengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "flow_node")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlowNode {

	@Id
	@Column(name = "node_id")
	private Long nodeId;

	@Column(name = "flow_code", nullable = false, length = 50)
	private String flowCode;

	@Column(name = "screen_id", nullable = false, unique = true, length = 100)
	private String screenId;

	@Enumerated(EnumType.STRING)
	@Column(name = "node_type", nullable = false, length = 20)
	private NodeType nodeType;

	@Column(name = "back_target_node_id")
	private Long backTargetNodeId;

	@Column(name = "action_code", length = 50)
	private String actionCode;
}
