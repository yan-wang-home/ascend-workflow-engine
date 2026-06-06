package com.ascend.workflow.domain.service;

import com.ascend.workflow.domain.model.StepCondition;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class ConditionEvaluator {

    public boolean evaluate(List<StepCondition> conditions, JsonNode metadata) {
        // All conditions must pass (AND logic)
        return conditions.stream().allMatch(condition -> evaluate(condition, metadata));
    }

    private boolean evaluate(StepCondition condition, JsonNode metadata) {
        JsonNode field = metadata.get(condition.getFieldName());
        if (field == null || field.isNull()) {
            log.debug("Field '{}' not found in metadata — condition fails", condition.getFieldName());
            return false;
        }

        String fieldValue = field.asText();
        String conditionValue = condition.getValue();

        try {
            return switch (condition.getOperator()) {
                case "EQ"       -> fieldValue.equals(conditionValue);
                case "NEQ"      -> !fieldValue.equals(conditionValue);
                case "GT"       -> toBigDecimal(fieldValue).compareTo(toBigDecimal(conditionValue)) > 0;
                case "GTE"      -> toBigDecimal(fieldValue).compareTo(toBigDecimal(conditionValue)) >= 0;
                case "LT"       -> toBigDecimal(fieldValue).compareTo(toBigDecimal(conditionValue)) < 0;
                case "LTE"      -> toBigDecimal(fieldValue).compareTo(toBigDecimal(conditionValue)) <= 0;
                case "IN"       -> Arrays.asList(conditionValue.split(",")).contains(fieldValue);
                case "CONTAINS" -> fieldValue.contains(conditionValue);
                default         -> {
                    log.warn("Unknown operator: {}", condition.getOperator());
                    yield false;
                }
            };
        } catch (NumberFormatException e) {
            log.warn("Numeric comparison failed for field '{}': {}", condition.getFieldName(), e.getMessage());
            return false;
        }
    }

    private BigDecimal toBigDecimal(String value) {
        return new BigDecimal(value.trim());
    }
}
