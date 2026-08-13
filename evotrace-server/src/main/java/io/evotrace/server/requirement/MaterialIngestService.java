package io.evotrace.server.requirement;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 需求材料统一解析：链接抓取 / 文档上传 / 原型链接+轻PRD 组合 → 纯文本。
 * <p>
 * 链接抓取仿 {@code GitLabDiffFetcher} 模式（JDK HttpClient + 超时 + 截断 +
 * 失败降级不抛异常）；文档解析走 Apache Tika（pdf/docx/html/txt/md/xlsx）。
 * SPA 页面（如 codesign）静态抓取只能拿到壳 HTML，正文过少时返回降级提示。
 */
@Service
public class MaterialIngestService {

    private static final Logger log = LoggerFactory.getLogger(MaterialIngestService.class);

    /** 抓取正文低于该长度视为"动态渲染无法解析"。 */
    private static final int MIN_MEANINGFUL_CHARS = 500;

    private final Tika tika = new Tika();
    private final HttpClient httpClient;

    @Value("${evotrace.pm.doc-parse.max-chars:200000}")
    private int maxChars;

    @Value("${evotrace.pm.doc-parse.fetch-timeout-seconds:30}")
    private int fetchTimeoutSeconds;

    public MaterialIngestService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 解析结果：text 为空时 message 给出可操作的降级提示。 */
    public record IngestResult(String text, String sourceType, String sourceName, String message) {
        public boolean ok() {
            return text != null && !text.isBlank();
        }
    }

    /** 抓取外部链接正文（含 SSRF 基础防护）。 */
    public IngestResult fromLink(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            return fail("LINK", url, "链接格式非法");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return fail("LINK", url, "仅支持 http/https 链接");
        }
        try {
            InetAddress host = InetAddress.getByName(uri.getHost());
            if (host.isLoopbackAddress() || host.isSiteLocalAddress() || host.isAnyLocalAddress()) {
                return fail("LINK", url, "不允许访问内网地址");
            }
        } catch (Exception e) {
            return fail("LINK", url, "链接域名解析失败");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(fetchTimeoutSeconds))
                    .header("User-Agent", "EvoTrace-DocParser/1.0")
                    .GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                return fail("LINK", url, "链接访问失败（HTTP " + response.statusCode() + "）");
            }
            String text = extractText(response.body(), url);
            if (text == null || text.length() < MIN_MEANINGFUL_CHARS) {
                return fail("LINK", url,
                        "该页面为动态渲染或内容过少，无法解析正文；请改用「上传导出文档」或「粘贴 PRD 文本」");
            }
            return new IngestResult(truncate(text), "LINK", url, "");
        } catch (Exception e) {
            log.warn("doc-parse: fetch link {} failed: {}", url, e.getMessage());
            return fail("LINK", url, "链接抓取失败：" + e.getMessage() + "；请改用文档上传");
        }
    }

    /** 解析上传文档（Tika 统一提取文本）。 */
    public IngestResult fromFile(MultipartFile file) {
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed";
        try {
            String text = tika.parseToString(file.getInputStream());
            if (text == null || text.isBlank()) {
                return fail("FILE", name, "文档内容为空或无法识别，请检查文件格式");
            }
            return new IngestResult(truncate(text), "FILE", name, "");
        } catch (IOException | TikaException e) {
            log.warn("doc-parse: parse file {} failed: {}", name, e.getMessage());
            return fail("FILE", name, "文档解析失败：" + e.getMessage());
        }
    }

    /** 组合模式：原型链接留档，解析以粘贴的 PRD 文本为主。 */
    public IngestResult fromPrototypeWithPrd(String prototypeUrl, String prdText) {
        if (prdText == null || prdText.isBlank()) {
            return fail("PROTOTYPE_PRD", prototypeUrl, "请粘贴轻量 PRD 文本");
        }
        return new IngestResult(truncate(prdText.trim()), "PROTOTYPE_PRD",
                prototypeUrl != null ? prototypeUrl : "", "");
    }

    /** Tika 提取；HTML 内容自动去标签。 */
    private String extractText(byte[] body, String name) {
        try {
            return tika.parseToString(new ByteArrayInputStream(body));
        } catch (Exception e) {
            // Tika 失败时按 UTF-8 兜底（纯文本/md 链接直接可用）
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private String truncate(String text) {
        String cleaned = text.strip();
        return cleaned.length() > maxChars ? cleaned.substring(0, maxChars) : cleaned;
    }

    private IngestResult fail(String type, String name, String message) {
        return new IngestResult(null, type, name, message);
    }
}
