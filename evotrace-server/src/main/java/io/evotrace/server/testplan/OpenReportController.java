package io.evotrace.server.testplan;

import io.evotrace.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 测试报告免登录分享（随机 token 只读，走 open-api 免鉴权路径）。
 */
@RestController
@RequestMapping("/open-api/v1")
public class OpenReportController {

    private final TestReportService reportService;

    public OpenReportController(TestReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/reports/share/{token}")
    public Result<Map<String, Object>> share(@PathVariable String token) {
        try {
            return Result.ok(reportService.share(token));
        } catch (Exception e) {
            return Result.fail("EVO-BIZ-404", "报告不存在或分享链接已失效");
        }
    }
}