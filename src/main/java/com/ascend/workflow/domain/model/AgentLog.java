package com.ascend.workflow.domain.model;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("agent_logs")
public class AgentLog {

    @Id
    private UUID id;
    private UUID sessionId;
    private String userMessage;
    private Json toolCalls;
    private String assistantResponse;
    private Long durationMs;
    private OffsetDateTime createdAt;
}
