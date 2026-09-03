package com.hdfc.flowengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_node_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlowNodeHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "flow_token", nullable = false, length = 64)
	private String flowToken;

	@Column(name = "customer_id", nullable = false, length = 32)
	private String customerId;

	@Column(name = "node_id", nullable = false)
	private Long nodeId;

	@Column(name = "screen_id", nullable = false, length = 100)
	private String screenId;

	@Enumerated(EnumType.STRING)
	@Column(name = "action_type", nullable = false, length = 20)
	private HistoryActionType actionType;

	@Column(name = "selected_value", length = 100)
	private String selectedValue;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "request_data", columnDefinition = "jsonb")
	private Map<String, Object> requestData;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "response_data", columnDefinition = "jsonb")
	private Map<String, Object> responseData;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;
}
