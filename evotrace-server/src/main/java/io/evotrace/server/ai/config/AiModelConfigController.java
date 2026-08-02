package io.evotrace.server.ai.config;

import io.evotrace.common.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 模型接入配置管理(系统页面):多模型配置 + 默认模型路由 + 测试连接。
 */
@RestController
@RequestMapping("/api/v1/ai/models")
public class AiModelConfigController {

    private final AiModelConfigService service;

    public AiModelConfigController(AiModelConfigService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(service.status());
    }

    @PostMapping
    public Result<AiModelConfig> create(@RequestBody AiModelConfig cfg) {
        return Result.ok(service.create(cfg));
    }

    @PutMapping("/{id}")
    public Result<AiModelConfig> update(@PathVariable Long id, @RequestBody AiModelConfig cfg) {
        return Result.ok(service.update(id, cfg));
    }

    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        service.setEnabled(id, true);
        return Result.ok(null);
    }

    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        service.setEnabled(id, false);
        return Result.ok(null);
    }

    @PostMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        service.setDefault(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable Long id) {
        return Result.ok(service.test(id));
    }
}
