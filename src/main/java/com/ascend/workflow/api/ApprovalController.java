package com.ascend.workflow.api;

import com.ascend.workflow.api.dto.DecisionRequest;
import com.ascend.workflow.api.dto.PageResponse;
import com.ascend.workflow.domain.model.Decision;
import com.ascend.workflow.domain.model.WorkflowInstance;
import com.ascend.workflow.domain.service.ApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
@Tag(name = "Approvals", description = "Approval inbox and decision making")
@SecurityRequirement(name = "bearerAuth")
public class ApprovalController {

    private final ApprovalService approvalService;

    @GetMapping("/inbox")
    @Operation(summary = "Get pending approvals for current user (includes delegated)")
    public Mono<PageResponse<WorkflowInstance>> inbox(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        return approvalService.getInbox(userId, pageable).collectList()
                .map(items -> PageResponse.of(items, page, size, items.size()));
    }

    @PostMapping("/{requestId}/decide")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Approve, reject, or request changes on a request")
    public Mono<Decision> decide(@PathVariable UUID requestId,
                                  @Valid @RequestBody DecisionRequest request,
                                  Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return approvalService.decide(requestId, userId, request);
    }
}
