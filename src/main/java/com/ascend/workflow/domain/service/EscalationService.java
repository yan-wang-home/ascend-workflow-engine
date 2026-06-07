package com.ascend.workflow.domain.service;

import com.ascend.workflow.domain.model.InstanceStep;
import com.ascend.workflow.domain.model.InstanceStepStatus;
import com.ascend.workflow.domain.model.WorkflowStep;
import com.ascend.workflow.infrastructure.repository.InstanceStepRepository;
import com.ascend.workflow.infrastructure.repository.WorkflowInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static java.time.temporal.ChronoUnit.SECONDS;

@Slf4j
@Service
@RequiredArgsConstructor
public class EscalationService {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ThreadPoolTaskScheduler taskScheduler;
    private final InstanceStepRepository instanceStepRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final AuditService auditService;

    @Value("${app.escalation.timeout-hour-in-seconds:3600}")
    private long timeoutHourInSeconds;

    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public void scheduleEscalation(InstanceStep instanceStep, WorkflowStep workflowStep) {
        if (workflowStep.getTimeoutHours() == null || workflowStep.getEscalationUserId() == null) return;
        if (instanceStep.getStartedAt() == null) return;

        Instant triggerAt = instanceStep.getStartedAt().toInstant()
                .plus((long) workflowStep.getTimeoutHours() * timeoutHourInSeconds, SECONDS);

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> doEscalate(instanceStep, workflowStep),
                triggerAt);

        scheduledTasks.put(instanceStep.getId(), future);
        log.debug("Escalation scheduled for step {} at {}", instanceStep.getId(), triggerAt);
    }

    public void cancelEscalation(UUID instanceStepId) {
        ScheduledFuture<?> future = scheduledTasks.remove(instanceStepId);
        if (future != null) {
            future.cancel(false);
            log.debug("Escalation cancelled for step {}", instanceStepId);
        }
    }

    private void doEscalate(InstanceStep step, WorkflowStep workflowStep) {
        UUID escalateTo = workflowStep.getEscalationUserId();
        scheduledTasks.remove(step.getId());

        // Re-fetch to guard against race with a concurrent decision
        instanceStepRepository.findById(step.getId())
                .filter(fresh -> fresh.getStatus() == InstanceStepStatus.PENDING && fresh.getEscalatedAt() == null)
                .flatMap(fresh -> {
                    fresh.setStatus(InstanceStepStatus.ESCALATED);
                    fresh.setEscalatedAt(OffsetDateTime.now());
                    fresh.setEscalatedToUserId(escalateTo);
                    fresh.setCompletedAt(OffsetDateTime.now());

                    InstanceStep escalatedStep = InstanceStep.builder()
                            .instanceId(fresh.getInstanceId())
                            .stepId(fresh.getStepId())
                            .name(fresh.getName())
                            .stepOrder(fresh.getStepOrder())
                            .parallelGroup(fresh.getParallelGroup())
                            .status(InstanceStepStatus.PENDING)
                            .startedAt(OffsetDateTime.now())
                            .build();

                    return instanceStepRepository.save(fresh)
                            .then(instanceStepRepository.save(escalatedStep))
                            .then(instanceRepository.findById(fresh.getInstanceId()))
                            .flatMap(instance -> auditService.log(
                                    instance.getId(), SYSTEM_USER_ID, "STEP_ESCALATED",
                                    Map.of("fromStep", fresh.getId().toString(),
                                           "escalatedTo", escalateTo.toString(),
                                           "reason", "timeout")));
                })
                .subscribe(
                        v   -> log.info("Escalated step {}", step.getId()),
                        err -> log.error("Escalation failed for step {}", step.getId(), err)
                );
    }
}
