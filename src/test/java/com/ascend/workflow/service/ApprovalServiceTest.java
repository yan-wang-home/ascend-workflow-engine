package com.ascend.workflow.service;

import com.ascend.workflow.api.dto.DecisionRequest;
import com.ascend.workflow.domain.model.*;
import com.ascend.workflow.domain.service.ApprovalService;
import com.ascend.workflow.domain.service.AuditService;
import com.ascend.workflow.domain.service.EscalationService;
import com.ascend.workflow.domain.service.ResourceNotFoundException;
import com.ascend.workflow.infrastructure.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock WorkflowInstanceRepository instanceRepository;
    @Mock InstanceStepRepository instanceStepRepository;
    @Mock WorkflowStepRepository workflowStepRepository;
    @Mock DecisionRepository decisionRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock DelegationRepository delegationRepository;
    @Mock AuditService auditService;
    @Mock EscalationService escalationService;

    @InjectMocks ApprovalService approvalService;

    private UUID instanceId;
    private UUID approverId;
    private UUID stepId;
    private UUID templateId;

    private WorkflowInstance pendingInstance;
    private InstanceStep instanceStep;
    private WorkflowStep workflowStep;

    @BeforeEach
    void setUp() {
        instanceId  = UUID.randomUUID();
        approverId  = UUID.randomUUID();
        stepId      = UUID.randomUUID();
        templateId  = UUID.randomUUID();

        pendingInstance = WorkflowInstance.builder()
                .id(instanceId)
                .templateId(templateId)
                .status(WorkflowInstanceStatus.PENDING)
                .currentStepOrder(1)
                .build();

        instanceStep = InstanceStep.builder()
                .id(UUID.randomUUID())
                .instanceId(instanceId)
                .stepId(stepId)
                .stepOrder(1)
                .status(InstanceStepStatus.PENDING)
                .build();

        workflowStep = WorkflowStep.builder()
                .id(stepId)
                .templateId(templateId)
                .approverType(ApproverType.USER)
                .approverId(approverId.toString())
                .stepOrder(1)
                .build();
    }

    // ── getInbox ──────────────────────────────────────────────────────────────

    @Test
    void getInbox_deduplicatesResultsFromMultipleSources() {
        UUID userId = UUID.randomUUID();
        WorkflowInstance i1 = WorkflowInstance.builder().id(UUID.randomUUID()).build();
        WorkflowInstance i2 = WorkflowInstance.builder().id(UUID.randomUUID()).build();

        when(instanceRepository.findPendingForDirectApprover(eq(userId), anyInt(), anyInt()))
                .thenReturn(Flux.just(i1, i2));
        when(instanceRepository.findPendingForGroupApprover(eq(userId), anyInt(), anyInt()))
                .thenReturn(Flux.just(i2)); // duplicate of i2
        when(instanceRepository.findPendingForDelegate(eq(userId), anyInt(), anyInt()))
                .thenReturn(Flux.empty());
        when(instanceRepository.findPendingForEscalatedApprover(eq(userId), anyInt(), anyInt()))
                .thenReturn(Flux.empty());

        StepVerifier.create(approvalService.getInbox(userId, PageRequest.of(0, 10)))
                .expectNextCount(2)
                .verifyComplete();
    }

    // ── decide — error cases ──────────────────────────────────────────────────

    @Test
    void decide_instanceNotFound_throwsResourceNotFoundException() {
        when(instanceRepository.findById(instanceId)).thenReturn(Mono.empty());

        StepVerifier.create(approvalService.decide(instanceId, approverId, approveRequest()))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    void decide_instanceNotPending_throwsIllegalStateException() {
        pendingInstance.setStatus(WorkflowInstanceStatus.APPROVED);
        when(instanceRepository.findById(instanceId)).thenReturn(Mono.just(pendingInstance));

        StepVerifier.create(approvalService.decide(instanceId, approverId, approveRequest()))
                .expectError(IllegalStateException.class)
                .verify();
    }

    // ── decide — REJECT ───────────────────────────────────────────────────────

    @Test
    void decide_reject_closesInstanceAsRejected() {
        setupAuthorization();
        when(decisionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceStepRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditService.log(any(), any(), any(), any())).thenReturn(Mono.just(AuditTrail.builder().build()));

        StepVerifier.create(approvalService.decide(instanceId, approverId,
                        new DecisionRequest(DecisionAction.REJECT, "not approved")))
                .expectNextCount(1)
                .verifyComplete();

        verify(instanceRepository).save(argThat(i -> i.getStatus() == WorkflowInstanceStatus.REJECTED));
    }

    // ── decide — REQUEST_CHANGES ──────────────────────────────────────────────

    @Test
    void decide_requestChanges_setsChangesRequestedStatus() {
        setupAuthorization();
        when(decisionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditService.log(any(), any(), any(), any())).thenReturn(Mono.just(AuditTrail.builder().build()));

        StepVerifier.create(approvalService.decide(instanceId, approverId,
                        new DecisionRequest(DecisionAction.REQUEST_CHANGES, "please revise")))
                .expectNextCount(1)
                .verifyComplete();

        verify(instanceRepository).save(argThat(i -> i.getStatus() == WorkflowInstanceStatus.CHANGES_REQUESTED));
    }

    // ── decide — APPROVE, sequential ─────────────────────────────────────────

    @Test
    void decide_approve_noMoreSteps_closesAsApproved() {
        setupAuthorization();
        when(decisionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceStepRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // Only step 1 exists — no step_order=2 PENDING steps
        when(instanceStepRepository.findByInstanceIdOrderByStepOrderAsc(instanceId))
                .thenReturn(Flux.just(instanceStep));
        when(instanceRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditService.log(any(), any(), any(), any())).thenReturn(Mono.just(AuditTrail.builder().build()));

        StepVerifier.create(approvalService.decide(instanceId, approverId, approveRequest()))
                .expectNextCount(1)
                .verifyComplete();

        verify(instanceRepository).save(argThat(i -> i.getStatus() == WorkflowInstanceStatus.APPROVED));
    }

    @Test
    void decide_approve_moreStepsExist_advancesToNextStepOrder() {
        UUID nextStepId = UUID.randomUUID();
        InstanceStep nextStep = InstanceStep.builder()
                .id(UUID.randomUUID())
                .instanceId(instanceId)
                .stepId(nextStepId)
                .stepOrder(2)
                .status(InstanceStepStatus.PENDING)
                .build();
        WorkflowStep nextWorkflowStep = WorkflowStep.builder()
                .id(nextStepId)
                .stepOrder(2)
                .build();

        setupAuthorization();
        when(decisionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceStepRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceStepRepository.findByInstanceIdOrderByStepOrderAsc(instanceId))
                .thenReturn(Flux.just(instanceStep, nextStep));
        when(instanceRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(workflowStepRepository.findById(nextStepId)).thenReturn(Mono.just(nextWorkflowStep));
        when(auditService.log(any(), any(), any(), any())).thenReturn(Mono.just(AuditTrail.builder().build()));

        StepVerifier.create(approvalService.decide(instanceId, approverId, approveRequest()))
                .expectNextCount(1)
                .verifyComplete();

        // Instance advances to step_order=2, not closed
        verify(instanceRepository).save(argThat(i -> i.getCurrentStepOrder() == 2
                && i.getStatus() == WorkflowInstanceStatus.PENDING));
    }

    // ── decide — APPROVE, parallel group ─────────────────────────────────────

    @Test
    void decide_approve_parallelSiblingStillPending_doesNotAdvance() {
        instanceStep.setParallelGroup(1);
        workflowStep = WorkflowStep.builder()
                .id(stepId)
                .approverType(ApproverType.USER)
                .approverId(approverId.toString())
                .stepOrder(1)
                .parallelGroup(1)
                .build();

        InstanceStep sibling = InstanceStep.builder()
                .id(UUID.randomUUID())
                .instanceId(instanceId)
                .stepOrder(1)
                .parallelGroup(1)
                .status(InstanceStepStatus.PENDING) // still waiting
                .build();

        setupAuthorization();
        when(decisionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceStepRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // Parallel check: returns both steps — sibling is still PENDING
        when(instanceStepRepository.findByInstanceIdAndStepOrder(instanceId, 1))
                .thenReturn(Flux.just(instanceStep, sibling));
        when(auditService.log(any(), any(), any(), any())).thenReturn(Mono.just(AuditTrail.builder().build()));

        StepVerifier.create(approvalService.decide(instanceId, approverId, approveRequest()))
                .expectNextCount(1)
                .verifyComplete();

        // Instance must NOT be saved (no advance, no close)
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void decide_approve_parallelGroupComplete_advances() {
        instanceStep.setParallelGroup(1);
        workflowStep = WorkflowStep.builder()
                .id(stepId)
                .approverType(ApproverType.USER)
                .approverId(approverId.toString())
                .stepOrder(1)
                .parallelGroup(1)
                .build();

        InstanceStep sibling = InstanceStep.builder()
                .id(UUID.randomUUID())
                .instanceId(instanceId)
                .stepOrder(1)
                .parallelGroup(1)
                .status(InstanceStepStatus.APPROVED) // already done
                .build();

        setupAuthorization();
        when(decisionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceStepRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceStepRepository.findByInstanceIdAndStepOrder(instanceId, 1))
                .thenReturn(Flux.just(instanceStep, sibling));
        // No step_order=2 → closes as APPROVED
        when(instanceStepRepository.findByInstanceIdOrderByStepOrderAsc(instanceId))
                .thenReturn(Flux.just(instanceStep, sibling));
        when(instanceRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditService.log(any(), any(), any(), any())).thenReturn(Mono.just(AuditTrail.builder().build()));

        StepVerifier.create(approvalService.decide(instanceId, approverId, approveRequest()))
                .expectNextCount(1)
                .verifyComplete();

        verify(instanceRepository).save(argThat(i -> i.getStatus() == WorkflowInstanceStatus.APPROVED));
    }

    // ── decide — APPROVE, ALL_OF group ───────────────────────────────────────

    @Test
    void decide_allOfGroup_partialApprovals_doesNotAdvance() {
        workflowStep = WorkflowStep.builder()
                .id(stepId)
                .approverType(ApproverType.GROUP)
                .approverId(UUID.randomUUID().toString())
                .approvalMode(ApprovalMode.ALL_OF)
                .stepOrder(1)
                .build();

        setupGroupAuthorization();
        when(decisionRepository.existsByInstanceStepIdAndApproverId(any(), eq(approverId)))
                .thenReturn(Mono.just(false));
        when(decisionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // 2 group members, only 1 approval so far → should not advance
        when(groupMemberRepository.countByGroupId(any())).thenReturn(Mono.just(2L));
        when(decisionRepository.countApprovedByInstanceStepId(any())).thenReturn(Mono.just(1L));
        when(auditService.log(any(), any(), any(), any())).thenReturn(Mono.just(AuditTrail.builder().build()));

        StepVerifier.create(approvalService.decide(instanceId, approverId, approveRequest()))
                .expectNextCount(1)
                .verifyComplete();

        verify(instanceRepository, never()).save(any());
    }

    @Test
    void decide_allOfGroup_allMembersApproved_advances() {
        workflowStep = WorkflowStep.builder()
                .id(stepId)
                .approverType(ApproverType.GROUP)
                .approverId(UUID.randomUUID().toString())
                .approvalMode(ApprovalMode.ALL_OF)
                .stepOrder(1)
                .build();

        setupGroupAuthorization();
        when(decisionRepository.existsByInstanceStepIdAndApproverId(any(), eq(approverId)))
                .thenReturn(Mono.just(false));
        when(decisionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // 2 members, 2 approvals — should advance
        when(groupMemberRepository.countByGroupId(any())).thenReturn(Mono.just(2L));
        when(decisionRepository.countApprovedByInstanceStepId(any())).thenReturn(Mono.just(2L));
        when(instanceStepRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceStepRepository.findByInstanceIdOrderByStepOrderAsc(instanceId))
                .thenReturn(Flux.just(instanceStep));
        when(instanceRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditService.log(any(), any(), any(), any())).thenReturn(Mono.just(AuditTrail.builder().build()));

        StepVerifier.create(approvalService.decide(instanceId, approverId, approveRequest()))
                .expectNextCount(1)
                .verifyComplete();

        verify(instanceRepository).save(argThat(i -> i.getStatus() == WorkflowInstanceStatus.APPROVED));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private DecisionRequest approveRequest() {
        return new DecisionRequest(DecisionAction.APPROVE, null);
    }

    private void setupAuthorization() {
        when(instanceRepository.findById(instanceId)).thenReturn(Mono.just(pendingInstance));
        when(instanceStepRepository.findByInstanceIdAndStepOrderAndStatus(instanceId, 1, InstanceStepStatus.PENDING))
                .thenReturn(Flux.just(instanceStep));
        when(workflowStepRepository.findById(stepId)).thenReturn(Mono.just(workflowStep));
        // USER type direct match — approverId matches, no delegation needed
        when(instanceStepRepository.existsEscalatedToUser(instanceId, 1, approverId))
                .thenReturn(Mono.just(false));
    }

    private void setupGroupAuthorization() {
        when(instanceRepository.findById(instanceId)).thenReturn(Mono.just(pendingInstance));
        when(instanceStepRepository.findByInstanceIdAndStepOrderAndStatus(instanceId, 1, InstanceStepStatus.PENDING))
                .thenReturn(Flux.just(instanceStep));
        when(workflowStepRepository.findById(stepId)).thenReturn(Mono.just(workflowStep));
        when(groupMemberRepository.existsByGroupIdAndUserId(any(), eq(approverId)))
                .thenReturn(Mono.just(true));
        when(instanceStepRepository.existsEscalatedToUser(instanceId, 1, approverId))
                .thenReturn(Mono.just(false));
    }
}
