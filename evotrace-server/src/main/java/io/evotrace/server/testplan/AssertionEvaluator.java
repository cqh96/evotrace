package io.evotrace.server.testplan;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享断言求值器（对标 MeterSphere 断言体系）。
 * <p>从旧版 {@link TestExecutionRunner} 抽离，供用例执行器与场景执行器复用，
 * 支持 statusCode / bodyContains / bodyNotContains / responseTimeMs 四种内置断言。</p>
 */
@Component
public class AssertionEvaluator {

    /**
     * 求值一组断言。
     *
     * @param assertions 断言列表 [{type, expected}]
     * @param statusCode HTTP 状态码
     * @param body       响应体
     * @param durationMs 耗时
     * @return 逐条断言结果 [{type, expected, passed, message}]
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> evaluate(List<Map<String, Object>> assertions, int statusCode,
                                              String body, long durationMs) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (assertions == null) {
            return results;
        }
        for (Object a : assertions) {
            Map<String, Object> assertion = a instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : new LinkedHashMap<>();
            String type = String.valueOf(assertion.getOrDefault("type", ""));
            Object expected = assertion.get("expected");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", type);
            r.put("expected", expected != null ? String.valueOf(expected) : "");
            try {
                switch (type) {
                    case "statusCode" -> {
                        boolean ok = statusCode == Integer.parseInt(String.valueOf(expected));
                        r.put("passed", ok);
                        r.put("message", "状态码 " + statusCode + (ok ? " = " : " ≠ ") + expected);
                    }
                    case "bodyContains" -> {
                        boolean ok = expected != null && body.contains(String.valueOf(expected));
                        r.put("passed", ok);
                        r.put("message", ok ? "响应体包含: " + expected : "响应体缺少: " + expected);
                    }
                    case "bodyNotContains" -> {
                        boolean ok = expected == null || !body.contains(String.valueOf(expected));
                        r.put("passed", ok);
                        r.put("message", ok ? "响应体不含: " + expected : "响应体意外包含: " + expected);
                    }
                    case "responseTimeMs" -> {
                        boolean ok = durationMs <= Long.parseLong(String.valueOf(expected));
                        r.put("passed", ok);
                        r.put("message", "耗时 " + durationMs + "ms " + (ok ? "≤" : ">") + " " + expected + "ms");
                    }
                    default -> {
                        r.put("passed", false);
                        r.put("message", "未知断言类型: " + type);
                    }
                }
            } catch (NumberFormatException e) {
                r.put("passed", false);
                r.put("message", "断言期望值非法: " + expected);
            }
            results.add(r);
        }
        return results;
    }

    /** 断言全通过：为空视为跳过（由调用方决定兜底策略）。 */
    public boolean allPassed(List<Map<String, Object>> assertionResults) {
        return assertionResults != null && assertionResults.stream()
                .allMatch(a -> Boolean.TRUE.equals(a.get("passed")));
    }
}