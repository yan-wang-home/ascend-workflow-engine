package com.ascend.workflow.infrastructure.ai;

import com.ascend.workflow.domain.model.AgentLog;
import com.ascend.workflow.domain.model.AgentSession;
import com.ascend.workflow.domain.model.User;
import com.ascend.workflow.infrastructure.repository.AgentLogRepository;
import com.ascend.workflow.infrastructure.repository.AgentSessionRepository;
import com.ascend.workflow.infrastructure.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AnthropicClient anthropicClient;
    private final ToolDefinitions toolDefinitions;
    private final AgentToolsService agentToolsService;
    private final AgentSessionRepository sessionRepository;
    private final AgentLogRepository agentLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are an AI-powered approval workflow assistant for Ascend's accounting platform.

            You help users submit approval requests, review pending approvals, make decisions,
            manage delegations, and create workflow templates through natural conversation.

            Current user: %s (%s)
            Role: %s
            User ID: %s

            Guidelines:
            - Only perform actions the current user is authorized to do based on their role
            - Before executing any write action (submit_request, make_decision, create_delegation,
              create_workflow_template), present a structured plan showing exactly what you intend
              to do and all parameters (e.g. request title, decision action, comment, delegate, dates).
              Invite the user to adjust any details: "Does this look right, or would you like to change anything?"
              Incorporate any changes the user requests and re-present the updated plan.
              Only call the tool once the user explicitly confirms the final plan (e.g. "Yes", "Go ahead", "Confirm").
            - If information needed to complete a request is missing, ask for it before presenting the plan
            - If a tool returns success=false, explain what went wrong clearly and suggest next steps
            - Always confirm the outcome after executing an action
            - Be concise and professional
            - Do not use markdown tables or headers in your responses. Use plain text with simple line breaks and labels instead (e.g. "Step 1: Finance Approval (finance@ascend.com)")
            """;

    private record AgentResult(String response, String toolCallsJson) {}

    public Mono<String> chat(UUID userId, UUID sessionId, String userMessage) {
        long startMs = System.currentTimeMillis();

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new IllegalStateException("User not found")))
                .flatMap(user -> loadOrCreateSession(userId, sessionId)
                        .flatMap(session -> Mono.fromCallable(() -> runAgentLoop(user, session, userMessage))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(result -> persistSession(session, userMessage, result.response())
                                        .then(persistLog(session.getId(), userMessage, result.response(),
                                                result.toolCallsJson(), System.currentTimeMillis() - startMs))
                                        .thenReturn(result.response()))));
    }

    private AgentResult runAgentLoop(User user, AgentSession session, String userMessage) throws Exception {
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE,
                user.getName(), user.getEmail(), user.getRole(), user.getId());

        ArrayNode messages = objectMapper.createArrayNode();
        deserializeHistory(session).forEach(messages::add);
        messages.addObject().put("role", "user").put("content", userMessage);

        List<ObjectNode> tools = toolDefinitions.all();
        List<ObjectNode> toolCallsLog = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            JsonNode response = anthropicClient.chat(systemPrompt, messages, tools).block();
            if (response == null) {
                return new AgentResult("I encountered an error communicating with the AI service.", "[]");
            }

            String stopReason = response.path("stop_reason").asText();
            JsonNode contentArray = response.path("content");

            // Append assistant turn (may contain text and/or tool_use blocks)
            ObjectNode assistantMsg = objectMapper.createObjectNode();
            assistantMsg.put("role", "assistant");
            assistantMsg.set("content", contentArray);
            messages.add(assistantMsg);

            if (!"tool_use".equals(stopReason)) {
                return new AgentResult(extractText(contentArray), objectMapper.writeValueAsString(toolCallsLog));
            }

            // Execute all tool_use blocks and collect results
            ArrayNode toolResults = objectMapper.createArrayNode();
            for (JsonNode block : contentArray) {
                if (!"tool_use".equals(block.path("type").asText())) continue;

                String toolId   = block.path("id").asText();
                String toolName = block.path("name").asText();
                JsonNode input  = block.path("input");

                log.debug("Tool call: {} input={}", toolName, input);
                String result = agentToolsService.dispatch(toolName, input, user.getId());

                ObjectNode callLog = objectMapper.createObjectNode();
                callLog.put("tool", toolName);
                callLog.set("input", input);
                callLog.put("result", result);
                toolCallsLog.add(callLog);

                ObjectNode toolResult = objectMapper.createObjectNode();
                toolResult.put("type", "tool_result");
                toolResult.put("tool_use_id", toolId);
                toolResult.put("content", result);
                toolResults.add(toolResult);
            }

            // Tool results are sent back as a user turn
            ObjectNode toolResultMsg = objectMapper.createObjectNode();
            toolResultMsg.put("role", "user");
            toolResultMsg.set("content", toolResults);
            messages.add(toolResultMsg);
        }

        log.warn("Agent loop exceeded max iterations for user {}", user.getId());
        return new AgentResult("I was unable to complete the request within the allowed steps.", "[]");
    }

    private String extractText(JsonNode contentArray) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : contentArray) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText());
            }
        }
        return sb.isEmpty() ? "I processed your request." : sb.toString();
    }

    private Mono<AgentSession> loadOrCreateSession(UUID userId, UUID sessionId) {
        if (sessionId != null) {
            return sessionRepository.findById(sessionId)
                    .switchIfEmpty(sessionRepository.findByUserId(userId))
                    .switchIfEmpty(createSession(userId));
        }
        return sessionRepository.findByUserId(userId)
                .switchIfEmpty(createSession(userId));
    }

    private Mono<AgentSession> createSession(UUID userId) {
        AgentSession session = AgentSession.builder()
                .userId(userId)
                .conversationHistory(Json.of("[]"))
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        return sessionRepository.save(session);
    }

    private ArrayNode deserializeHistory(AgentSession session) {
        try {
            if (session.getConversationHistory() == null) return objectMapper.createArrayNode();
            return (ArrayNode) objectMapper.readTree(session.getConversationHistory().asString());
        } catch (Exception e) {
            log.warn("Failed to deserialize conversation history, starting fresh: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    private Mono<AgentSession> persistSession(AgentSession session, String userMessage, String assistantResponse) {
        try {
            ArrayNode history = deserializeHistory(session);
            history.addObject().put("role", "user").put("content", userMessage);
            history.addObject().put("role", "assistant").put("content", assistantResponse);
            // Keep last 50 turns to stay within context limits
            while (history.size() > 50) history.remove(0);
            session.setConversationHistory(Json.of(history.toString()));
            session.setUpdatedAt(OffsetDateTime.now());
            return sessionRepository.save(session);
        } catch (Exception e) {
            log.error("Failed to persist session", e);
            return Mono.just(session);
        }
    }

    private Mono<AgentLog> persistLog(UUID sessionId, String userMessage, String assistantResponse,
                                      String toolCallsJson, long durationMs) {
        AgentLog logEntry = AgentLog.builder()
                .sessionId(sessionId)
                .userMessage(userMessage)
                .toolCalls(Json.of(toolCallsJson))
                .assistantResponse(assistantResponse)
                .durationMs(durationMs)
                .createdAt(OffsetDateTime.now())
                .build();
        return agentLogRepository.save(logEntry);
    }

    public Mono<UUID> getOrCreateSessionId(UUID userId) {
        return sessionRepository.findByUserId(userId)
                .map(AgentSession::getId)
                .switchIfEmpty(createSession(userId).map(AgentSession::getId));
    }
}
