package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.AgentSession;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AgentSessionRepository extends ReactiveCrudRepository<AgentSession, UUID> {

    Mono<AgentSession> findByUserId(UUID userId);
}
