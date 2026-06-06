package com.ascend.workflow.domain.service;

import com.ascend.workflow.api.dto.DecisionRequest;
import com.ascend.workflow.domain.model.Decision;
import com.ascend.workflow.domain.model.InstanceStep;
import com.ascend.workflow.domain.model.WorkflowInstance;
import com.ascend.workflow.domain.model.WorkflowStep;
import com.ascend.workflow.infrastructure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final WorkflowInstanceRepository instanceRepository;
    private final InstanceStepRepository instanceStepRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final DecisionRepository decisionRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final DelegationRepository delegationRepository;
    private final AuditService auditService;
    private final EscalationService escalationService;

    public Flux<WorkflowInstance> getInbox(UUID userId, Pageable pageable) {
        int limit = pageable.getPageSize();
        int offset = (int) pageable.getOffset();

        return Flux.merge(
                instanceRepository.findPendingForDirectApprover(userId, limit, offset),
                instanceRepository.findPendingForGroupApprover(userId, limit, offset),
                instanceRepository.findPendingForDelegate(userId, limit, offset)
        ).distinct(WorkflowInstance::getId);
    }

    public Mono<Decision> decide(UUID instanceId, UUID approverId, DecisionRequest request) {
        return instanceRepository.findById(instanceId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Request not found: " + instanceId)))
                .flatMap(instance -> {
                    if (!instance.getStatus().equals("PENDING")) {
                        return Mono.error(new IllegalStateException("Request is not pending"));
                    }
                    return verifyApproverAuthorization(instance, approverId)
                            .flatMap(step -> recordDecision(instance, step, approverId, request));
                });
    }

    private Mono<InstanceStep> verifyApproverAuthorization(WorkflowInstance instance, UUID approverId) {
        return instanceStepRepository
                .findByInstanceIdAndStepOrderAndStatus(instance.getId(), instance.getCurrentStepOrder(), "PENDING")
                .switchIfEmpty(Mono.error(new IllegalStateException("No pending step found")))
                .flatMap(instanceStep -> workflowStepRepository.findById(instanceStep.getStepId())
                        .flatMap(workflowStep -> isAuthorized(workflowStep, approverId, instance.getTemplateId())
                                .flatMap(authorized -> {
                                    if (!authorized) {
                                        return Mono.error(new SecurityException("Not authorized to approve this step"));
                                    }
                                    return Mono.just(instanceStep);
                                })));
    }

    private Mono<Boolean> isAuthorized(WorkflowStep step, UUID approverId, UUID templateId) {
        return switch (step.getApproverType()) {
            case "USER" -> {
                boolean isDirect = step.getApproverId().equals(approverId.toString());
                if (isDirect) yield Mono.just(true);
                // Check if approverId is a delegate for the direct approver
                yield delegationRepository
                        .findActiveDelegation(UUID.fromString(step.getApproverId()), templateId)
                        .map(d -> d.getDelegateId().equals(approverId))
                        .defaultIfEmpty(false);
            }
            case "GROUP" -> groupMemberRepository
                    .existsByGroupIdAndUserId(UUID.fromString(step.getApproverId()), approverId);
            case "ROLE"  -> Mono.just(true); // role already enforced by Spring Security
            default      -> Mono.just(false);
        };
    }

    private Mono<Decision> recordDecision(WorkflowInstance instance, InstanceStep instanceStep,
                                           UUID approverId, DecisionRequest request) {
        Decision decision = Decision.builder()
                .instanceStepId(instanceStep.getId())
                .approverId(approverId)
                .action(request.action())
                .comment(request.comment())
                .decidedAt(OffsetDateTime.now())
                .build();

        return decisionRepository.save(decision)
                .flatMap(saved -> advanceWorkflow(instance, instanceStep, request.action())
                        .then(auditService.log(instance.getId(), approverId, "DECISION_MADE",
                                Map.of("action", request.action(), "stepOrder", instanceStep.getStepOrder(),
                                        "comment", request.comment() != null ? request.comment() : "")))
                        .thenReturn(saved));
    }

    private Mono<Void> advanceWorkflow(WorkflowInstance instance, InstanceStep currentStep, String action) {
        return switch (action) {
            case "REJECT" -> closeInstance(instance, "REJECTED")
                    .then(markStepComplete(currentStep, "REJECTED"));

            case "APPROVE" -> markStepComplete(currentStep, "APPROVED")
                    .then(checkParallelGroupComplete(instance, currentStep))
                    .flatMap(groupComplete -> {
                        if (!groupComplete) return Mono.empty();
                        return advanceToNextStep(instance);
                    });

            default -> Mono.empty(); // REQUEST_CHANGES — leave step pending for re-review
        };
    }

    private Mono<Boolean> checkParallelGroupComplete(WorkflowInstance instance, InstanceStep approvedStep) {
        if (approvedStep.getParallelGroup() == null) return Mono.just(true);

        return instanceStepRepository.findByInstanceIdAndStepOrder(instance.getId(), approvedStep.getStepOrder())
                .filter(s -> approvedStep.getParallelGroup().equals(s.getParallelGroup()))
                .all(s -> s.getId().equals(approvedStep.getId()) || s.getStatus().equals("APPROVED") || s.getStatus().equals("SKIPPED"));
    }

    private Mono<Void> advanceToNextStep(WorkflowInstance instance) {
        int nextOrder = instance.getCurrentStepOrder() + 1;

        return instanceStepRepository.findByInstanceIdOrderByStepOrderAsc(instance.getId())
                .filter(s -> s.getStepOrder() == nextOrder && s.getStatus().equals("PENDING"))
                .collectList()
                .flatMap(nextSteps -> {
                    if (nextSteps.isEmpty()) {
                        return closeInstance(instance, "APPROVED");
                    }
                    instance.setCurrentStepOrder(nextOrder);
                    instance.setUpdatedAt(OffsetDateTime.now());
                    return instanceRepository.save(instance)
                            .then(Flux.fromIterable(nextSteps)
                                    .flatMap(step -> {
                                        step.setStartedAt(OffsetDateTime.now());
                                        return instanceStepRepository.save(step)
                                                .flatMap(saved -> workflowStepRepository.findById(saved.getStepId())
                                                        .doOnNext(ws -> escalationService.scheduleEscalation(saved, ws))
                                                        .thenReturn(saved));
                                    }).then());
                });
    }

    private Mono<Void> closeInstance(WorkflowInstance instance, String finalStatus) {
        instance.setStatus(finalStatus);
        instance.setUpdatedAt(OffsetDateTime.now());
        return instanceRepository.save(instance).then();
    }

    private Mono<Void> markStepComplete(InstanceStep step, String status) {
        escalationService.cancelEscalation(step.getId());
        step.setStatus(status);
        step.setCompletedAt(OffsetDateTime.now());
        return instanceStepRepository.save(step).then();
    }
}
