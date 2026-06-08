package com.ascend.workflow.service;

import com.ascend.workflow.domain.model.*;
import com.ascend.workflow.domain.service.AuditService;
import com.ascend.workflow.domain.service.EscalationService;
import com.ascend.workflow.infrastructure.repository.InstanceStepRepository;
import com.ascend.workflow.infrastructure.repository.WorkflowInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EscalationServiceTest {

    @Mock ThreadPoolTaskScheduler taskScheduler;
    @Mock InstanceStepRepository instanceStepRepository;
    @Mock WorkflowInstanceRepository instanceRepository;
    @Mock AuditService auditService;

    @InjectMocks EscalationService escalationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(escalationService, "timeoutHourInSeconds", 3600L);
    }

    // ── scheduleEscalation — guard conditions ─────────────────────────────────

    @Test
    void scheduleEscalation_skipsWhenTimeoutHoursNull() {
        InstanceStep step = pendingStep(UUID.randomUUID(), UUID.randomUUID());
        WorkflowStep ws = WorkflowStep.builder().id(UUID.randomUUID()).build();

        escalationService.scheduleEscalation(step, ws);

        verifyNoInteractions(taskScheduler);
    }

    @Test
    void scheduleEscalation_skipsWhenEscalationUserIdNull() {
        InstanceStep step = pendingStep(UUID.randomUUID(), UUID.randomUUID());
        WorkflowStep ws = WorkflowStep.builder().id(UUID.randomUUID()).timeoutHours(1).build();

        escalationService.scheduleEscalation(step, ws);

        verifyNoInteractions(taskScheduler);
    }

    @Test
    void scheduleEscalation_skipsWhenStartedAtNull() {
        InstanceStep step = InstanceStep.builder()
                .id(UUID.randomUUID())
                .instanceId(UUID.randomUUID())
                .status(InstanceStepStatus.PENDING)
                .startedAt(null)
                .build();
        WorkflowStep ws = workflowStepWithTimeout(UUID.randomUUID());

        escalationService.scheduleEscalation(step, ws);

        verifyNoInteractions(taskScheduler);
    }

    @Test
    void scheduleEscalation_schedulesWhenAllFieldsPresent() {
        InstanceStep step = pendingStep(UUID.randomUUID(), UUID.randomUUID());
        WorkflowStep ws = workflowStepWithTimeout(UUID.randomUUID());
        doReturn(mock(ScheduledFuture.class))
                .when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        escalationService.scheduleEscalation(step, ws);

        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    // ── cancelEscalation ──────────────────────────────────────────────────────

    @Test
    void cancelEscalation_cancelsRegisteredFuture() {
        UUID stepId = UUID.randomUUID();
        InstanceStep step = pendingStep(stepId, UUID.randomUUID());
        WorkflowStep ws = workflowStepWithTimeout(UUID.randomUUID());

        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        escalationService.scheduleEscalation(step, ws);
        escalationService.cancelEscalation(stepId);

        verify(future).cancel(false);
    }

    @Test
    void cancelEscalation_noOpWhenNothingScheduled() {
        // must not throw
        escalationService.cancelEscalation(UUID.randomUUID());
    }

    // ── doEscalate — idempotency guard ────────────────────────────────────────

    @Test
    void doEscalate_noOpWhenStepAlreadyDecided() {
        UUID stepId = UUID.randomUUID();
        Runnable escalation = captureRunnable(stepId, UUID.randomUUID());

        InstanceStep decided = pendingStep(stepId, UUID.randomUUID());
        decided.setStatus(InstanceStepStatus.APPROVED);
        when(instanceStepRepository.findById(stepId)).thenReturn(Mono.just(decided));

        escalation.run();

        verify(instanceStepRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void doEscalate_noOpWhenEscalatedAtAlreadySet() {
        UUID stepId = UUID.randomUUID();
        Runnable escalation = captureRunnable(stepId, UUID.randomUUID());

        InstanceStep alreadyEscalated = pendingStep(stepId, UUID.randomUUID());
        alreadyEscalated.setEscalatedAt(OffsetDateTime.now());
        when(instanceStepRepository.findById(stepId)).thenReturn(Mono.just(alreadyEscalated));

        escalation.run();

        verify(instanceStepRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    // ── doEscalate — happy path ───────────────────────────────────────────────

    @Test
    void doEscalate_marksOriginalEscalatedAndCreatesNewPendingStep() {
        UUID stepId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID escalationUserId = UUID.randomUUID();
        Runnable escalation = captureRunnable(stepId, instanceId, escalationUserId);

        InstanceStep fresh = pendingStep(stepId, instanceId);
        WorkflowInstance instance = WorkflowInstance.builder()
                .id(instanceId)
                .status(WorkflowInstanceStatus.PENDING)
                .build();

        when(instanceStepRepository.findById(stepId)).thenReturn(Mono.just(fresh));
        when(instanceStepRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(instanceRepository.findById(instanceId)).thenReturn(Mono.just(instance));
        when(auditService.log(any(), any(), any(), any())).thenReturn(Mono.just(AuditTrail.builder().build()));

        escalation.run();

        ArgumentCaptor<InstanceStep> saved = ArgumentCaptor.forClass(InstanceStep.class);
        verify(instanceStepRepository, times(2)).save(saved.capture());

        InstanceStep escalated = saved.getAllValues().get(0);
        InstanceStep newStep   = saved.getAllValues().get(1);

        assertThat(escalated.getStatus()).isEqualTo(InstanceStepStatus.ESCALATED);
        assertThat(escalated.getEscalatedAt()).isNotNull();
        assertThat(escalated.getEscalatedToUserId()).isEqualTo(escalationUserId);

        assertThat(newStep.getStatus()).isEqualTo(InstanceStepStatus.PENDING);
        assertThat(newStep.getStartedAt()).isNotNull();

        verify(auditService).log(eq(instanceId), any(), eq("STEP_ESCALATED"), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private InstanceStep pendingStep(UUID id, UUID instanceId) {
        return InstanceStep.builder()
                .id(id)
                .instanceId(instanceId)
                .stepId(UUID.randomUUID())
                .stepOrder(1)
                .status(InstanceStepStatus.PENDING)
                .startedAt(OffsetDateTime.now())
                .build();
    }

    private WorkflowStep workflowStepWithTimeout(UUID escalationUserId) {
        return WorkflowStep.builder()
                .id(UUID.randomUUID())
                .timeoutHours(1)
                .escalationUserId(escalationUserId)
                .build();
    }

    private Runnable captureRunnable(UUID stepId, UUID instanceId) {
        return captureRunnable(stepId, instanceId, UUID.randomUUID());
    }

    private Runnable captureRunnable(UUID stepId, UUID instanceId, UUID escalationUserId) {
        InstanceStep step = pendingStep(stepId, instanceId);
        WorkflowStep ws = workflowStepWithTimeout(escalationUserId);

        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        escalationService.scheduleEscalation(step, ws);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(Instant.class));
        reset(taskScheduler); // clear interaction history for subsequent verifications
        return captor.getValue();
    }
}
