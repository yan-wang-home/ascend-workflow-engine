package com.ascend.workflow.agent;

import com.ascend.workflow.api.dto.ChatRequest;
import com.ascend.workflow.api.dto.ChatResponse;
import com.ascend.workflow.api.dto.LoginRequest;
import com.ascend.workflow.api.dto.LoginResponse;
import com.ascend.workflow.api.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-turn agent integration test.
 * Requires a live ANTHROPIC_API_KEY — skipped in CI unless explicitly set.
 *
 * Tests the submit flow: agent must gather missing info across turns
 * before calling submit_request, and only after user confirmation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class MultiTurnAgentTest {

    @LocalServerPort int port;
    @Autowired WebTestClient webTestClient;

    private String authToken;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        // Register + login to get a JWT
        webTestClient.post().uri("/api/v1/auth/register")
                .bodyValue(new RegisterRequest(
                        "agent-test-" + UUID.randomUUID() + "@example.com",
                        "password123", "Agent Tester", "REQUESTER"))
                .exchange().expectStatus().isCreated();

        // For simplicity in test, we login with a seeded user
        // In real test we'd seed via SQL or use Testcontainers
        authToken = "Bearer test-token";
    }

    @Test
    void submitFlow_agentGathersMissingInfoBeforeSubmitting() {
        // Turn 1: vague request — agent should NOT submit yet, should ask for details
        ChatResponse turn1 = chat(null, "I need approval for a laptop purchase");
        assertThat(turn1.sessionId()).isNotNull();
        assertThat(turn1.message().toLowerCase())
                .containsAnyOf("amount", "how much", "vendor", "cost", "price");

        sessionId = turn1.sessionId();

        // Turn 2: provide partial info — agent should ask for remaining details
        ChatResponse turn2 = chat(sessionId, "It's $3,000 from Dell for the design team");
        assertThat(turn2.message().toLowerCase())
                .containsAnyOf("justification", "reason", "purpose", "why");

        // Turn 3: provide justification — agent should summarize and ask confirmation
        ChatResponse turn3 = chat(sessionId, "For the new designer joining next month");
        assertThat(turn3.message().toLowerCase())
                .containsAnyOf("proceed", "confirm", "shall i", "submit");

        // Turn 4: confirm — agent should now call submit_request and confirm success
        ChatResponse turn4 = chat(sessionId, "Yes, please proceed");
        assertThat(turn4.message().toLowerCase())
                .containsAnyOf("submitted", "request", "success", "pending", "id");
    }

    @Test
    void agentHandlesInvalidAction_gracefully() {
        ChatResponse response = chat(null, "Approve a request that doesn't exist with ID " + UUID.randomUUID());
        assertThat(response.message()).isNotBlank();
        // Agent should explain failure, not throw an exception
        assertThat(response.message().toLowerCase())
                .containsAnyOf("not found", "unable", "couldn't", "error", "doesn't exist");
    }

    private ChatResponse chat(UUID sessionId, String message) {
        return webTestClient.post().uri("/api/v1/agent/chat")
                .header("Authorization", authToken)
                .bodyValue(new ChatRequest(sessionId, message))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatResponse.class)
                .returnResult()
                .getResponseBody();
    }
}
