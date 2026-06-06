package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.Delegation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface DelegationRepository extends ReactiveCrudRepository<Delegation, UUID> {

    Flux<Delegation> findByDelegatorIdOrderByCreatedAtDesc(UUID delegatorId, Pageable pageable);

    Mono<Long> countByDelegatorId(UUID delegatorId);

    // Active delegation from delegator to any delegate, optionally scoped to a template
    @Query("""
        SELECT * FROM delegations
        WHERE delegator_id = :delegatorId
          AND is_active = TRUE
          AND starts_at <= NOW()
          AND ends_at >= NOW()
          AND (template_id = :templateId OR template_id IS NULL)
        LIMIT 1
        """)
    Mono<Delegation> findActiveDelegation(UUID delegatorId, UUID templateId);
}
