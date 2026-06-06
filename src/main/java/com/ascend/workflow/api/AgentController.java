package com.ascend.workflow.api;

import com.ascend.workflow.api.dto.ChatRequest;
import com.ascend.workflow.api.dto.ChatResponse;
import com.ascend.workflow.api.dto.PageResponse;
import com.ascend.workflow.domain.model.AgentLog;
import com.ascend.workflow.infrastructure.ai.AgentService;
import com.ascend.workflow.infrastructure.repository.AgentLogRepository;
import com.ascend.workflow.infrastructure.repository.AgentSessionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Tag(name = "Agent", description = "AI-powered workflow assistant")
@SecurityRequirement(name = "bearerAuth")
public class AgentController {

    private final AgentService agentService;
    private final AgentSessionRepository sessionRepository;
    private final AgentLogRepository agentLogRepository;

    @PostMapping("/chat")
    @Operation(summary = "Chat with the AI workflow assistant")
    public Mono<ChatResponse> chat(@Valid @RequestBody ChatRequest request, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return agentService.chat(userId, request.sessionId(), request.message())
                .flatMap(response -> agentService.getOrCreateSessionId(userId)
                        .map(sessionId -> new ChatResponse(sessionId, response)));
    }

    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "View agent interaction logs (admin only)")
    public Mono<PageResponse<AgentLog>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return sessionRepository.findByUserId(userId)
                .flatMapMany(session -> agentLogRepository.findBySessionIdOrderByCreatedAtDesc(session.getId(), pageable))
                .collectList()
                .zipWith(sessionRepository.findByUserId(userId)
                        .flatMap(session -> agentLogRepository.countBySessionId(session.getId()))
                        .defaultIfEmpty(0L))
                .map(t -> PageResponse.of(t.getT1(), page, size, t.getT2()));
    }
}
