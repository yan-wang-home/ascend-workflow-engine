package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.AgentLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AgentLogRepository extends ReactiveCrudRepository<AgentLog, UUID> {

    Flux<AgentLog> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);

    Mono<Long> countBySessionId(UUID sessionId);
}
