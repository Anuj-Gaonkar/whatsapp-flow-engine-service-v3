package com.hdfc.flowengine.repository;

import com.hdfc.flowengine.entity.FlowSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowSessionRepository extends JpaRepository<FlowSession, String> {

	Optional<FlowSession> findFirstByWaIdOrderByStartedAtDesc(String waId);

	List<FlowSession> findByWaIdOrderByStartedAtDesc(String waId);
}
