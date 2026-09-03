package com.hdfc.flowengine.repository;

import com.hdfc.flowengine.entity.FlowNodeHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowNodeHistoryRepository extends JpaRepository<FlowNodeHistory, Long> {

	List<FlowNodeHistory> findByFlowTokenOrderByOccurredAtAsc(String flowToken);
}
