package com.ascend.workflow.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${app.anthropic.api-key}")
    private String apiKey;

    @Value("${app.anthropic.model:claude-sonnet-4-6}")
    private String model;

    private static final String BASE_URL = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";

    public Mono<JsonNode> chat(String systemPrompt, ArrayNode messages, List<ObjectNode> tools) {
        ObjectNode body = objectMapper.createObjectNode()
                .put("model", model)
                .put("max_tokens", 4096);

        body.put("system", systemPrompt);
        body.set("messages", messages);

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = objectMapper.createArrayNode();
            tools.forEach(toolsArray::add);
            body.set("tools", toolsArray);
        }

        return webClientBuilder.build()
                .post()
                .uri(BASE_URL + "/v1/messages")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnError(e -> log.error("Anthropic API error: {}", e.getMessage()));
    }
}
