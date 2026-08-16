package io.evotrace.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UI 测试（对标 MeterSphere UI 自动化）：基于 Selenium WebDriver 的低代码浏览器自动化。
 * <p>用例由有序步骤组成：OPEN（导航）/ CLICK（点击）/ INPUT（输入）/ ASSERT_TEXT（文本断言）/
 * WAIT（等待）/ ASSERT_URL。步骤通过 CSS 选择器定位元素，无需编写脚本。</p>
 * <p>当运行环境未配置 chromedriver 时，执行返回 SKIPPED 并提示配置，保证功能可降级、不中断其余模块。</p>
 */
@Service
public class UiTestService {

    private static final Logger log = LoggerFactory.getLogger(UiTestService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final String driverPath;

    public UiTestService(JdbcTemplate jdbc,
                         @Value("${evotrace.ui.driver-path:}") String driverPath) {
        this.jdbc = jdbc;
        this.driverPath = driverPath;
    }

    // ==================== CRUD ====================

    public List<Map<String, Object>> list(Long projectId) {
        return jdbc.queryForList("""
                SELECT id, name, description, base_url AS "baseUrl", enabled, status,
                       last_result AS "lastResult", created_at AS "createdAt", updated_at AS "updatedAt"
                FROM ui_test_case WHERE project_id = ? ORDER BY updated_at DESC
                """, projectId);
    }

    public Map<String, Object> detail(Long projectId, Long id) {
        return jdbc.queryForMap("""
                SELECT id, project_id AS "projectId", name, description, base_url AS "baseUrl",
                       steps_json AS "steps", script, enabled, status, last_result AS "lastResult",
                       created_at AS "createdAt", updated_at AS "updatedAt"
                FROM ui_test_case WHERE id = ? AND project_id = ?
                """, id, projectId);
    }

    @Transactional
    public Long create(Long projectId, String name, String description, String baseUrl,
                       List<Map<String, Object>> steps, String script) {
        return jdbc.queryForObject("""
                INSERT INTO ui_test_case(project_id, name, description, base_url, steps_json, script)
                VALUES (?, ?, ?, ?, ?::jsonb, ?) RETURNING id
                """, Long.class, projectId, name, description, baseUrl, writeJson(steps), script);
    }

    @Transactional
    public void update(Long projectId, Long id, String name, String description, String baseUrl,
                       List<Map<String, Object>> steps, String script, Boolean enabled) {
        jdbc.update("""
                UPDATE ui_test_case SET name = COALESCE(?, name), description = COALESCE(?, description),
                    base_url = COALESCE(?, base_url), steps_json = COALESCE(?::jsonb, steps_json),
                    script = COALESCE(?, script), enabled = COALESCE(?, enabled), updated_at = now()
                WHERE id = ? AND project_id = ?
                """, name, description, baseUrl, steps == null ? null : writeJson(steps), script, enabled, id, projectId);
    }

    @Transactional
    public void delete(Long projectId, Long id) {
        jdbc.update("DELETE FROM ui_test_case WHERE id = ? AND project_id = ?", id, projectId);
    }

    // ==================== 执行 ====================

    /** 执行 UI 用例（同步阻塞）。返回逐步结果并落库。 */
    public Map<String, Object> run(Long projectId, Long id) {
        Map<String, Object> row = detail(projectId, id);
        List<Map<String, Object>> steps;
        try {
            steps = parseSteps(row.get("steps"));
        } catch (Exception e) {
            log.warn("ui steps parse failed: test={} error={}", id, e.getMessage());
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("testId", id);
            failed.put("testName", row.get("name"));
            failed.put("verdict", "FAILED");
            failed.put("durationMs", 0);
            failed.put("steps", List.of());
            failed.put("error", "步骤解析失败: " + e.getMessage());
            persistResult(id, "FAILED", failed);
            return failed;
        }

        jdbc.update("UPDATE ui_test_case SET status = 'RUNNING', updated_at = now() WHERE id = ?", id);

        if (!driverAvailable()) {
            Map<String, Object> skipped = skippedResult(id, row, "未配置 Selenium WebDriver（chromedriver），已跳过执行。请在服务配置 evotrace.ui.driver-path 开启。");
            persistResult(id, "SKIPPED", skipped);
            return skipped;
        }

        long started = System.nanoTime();
        WebDriver driver = null;
        List<Map<String, Object>> stepResults = new ArrayList<>();
        String verdict = "PASSED";
        String error = null;
        try {
            driver = newDriver();
            driver.manage().window().maximize();
            for (int i = 0; i < steps.size(); i++) {
                Map<String, Object> result;
                try {
                    result = executeStep(driver, steps.get(i), i, row);
                } catch (Exception e) {
                    log.warn("ui step error: test={} index={} error={}", id, i, e.getMessage());
                    result = new LinkedHashMap<>();
                    result.put("index", i);
                    result.put("type", steps.get(i).getOrDefault("type", ""));
                    result.put("status", "FAILED");
                    result.put("error", e.getMessage());
                    verdict = "FAILED";
                    error = e.getMessage();
                    stepResults.add(result);
                    break;
                }
                if (!"PASSED".equals(result.get("status"))) {
                    verdict = "FAILED";
                    error = String.valueOf(result.get("error"));
                    stepResults.add(result);
                    break;
                }
                stepResults.add(result);
            }
        } catch (Exception e) {
            log.warn("ui driver init failed: {}", e.getMessage());
            verdict = "FAILED";
            error = "浏览器启动失败: " + e.getMessage();
        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignore) {}
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testId", id);
        result.put("testName", row.get("name"));
        result.put("verdict", verdict);
        result.put("durationMs", (System.nanoTime() - started) / 1_000_000);
        result.put("steps", stepResults);
        if (error != null) result.put("error", error);
        persistResult(id, verdict, result);
        return result;
    }

    private Map<String, Object> executeStep(WebDriver driver, Map<String, Object> step, int index, Map<String, Object> row) {
        String type = String.valueOf(step.getOrDefault("type", "OPEN")).toUpperCase();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("index", index);
        r.put("type", type);
        String baseUrl = row.get("baseUrl") == null ? "" : String.valueOf(row.get("baseUrl"));
        switch (type) {
            case "OPEN" -> {
                String url = resolveUrl(String.valueOf(step.getOrDefault("url", "")), baseUrl);
                driver.get(url);
                r.put("url", url);
                r.put("status", "PASSED");
            }
            case "CLICK" -> {
                String sel = String.valueOf(step.getOrDefault("selector", ""));
                WebElement el = find(driver, sel);
                el.click();
                r.put("selector", sel);
                r.put("status", "PASSED");
            }
            case "INPUT" -> {
                String sel = String.valueOf(step.getOrDefault("selector", ""));
                String value = String.valueOf(step.getOrDefault("value", ""));
                WebElement el = find(driver, sel);
                // WebDriver.clear() 不触发 input 事件且 Ctrl+A 在 headless Chrome 下
                // 不生效,Vue/React 的 v-model 预填值会与 sendKeys 叠加(如 admin→adminadmin)。
                // 用 JS 置空并派发 input 事件让 v-model 同步,再键入。
                el.click();
                ((org.openqa.selenium.JavascriptExecutor) driver)
                        .executeScript("arguments[0].value=''; arguments[0].dispatchEvent(new Event('input',{bubbles:true}))", el);
                el.sendKeys(value);
                r.put("selector", sel);
                r.put("value", value);
                r.put("status", "PASSED");
            }
            case "ASSERT_TEXT" -> {
                String sel = String.valueOf(step.getOrDefault("selector", ""));
                String expected = String.valueOf(step.getOrDefault("value", ""));
                WebElement el = find(driver, sel);
                String text = el.getText();
                boolean ok = text.contains(expected);
                r.put("selector", sel);
                r.put("expected", expected);
                r.put("actual", text);
                r.put("status", ok ? "PASSED" : "FAILED");
                if (!ok) r.put("error", "断言失败：期望包含「" + expected + "」，实际「" + text + "」");
            }
            case "WAIT" -> {
                int ms = step.get("value") instanceof Number n ? n.intValue() : 1000;
                try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                r.put("value", ms);
                r.put("status", "PASSED");
            }
            case "ASSERT_URL" -> {
                String expected = String.valueOf(step.getOrDefault("value", ""));
                String cur = driver.getCurrentUrl();
                boolean ok = cur.contains(expected);
                r.put("expected", expected);
                r.put("actual", cur);
                r.put("status", ok ? "PASSED" : "FAILED");
                if (!ok) r.put("error", "URL 断言失败：期望包含「" + expected + "」，实际「" + cur + "」");
            }
            default -> {
                r.put("status", "FAILED");
                r.put("error", "未知步骤类型: " + type);
            }
        }
        return r;
    }

    /** pgjdbc 对 jsonb 列返回 PGobject（toString 即 JSON 文本），统一解析为步骤列表。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSteps(Object raw) throws Exception {
        if (raw == null) return List.of();
        if (raw instanceof List<?> l) return (List<Map<String, Object>>) l;
        return mapper.readValue(String.valueOf(raw), List.class);
    }

    private WebElement find(WebDriver driver, String selector) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        return wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(selector)));
    }

    private String resolveUrl(String url, String baseUrl) {
        if (url == null || url.isBlank()) return baseUrl;
        if (url.startsWith("http")) return url;
        return baseUrl.replaceAll("/\\s*$", "") + url;
    }

    private boolean driverAvailable() {
        if (driverPath != null && !driverPath.isBlank()) {
            return new java.io.File(driverPath).exists();
        }
        // 未配置时尝试系统 PATH 中的 chromedriver
        try {
            Runtime.getRuntime().exec("which chromedriver").waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private WebDriver newDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage");
        if (driverPath != null && !driverPath.isBlank()) {
            System.setProperty("webdriver.chrome.driver", driverPath);
        }
        return new ChromeDriver(options);
    }

    private Map<String, Object> skippedResult(Long id, Map<String, Object> row, String reason) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("testId", id);
        r.put("testName", row.get("name"));
        r.put("verdict", "SKIPPED");
        r.put("durationMs", 0);
        r.put("steps", List.of());
        r.put("error", reason);
        return r;
    }

    private void persistResult(Long id, String status, Map<String, Object> result) {
        jdbc.update("UPDATE ui_test_case SET status = ?, last_result = ?::jsonb, updated_at = now() WHERE id = ?",
                status, writeJson(result), id);
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败: " + e.getMessage(), e);
        }
    }
}