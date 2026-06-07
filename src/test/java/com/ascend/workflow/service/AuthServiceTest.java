package com.ascend.workflow.service;

import com.ascend.workflow.api.dto.LoginRequest;
import com.ascend.workflow.api.dto.RegisterRequest;
import com.ascend.workflow.domain.model.User;
import com.ascend.workflow.domain.model.UserRole;
import com.ascend.workflow.domain.service.AuthService;
import com.ascend.workflow.infrastructure.repository.UserRepository;
import com.ascend.workflow.infrastructure.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtil jwtUtil;
    @InjectMocks AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .passwordHash("hashed")
                .name("Test User")
                .role(UserRole.REQUESTER)
                .build();
    }

    @Test
    void register_success() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode("password")).thenReturn("hashed");
        when(userRepository.save(any())).thenReturn(Mono.just(testUser));

        StepVerifier.create(authService.register(
                        new RegisterRequest("test@example.com", "password", "Test User", UserRole.REQUESTER)))
                .expectNextMatches(u -> u.getEmail().equals("test@example.com"))
                .verifyComplete();
    }

    @Test
    void register_duplicateEmail_fails() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(Mono.just(true));

        StepVerifier.create(authService.register(
                        new RegisterRequest("test@example.com", "password", "Test User", UserRole.REQUESTER)))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void login_success() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("password", "hashed")).thenReturn(true);
        when(jwtUtil.generate(any(), anyString(), any(UserRole.class))).thenReturn("jwt-token");

        StepVerifier.create(authService.login(new LoginRequest("test@example.com", "password")))
                .expectNextMatches(r -> r.token().equals("jwt-token"))
                .verifyComplete();
    }

    @Test
    void login_wrongPassword_fails() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        StepVerifier.create(authService.login(new LoginRequest("test@example.com", "wrong")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
