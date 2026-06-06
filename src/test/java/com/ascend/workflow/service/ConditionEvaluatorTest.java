package com.ascend.workflow.service;

import com.ascend.workflow.domain.model.StepCondition;
import com.ascend.workflow.domain.service.ConditionEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluatorTest {

    private ConditionEvaluator evaluator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        evaluator = new ConditionEvaluator();
        objectMapper = new ObjectMapper();
    }

    @Test
    void emptyConditions_alwaysTrue() {
        ObjectNode metadata = objectMapper.createObjectNode().put("amount", 5000);
        assertThat(evaluator.evaluate(List.of(), metadata)).isTrue();
    }

    @Test
    void gt_passesWhenAmountAboveThreshold() {
        var condition = condition("amount", "GT", "5000");
        ObjectNode metadata = objectMapper.createObjectNode().put("amount", 7000);
        assertThat(evaluator.evaluate(List.of(condition), metadata)).isTrue();
    }

    @Test
    void gt_failsWhenAmountBelowThreshold() {
        var condition = condition("amount", "GT", "5000");
        ObjectNode metadata = objectMapper.createObjectNode().put("amount", 3000);
        assertThat(evaluator.evaluate(List.of(condition), metadata)).isFalse();
    }

    @Test
    void eq_matchesExactValue() {
        var condition = condition("category", "EQ", "travel");
        ObjectNode metadata = objectMapper.createObjectNode().put("category", "travel");
        assertThat(evaluator.evaluate(List.of(condition), metadata)).isTrue();
    }

    @Test
    void multipleConditions_allMustPass() {
        var c1 = condition("amount", "GT", "1000");
        var c2 = condition("category", "EQ", "equipment");
        ObjectNode metadata = objectMapper.createObjectNode()
                .put("amount", 5000)
                .put("category", "equipment");
        assertThat(evaluator.evaluate(List.of(c1, c2), metadata)).isTrue();
    }

    @Test
    void multipleConditions_failsIfOneConditionFails() {
        var c1 = condition("amount", "GT", "1000");
        var c2 = condition("category", "EQ", "equipment");
        ObjectNode metadata = objectMapper.createObjectNode()
                .put("amount", 5000)
                .put("category", "travel"); // does not match
        assertThat(evaluator.evaluate(List.of(c1, c2), metadata)).isFalse();
    }

    @Test
    void missingField_returnsFalse() {
        var condition = condition("vendor", "EQ", "Apple");
        ObjectNode metadata = objectMapper.createObjectNode().put("amount", 5000);
        assertThat(evaluator.evaluate(List.of(condition), metadata)).isFalse();
    }

    @Test
    void in_matchesOneOfValues() {
        var condition = condition("department", "IN", "engineering,product,design");
        ObjectNode metadata = objectMapper.createObjectNode().put("department", "product");
        assertThat(evaluator.evaluate(List.of(condition), metadata)).isTrue();
    }

    private StepCondition condition(String field, String operator, String value) {
        return StepCondition.builder()
                .id(UUID.randomUUID())
                .stepId(UUID.randomUUID())
                .fieldName(field)
                .operator(operator)
                .value(value)
                .build();
    }
}
