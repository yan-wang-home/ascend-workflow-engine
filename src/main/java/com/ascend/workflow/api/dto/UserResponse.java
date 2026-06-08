package com.ascend.workflow.api.dto;

import com.ascend.workflow.domain.model.User;
import com.ascend.workflow.domain.model.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String name,
        UserRole role,
        OffsetDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(),
                user.getRole(), user.getCreatedAt());
    }
}
