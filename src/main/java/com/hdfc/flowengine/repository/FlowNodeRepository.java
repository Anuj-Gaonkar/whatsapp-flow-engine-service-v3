package com.hdfc.flowengine.repository;

import com.hdfc.flowengine.entity.FlowNode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowNodeRepository extends JpaRepository<FlowNode, Long> {

	Optional<FlowNode> findByScreenId(String screenId);
}
