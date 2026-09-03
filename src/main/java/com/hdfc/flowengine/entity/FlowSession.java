package com.hdfc.flowengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlowSession {

	@Id
	@Column(name = "flow_token", length = 64)
	private String flowToken;

	@Column(name = "wa_id", nullable = false, length = 32)
	private String waId;

	@Column(name = "current_node_id", nullable = false)
	private Long currentNodeId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private FlowSessionStatus status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "context", columnDefinition = "jsonb", nullable = false)
	@Builder.Default
	private Map<String, Object> context = new LinkedHashMap<>();

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "last_interaction_at", nullable = false)
	private Instant lastInteractionAt;

	@Column(name = "completed_at")
	private Instant completedAt;
}
