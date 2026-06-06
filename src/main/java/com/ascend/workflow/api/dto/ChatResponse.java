package com.ascend.workflow.api.dto;

import java.util.UUID;

public record ChatResponse(UUID sessionId, String message) {}
