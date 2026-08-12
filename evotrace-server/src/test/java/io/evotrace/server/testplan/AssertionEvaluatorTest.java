package io.evotrace.server.testplan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssertionEvaluatorTest {

    private final AssertionEvaluator evaluator = new AssertionEvaluator();

    @Test
    void statusCodeAssertion() {
        var results = evaluator.evaluate(
                List.of(Map.of("type", "statusCode", "expected", 200)), 200, "ok", 10);

        assertEquals(1, results.size());
        assertTrue(Boolean.TRUE.equals(results.get(0).get("passed")));

        results = evaluator.evaluate(
                List.of(Map.of("type", "statusCode", "expected", 201)), 200, "ok", 10);
        assertFalse(Boolean.TRUE.equals(results.get(0).get("passed")));
    }

    @Test
    void bodyContainsAssertion() {
        var results = evaluator.evaluate(
                List.of(Map.of("type", "bodyContains", "expected", "success")),
                200, "{\"msg\":\"success\"}", 10);
        assertTrue(Boolean.TRUE.equals(results.get(0).get("passed")));

        results = evaluator.evaluate(
                List.of(Map.of("type", "bodyContains", "expected", "missing")),
                200, "{\"msg\":\"success\"}", 10);
        assertFalse(Boolean.TRUE.equals(results.get(0).get("passed")));
    }

    @Test
    void bodyNotContainsAssertion() {
        var results = evaluator.evaluate(
                List.of(Map.of("type", "bodyNotContains", "expected", "error")),
                200, "{\"msg\":\"success\"}", 10);
        assertTrue(Boolean.TRUE.equals(results.get(0).get("passed")));

        results = evaluator.evaluate(
                List.of(Map.of("type", "bodyNotContains", "expected", "success")),
                200, "{\"msg\":\"success\"}", 10);
        assertFalse(Boolean.TRUE.equals(results.get(0).get("passed")));
    }

    @Test
    void responseTimeAssertion() {
        var results = evaluator.evaluate(
                List.of(Map.of("type", "responseTimeMs", "expected", 100)), 200, "ok", 50);
        assertTrue(Boolean.TRUE.equals(results.get(0).get("passed")));

        results = evaluator.evaluate(
                List.of(Map.of("type", "responseTimeMs", "expected", 100)), 200, "ok", 150);
        assertFalse(Boolean.TRUE.equals(results.get(0).get("passed")));
    }

    @Test
    void unknownTypeFailsClosed() {
        var results = evaluator.evaluate(
                List.of(Map.of("type", "jsonPath", "expected", "$.code")), 200, "ok", 10);
        assertFalse(Boolean.TRUE.equals(results.get(0).get("passed")));
    }

    @Test
    void illegalExpectedValueFails() {
        var results = evaluator.evaluate(
                List.of(Map.of("type", "statusCode", "expected", "abc")), 200, "ok", 10);
        assertFalse(Boolean.TRUE.equals(results.get(0).get("passed")));
        assertTrue(String.valueOf(results.get(0).get("message")).contains("非法"));
    }

    @Test
    void nullAssertionsYieldEmptyResults() {
        assertTrue(evaluator.evaluate(null, 200, "ok", 10).isEmpty());
    }

    @Test
    void allPassedSemantics() {
        assertTrue(evaluator.allPassed(List.of()));
        assertTrue(evaluator.allPassed(List.of(Map.of("passed", true))));
        assertFalse(evaluator.allPassed(List.of(Map.of("passed", true), Map.of("passed", false))));
    }
}
