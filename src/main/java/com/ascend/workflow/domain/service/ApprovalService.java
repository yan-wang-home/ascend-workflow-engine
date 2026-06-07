package com.ascend.workflow.domain.service;

import com.ascend.workflow.api.dto.DecisionRequest;
import com.ascend.workflow.domain.model.*;
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
                instanceRepository.findPendingForDelegate(userId, limit, offset),
                instanceRepository.findPendingForEscalatedApprover(userId, limit, offset)
        ).distinct(WorkflowInstance::getId);
    }

    private record AuthResult(InstanceStep instanceStep, WorkflowStep workflowStep) {}

    public Mono<Decision> decide(UUID instanceId, UUID approverId, DecisionRequest request) {
        return instanceRepository.findById(instanceId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Request not found: " + instanceId)))
                .flatMap(instance -> {
                    if (instance.getStatus() != WorkflowInstanceStatus.PENDING
                            && instance.getStatus() != WorkflowInstanceStatus.CHANGES_REQUESTED) {
                        return Mono.error(new IllegalStateException("Request is not pending"));
                    }
                    return verifyApproverAuthorization(instance, approverId)
                            .flatMap(auth -> recordDecision(instance, auth.instanceStep(), auth.workflowStep(), approverId, request));
                });
    }

    private Mono<AuthResult> verifyApproverAuthorization(WorkflowInstance instance, UUID approverId) {
        return instanceStepRepository
                .findByInstanceIdAndStepOrderAndStatus(instance.getId(), instance.getCurrentStepOrder(), InstanceStepStatus.PENDING)
                .flatMap(instanceStep -> workflowStepRepository.findById(instanceStep.getStepId())
                        .flatMap(workflowStep -> {
                            Mono<Boolean> directAuth = isAuthorized(workflowStep, approverId, instance.getTemplateId());
                            Mono<Boolean> escalationAuth = instanceStepRepository.existsEscalatedToUser(
                                    instanceStep.getInstanceId(), instanceStep.getStepOrder(), approverId);
                            return Mono.zip(directAuth, escalationAuth)
                                    .filter(t -> t.getT1() || t.getT2())
                                    .map(t -> new AuthResult(instanceStep, workflowStep));
                        }))
                .next()
                .switchIfEmpty(Mono.error(new SecurityException("Not authorized to approve this step")));
    }

    private Mono<Boolean> isAuthorized(WorkflowStep step, UUID approverId, UUID templateId) {
        return switch (step.getApproverType()) {
            case USER -> {
                boolean isDirect = step.getApproverId().equals(approverId.toString());
                if (isDirect) yield Mono.just(true);
                yield delegationRepository
                        .findActiveDelegation(UUID.fromString(step.getApproverId()), templateId)
                        .map(d -> d.getDelegateId().equals(approverId))
                        .defaultIfEmpty(false);
            }
            case GROUP -> groupMemberRepository
                    .existsByGroupIdAndUserId(UUID.fromString(step.getApproverId()), approverId);
            case ROLE  -> Mono.just(true); // role already enforced by Spring Security
        };
    }

    private Mono<Decision> recordDecision(WorkflowInstance instance, InstanceStep instanceStep,
                                           WorkflowStep workflowStep, UUID approverId, DecisionRequest request) {
        // Guard: prevent a group member approving the same step twice in ALL_OF mode
        if (request.action() == DecisionAction.APPROVE
                && workflowStep.getApproverType() == ApproverType.GROUP
                && workflowStep.getApprovalMode() == ApprovalMode.ALL_OF) {
            return decisionRepository.existsByInstanceStepIdAndApproverId(instanceStep.getId(), approverId)
                    .flatMap(already -> already
                            ? Mono.error(new IllegalStateException("You have already approved this step"))
                            : doRecord(instance, instanceStep, workflowStep, approverId, request));
        }
        return doRecord(instance, instanceStep, workflowStep, approverId, request);
    }

    private Mono<Decision> doRecord(WorkflowInstance instance, InstanceStep instanceStep,
                                     WorkflowStep workflowStep, UUID approverId, DecisionRequest request) {
        Decision decision = Decision.builder()
                .instanceStepId(instanceStep.getId())
                .approverId(approverId)
                .action(request.action())
                .comment(request.comment())
                .decidedAt(OffsetDateTime.now())
                .build();

        return decisionRepository.save(decision)
                .flatMap(saved -> allMembersApproved(instanceStep, workflowStep, request.action())
                        .flatMap(shouldAdvance -> {
                            if (!shouldAdvance) return Mono.just(saved); // ALL_OF: waiting for remaining members
                            return advanceWorkflow(instance, instanceStep, request.action()).thenReturn(saved);
                        })
                        .flatMap(saved2 -> auditService.log(instance.getId(), approverId, "DECISION_MADE",
                                Map.of("action", request.action().name(), "stepOrder", instanceStep.getStepOrder(),
                                        "comment", request.comment() != null ? request.comment() : ""))
                                .thenReturn(saved2)));
    }

    // Returns true when the workflow should advance: always for non-ALL_OF, only when all group members approved for ALL_OF
    private Mono<Boolean> allMembersApproved(InstanceStep instanceStep, WorkflowStep workflowStep, DecisionAction action) {
        if (action != DecisionAction.APPROVE) return Mono.just(true);
        if (workflowStep.getApproverType() != ApproverType.GROUP) return Mono.just(true);
        if (workflowStep.getApprovalMode() != ApprovalMode.ALL_OF) return Mono.just(true);

        UUID groupId = UUID.fromString(workflowStep.getApproverId());
        return Mono.zip(
                groupMemberRepository.countByGroupId(groupId),
                decisionRepository.countApprovedByInstanceStepId(instanceStep.getId())
        ).map(t -> t.getT2() >= t.getT1());
    }

    private Mono<Void> advanceWorkflow(WorkflowInstance instance, InstanceStep currentStep, DecisionAction action) {
        return switch (action) {
            case REJECT -> closeInstance(instance, WorkflowInstanceStatus.REJECTED)
                    .then(markStepComplete(currentStep, InstanceStepStatus.REJECTED));

            case APPROVE -> markStepComplete(currentStep, InstanceStepStatus.APPROVED)
                    .then(checkParallelGroupComplete(instance, currentStep))
                    .flatMap(groupComplete -> {
                        if (!groupComplete) return Mono.empty();
                        return advanceToNextStep(instance);
                    });

            case REQUEST_CHANGES -> {
                instance.setStatus(WorkflowInstanceStatus.CHANGES_REQUESTED);
                instance.setUpdatedAt(OffsetDateTime.now());
                yield instanceRepository.save(instance).then();
            }
        };
    }

    private Mono<Boolean> checkParallelGroupComplete(WorkflowInstance instance, InstanceStep approvedStep) {
        if (approvedStep.getParallelGroup() == null) return Mono.just(true);

        return instanceStepRepository.findByInstanceIdAndStepOrder(instance.getId(), approvedStep.getStepOrder())
                .filter(s -> approvedStep.getParallelGroup().equals(s.getParallelGroup()))
                .all(s -> s.getId().equals(approvedStep.getId())
                        || s.getStatus() == InstanceStepStatus.APPROVED
                        || s.getStatus() == InstanceStepStatus.SKIPPED);
    }

    private Mono<Void> advanceToNextStep(WorkflowInstance instance) {
        int nextOrder = instance.getCurrentStepOrder() + 1;

        return instanceStepRepository.findByInstanceIdOrderByStepOrderAsc(instance.getId())
                .filter(s -> s.getStepOrder() == nextOrder && s.getStatus() == InstanceStepStatus.PENDING)
                .collectList()
                .flatMap(nextSteps -> {
                    if (nextSteps.isEmpty()) {
                        return closeInstance(instance, WorkflowInstanceStatus.APPROVED);
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

    private Mono<Void> closeInstance(WorkflowInstance instance, WorkflowInstanceStatus finalStatus) {
        instance.setStatus(finalStatus);
        instance.setUpdatedAt(OffsetDateTime.now());
        return instanceRepository.save(instance).then();
    }

    private Mono<Void> markStepComplete(InstanceStep step, InstanceStepStatus status) {
        escalationService.cancelEscalation(step.getId());
        step.setStatus(status);
        step.setCompletedAt(OffsetDateTime.now());
        return instanceStepRepository.save(step).then();
    }
}
