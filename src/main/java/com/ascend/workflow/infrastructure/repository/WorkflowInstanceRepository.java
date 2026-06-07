package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.WorkflowInstance;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface WorkflowInstanceRepository extends ReactiveCrudRepository<WorkflowInstance, UUID> {

    Flux<WorkflowInstance> findByRequesterIdOrderByCreatedAtDesc(UUID requesterId, Pageable pageable);

    Mono<Long> countByRequesterId(UUID requesterId);

    Mono<Long> countByTemplateId(UUID templateId);

    // Instances where user is a direct approver on the current pending step
    @Query("""
        SELECT DISTINCT wi.* FROM workflow_instances wi
        JOIN instance_steps ist ON ist.instance_id = wi.id
        JOIN workflow_steps ws ON ws.id = ist.step_id
        WHERE wi.status = 'PENDING'
          AND ist.status = 'PENDING'
          AND ist.step_order = wi.current_step_order
          AND ws.approver_type = 'USER'
          AND ws.approver_id = CAST(:userId AS VARCHAR)
        ORDER BY wi.created_at ASC
        LIMIT :limit OFFSET :offset
        """)
    Flux<WorkflowInstance> findPendingForDirectApprover(UUID userId, int limit, int offset);

    // Instances where user is in an approver group on the current pending step
    @Query("""
        SELECT DISTINCT wi.* FROM workflow_instances wi
        JOIN instance_steps ist ON ist.instance_id = wi.id
        JOIN workflow_steps ws ON ws.id = ist.step_id
        JOIN group_members gm ON gm.group_id = CAST(ws.approver_id AS UUID)
        WHERE wi.status = 'PENDING'
          AND ist.status = 'PENDING'
          AND ist.step_order = wi.current_step_order
          AND ws.approver_type = 'GROUP'
          AND gm.user_id = :userId
        ORDER BY wi.created_at ASC
        LIMIT :limit OFFSET :offset
        """)
    Flux<WorkflowInstance> findPendingForGroupApprover(UUID userId, int limit, int offset);

    // Instances delegated to this user (active delegation)
    @Query("""
        SELECT DISTINCT wi.* FROM workflow_instances wi
        JOIN instance_steps ist ON ist.instance_id = wi.id
        JOIN workflow_steps ws ON ws.id = ist.step_id
        JOIN delegations d ON (d.template_id = wi.template_id OR d.template_id IS NULL)
        WHERE wi.status = 'PENDING'
          AND ist.status = 'PENDING'
          AND ist.step_order = wi.current_step_order
          AND ws.approver_type = 'USER'
          AND ws.approver_id = CAST(d.delegator_id AS VARCHAR)
          AND d.delegate_id = :userId
          AND d.is_active = TRUE
          AND d.starts_at <= NOW()
          AND d.ends_at >= NOW()
        ORDER BY wi.created_at ASC
        LIMIT :limit OFFSET :offset
        """)
    Flux<WorkflowInstance> findPendingForDelegate(UUID userId, int limit, int offset);

    // Instances escalated to this user — a PENDING step exists where a sibling ESCALATED step
    // at the same (instance, step_order) has escalated_to_user_id = this user
    @Query("""
        SELECT DISTINCT wi.* FROM workflow_instances wi
        JOIN instance_steps ist ON ist.instance_id = wi.id
        WHERE wi.status = 'PENDING'
          AND ist.status = 'PENDING'
          AND ist.step_order = wi.current_step_order
          AND EXISTS (
              SELECT 1 FROM instance_steps esc
              WHERE esc.instance_id = ist.instance_id
                AND esc.step_order = ist.step_order
                AND esc.status = 'ESCALATED'
                AND esc.escalated_to_user_id = :userId
          )
        ORDER BY wi.created_at ASC
        LIMIT :limit OFFSET :offset
        """)
    Flux<WorkflowInstance> findPendingForEscalatedApprover(UUID userId, int limit, int offset);
}
