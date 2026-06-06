package com.ascend.workflow.api.dto;

import java.util.UUID;

public record LoginResponse(String token, UUID userId, String name, String role) {}
