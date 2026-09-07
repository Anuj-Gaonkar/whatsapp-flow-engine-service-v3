package com.hdfc.flowengine.repository;

import com.hdfc.flowengine.entity.FlowTransition;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowTransitionRepository extends JpaRepository<FlowTransition, Long> {

	List<FlowTransition> findByFromNodeIdOrderByDisplayOrderAsc(Long fromNodeId);

	/** Batch form of the above - every outgoing option for a whole set of nodes in one query. */
	List<FlowTransition> findByFromNodeIdIn(Collection<Long> fromNodeIds);
}
