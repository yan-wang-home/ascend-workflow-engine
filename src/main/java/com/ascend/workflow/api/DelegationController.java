package com.ascend.workflow.api;

import com.ascend.workflow.api.dto.CreateDelegationRequest;
import com.ascend.workflow.api.dto.PageResponse;
import com.ascend.workflow.domain.model.Delegation;
import com.ascend.workflow.domain.service.DelegationService;
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
@RequestMapping("/api/v1/delegations")
@RequiredArgsConstructor
@Tag(name = "Delegations", description = "Approval authority delegation management")
@SecurityRequirement(name = "bearerAuth")
public class DelegationController {

    private final DelegationService delegationService;

    @GetMapping
    @Operation(summary = "List my delegations (paginated)")
    public Mono<PageResponse<Delegation>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return delegationService.findByDelegator(userId, pageable).collectList()
                .zipWith(delegationService.countByDelegator(userId))
                .map(t -> PageResponse.of(t.getT1(), page, size, t.getT2()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a delegation")
    public Mono<Delegation> create(@Valid @RequestBody CreateDelegationRequest request, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return delegationService.create(request, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a delegation")
    public Mono<Void> revoke(@PathVariable UUID id, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return delegationService.revoke(id, userId);
    }
}
