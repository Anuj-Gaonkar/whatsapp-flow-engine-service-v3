package com.hdfc.flowengine.repository;

import com.hdfc.flowengine.entity.FlowTransition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowTransitionRepository extends JpaRepository<FlowTransition, Long> {

	List<FlowTransition> findByFromNodeIdOrderByDisplayOrderAsc(Long fromNodeId);
}
