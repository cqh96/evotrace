package io.evotrace.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量性能测试（对标 MeterSphere 性能测试的单机形态）。
 * <p>对指定接口进行并发压测，输出 TPS / 平均RT / P95 / 错误率。JMeter 分布式压测列为远期，
 * 本实现不引入额外中间件，用 JDK 虚拟线程 + 信号量实现限流。</p>
 */
@Service
public class PerformanceTestService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceTestService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ApiRepository apiRepository;
    private final HttpClient httpClient;

    public PerformanceTestService(JdbcTemplate jdbc, ApiRepository apiRepository) {
        this.jdbc = jdbc;
        this.apiRepository = apiRepository;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public List<Map<String, Object>> list(Long projectId) {
        return jdbc.queryForList("""
                SELECT pt.id, pt.name, pt.endpoint_id AS "endpointId", e.method, e.path,
                       pt.concurrency, pt.duration_sec AS "durationSec", pt.status,
                       pt.summary_json AS "summary", pt.created_at AS "createdAt"
                FROM performance_test pt LEFT JOIN api_endpoint e ON e.id = pt.endpoint_id
                WHERE pt.project_id = ? ORDER BY pt.created_at DESC
                """, projectId);
    }

    @Transactional
    public Long create(Long projectId, Long endpointId, String name, int concurrency, int durationSec) {
        return jdbc.queryForObject("""
                INSERT INTO performance_test(project_id, endpoint_id, name, concurrency, duration_sec)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, projectId, endpointId, name, concurrency, durationSec);
    }

    @Transactional
    public void delete(Long projectId, Long testId) {
        jdbc.update("DELETE FROM performance_test WHERE id = ? AND project_id = ?", testId, projectId);
    }

    /** 执行压测（同步阻塞，返回结果并落库）。 */
    public Map<String, Object> run(Long projectId, Long testId, String baseUrl) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT pt.id, pt.endpoint_id AS "endpointId", pt.concurrency, pt.duration_sec AS "durationSec",
                       e.method, e.path, e.app_id AS "appId"
                FROM performance_test pt JOIN api_endpoint e ON e.id = pt.endpoint_id
                WHERE pt.id = ? AND pt.project_id = ?
                """, testId, projectId);
        Long endpointId = ((Number) row.get("endpointId")).longValue();
        ApiRepository.Endpoint ep = apiRepository.findById(endpointId);
        if (ep == null) {
            throw new IllegalArgumentException("接口不存在");
        }
        String method = String.valueOf(row.get("method"));
        String path = String.valueOf(row.get("path"));
        String base = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : appBaseUrl(ep.appId());
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("应用未配置 base-url，无法压测");
        }
        String url = base.replaceAll("/\\s*$", "") + path;
        int concurrency = ((Number) row.get("concurrency")).intValue();
        int durationSec = ((Number) row.get("durationSec")).intValue();

        jdbc.update("UPDATE performance_test SET status = 'RUNNING', updated_at = now() WHERE id = ?", testId);

        List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger success = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();
        AtomicLong total = new AtomicLong();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSec);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger active = new AtomicInteger();
            while (System.nanoTime() < deadline) {
                if (active.incrementAndGet() > concurrency) {
                    active.decrementAndGet();
                    try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                pool.submit(() -> {
                    try {
                        start.await();
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<String> resp = send(method, url);
                            long dur = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                            latencies.add(dur);
                            total.incrementAndGet();
                            if (resp.statusCode() >= 200 && resp.statusCode() < 300) success.incrementAndGet();
                            else error.incrementAndGet();
                        } catch (Exception e) {
                            error.incrementAndGet();
                        }
                    } catch (InterruptedException ignore) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                });
            }
            start.countDown();
            long wait = TimeUnit.SECONDS.toMillis(durationSec) + 3000;
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        Map<String, Object> summary = summarize(latencies, total.get(), success.get(), error.get(), durationSec);
        jdbc.update("""
                UPDATE performance_test SET status = 'DONE', summary_json = ?::jsonb, report_json = ?::jsonb,
                    updated_at = now() WHERE id = ?
                """, writeJson(summary), writeJson(summary), testId);
        log.info("perf test done: project={} test={} reqs={}", projectId, testId, total.get());
        return summary;
    }

    private HttpResponse<String> send(String method, String url) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url));
        rb.method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8));
        return httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> summarize(List<Long> latencies, long total, long success, long error, int durationSec) {
        long[] arr = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long avg = arr.length > 0 ? java.util.Arrays.stream(arr).sum() / arr.length : 0;
        long p95 = arr.length > 0 ? arr[(int) (arr.length * 0.95) - 1 < 0 ? 0 : (int) (arr.length * 0.95) - 1] : 0;
        double tps = durationSec > 0 ? (double) total / durationSec : 0;
        double errorRate = total > 0 ? error * 100.0 / total : 0;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalRequests", total);
        s.put("success", success);
        s.put("error", error);
        s.put("errorRate", Math.round(errorRate * 100.0) / 100.0);
        s.put("tps", Math.round(tps * 100.0) / 100.0);
        s.put("avgRtMs", avg);
        s.put("p95RtMs", p95);
        s.put("durationSec", durationSec);
        return s;
    }

    private String appBaseUrl(Long appId) {
        if (appId == null) return null;
        try {
            return jdbc.queryForObject("SELECT base_url FROM application WHERE id = ?", String.class, appId);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("压测结果序列化失败", e);
        }
    }
}