package com.ascend.workflow.domain.service;

import com.ascend.workflow.api.dto.LoginRequest;
import com.ascend.workflow.api.dto.LoginResponse;
import com.ascend.workflow.api.dto.RegisterRequest;
import com.ascend.workflow.api.dto.UserResponse;
import com.ascend.workflow.domain.model.User;
import com.ascend.workflow.domain.model.UserRole;
import com.ascend.workflow.infrastructure.repository.UserRepository;
import com.ascend.workflow.infrastructure.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public Mono<UserResponse> register(RegisterRequest request, boolean callerIsAdmin) {
        if (!callerIsAdmin && request.role() != null && request.role() != UserRole.REQUESTER) {
            return Mono.error(new IllegalArgumentException(
                    "Only an ADMIN can assign roles other than REQUESTER"));
        }
        return userRepository.existsByEmail(request.email())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new IllegalArgumentException("Email already registered"));
                    }
                    UserRole role = (callerIsAdmin && request.role() != null)
                            ? request.role()
                            : UserRole.REQUESTER;
                    User user = User.builder()
                            .email(request.email())
                            .passwordHash(passwordEncoder.encode(request.password()))
                            .name(request.name())
                            .role(role)
                            .createdAt(OffsetDateTime.now())
                            .updatedAt(OffsetDateTime.now())
                            .build();
                    return userRepository.save(user).map(UserResponse::from);
                });
    }

    public Mono<LoginResponse> login(LoginRequest request) {
        return userRepository.findByEmail(request.email())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid credentials")))
                .flatMap(user -> {
                    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                        return Mono.error(new IllegalArgumentException("Invalid credentials"));
                    }
                    String token = jwtUtil.generate(user.getId(), user.getEmail(), user.getRole());
                    return Mono.just(new LoginResponse(token, user.getId(), user.getName(), user.getRole()));
                });
    }
}
