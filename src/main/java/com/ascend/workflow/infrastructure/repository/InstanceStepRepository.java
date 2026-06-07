package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.InstanceStep;
import com.ascend.workflow.domain.model.InstanceStepStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface InstanceStepRepository extends ReactiveCrudRepository<InstanceStep, UUID> {

    Flux<InstanceStep> findByInstanceIdOrderByStepOrderAsc(UUID instanceId);

    Flux<InstanceStep> findByInstanceIdAndStepOrder(UUID instanceId, int stepOrder);

    Flux<InstanceStep> findByInstanceIdAndStepOrderAndStatus(UUID instanceId, int stepOrder, InstanceStepStatus status);

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM instance_steps
            WHERE instance_id = :instanceId
              AND step_order = :stepOrder
              AND status = 'ESCALATED'
              AND escalated_to_user_id = :userId
        )
        """)
    Mono<Boolean> existsEscalatedToUser(UUID instanceId, int stepOrder, UUID userId);

    // Used by escalation scheduler: PENDING steps past their timeout with no escalation yet
    @Query("""
        SELECT ist.* FROM instance_steps ist
        JOIN workflow_steps ws ON ws.id = ist.step_id
        WHERE ist.status = 'PENDING'
          AND ist.escalated_at IS NULL
          AND ws.timeout_hours IS NOT NULL
          AND ws.escalation_user_id IS NOT NULL
          AND ist.started_at + (ws.timeout_hours || ' hours')::INTERVAL < NOW()
        """)
    Flux<InstanceStep> findEscalationCandidates();
}
