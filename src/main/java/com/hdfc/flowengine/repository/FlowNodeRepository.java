package com.hdfc.flowengine.repository;

import com.hdfc.flowengine.entity.FlowNode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowNodeRepository extends JpaRepository<FlowNode, Long> {

	Optional<FlowNode> findByScreenId(String screenId);

	/** Every screen registered under one flow_key - flow_code is a plain label (see FlowRegistry). */
	List<FlowNode> findByFlowCode(String flowCode);
}
