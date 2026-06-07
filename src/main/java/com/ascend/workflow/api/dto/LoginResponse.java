package com.ascend.workflow.api.dto;

import com.ascend.workflow.domain.model.UserRole;

import java.util.UUID;

public record LoginResponse(String token, UUID userId, String name, UserRole role) {}
