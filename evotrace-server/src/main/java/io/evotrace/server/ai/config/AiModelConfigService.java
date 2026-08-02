package io.evotrace.server.ai.config;

import io.evotrace.server.ai.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 模型接入配置管理:CRUD + 启停 + 设默认 + 测试连接。
 * 所有写操作后触发 {@link ModelRouter#refresh()},配置即时生效。
 */
@Service
public class AiModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfigService.class);

    private final AiModelConfigRepository repository;
    private final ModelRouter modelRouter;

    public AiModelConfigService(AiModelConfigRepository repository, ModelRouter modelRouter) {
        this.repository = repository;
        this.modelRouter = modelRouter;
    }

    public List<Map<String, Object>> list() {
        return repository.findAll().stream()
                .sorted((a, b) -> Boolean.compare(b.isDefault(), a.isDefault()))
                .map(this::toView)
                .toList();
    }

    @Transactional
    public AiModelConfig create(AiModelConfig cfg) {
        validate(cfg);
        if (repository.findByName(cfg.getName()).isPresent()) {
            throw new IllegalArgumentException("配置名已存在: " + cfg.getName());
        }
        if (cfg.isDefault() && !cfg.isEnabled()) {
            throw new IllegalArgumentException("默认模型必须处于启用状态");
        }
        if (cfg.isDefault()) {
            repository.clearAllDefaults();
        }
        AiModelConfig saved = repository.save(cfg);
        modelRouter.refresh();
        log.info("ai model config created: {} ({} -> {})", saved.getName(), saved.getProvider(), saved.getModelName());
        return saved;
    }

    @Transactional
    public AiModelConfig update(Long id, AiModelConfig patch) {
        AiModelConfig cfg = require(id);
        validate(patch);
        Optional<AiModelConfig> sameName = repository.findByName(patch.getName());
        if (sameName.isPresent() && !sameName.get().getId().equals(id)) {
            throw new IllegalArgumentException("配置名已存在: " + patch.getName());
        }
        if (patch.isDefault() && !patch.isEnabled()) {
            throw new IllegalArgumentException("默认模型必须处于启用状态");
        }
        if (patch.isDefault() && !cfg.isDefault()) {
            repository.clearAllDefaults();
        }
        cfg.setName(patch.getName());
        cfg.setProvider(patch.getProvider());
        cfg.setBaseUrl(patch.getBaseUrl());
        // apiKey 留空 = 保持原值
        if (patch.getApiKey() != null && !patch.getApiKey().isBlank()) {
            cfg.setApiKey(patch.getApiKey());
        }
        cfg.setModelName(patch.getModelName());
        cfg.setTemperature(patch.getTemperature());
        cfg.setEnabled(patch.isEnabled());
        cfg.setDefault(patch.isDefault());
        cfg.setUpdatedAt(java.time.OffsetDateTime.now());
        AiModelConfig saved = repository.save(cfg);
        modelRouter.refresh();
        log.info("ai model config updated: {} (enabled={}, default={})", saved.getName(), saved.isEnabled(), saved.isDefault());
        return saved;
    }

    @Transactional
    public void remove(Long id) {
        AiModelConfig cfg = require(id);
        repository.delete(cfg);
        modelRouter.refresh();
        log.info("ai model config removed: {}", cfg.getName());
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        AiModelConfig cfg = require(id);
        if (!enabled && cfg.isDefault()) {
            throw new IllegalArgumentException("请先取消默认模型,或先设置新的默认模型");
        }
        cfg.setEnabled(enabled);
        cfg.setUpdatedAt(java.time.OffsetDateTime.now());
        repository.save(cfg);
        modelRouter.refresh();
        log.info("ai model config {} set enabled={}", cfg.getName(), enabled);
    }

    @Transactional
    public void setDefault(Long id) {
        AiModelConfig cfg = require(id);
        if (!cfg.isEnabled()) {
            throw new IllegalArgumentException("请先启用该模型,再设为默认");
        }
        repository.clearAllDefaults();
        cfg.setDefault(true);
        cfg.setUpdatedAt(java.time.OffsetDateTime.now());
        repository.save(cfg);
        modelRouter.refresh();
        log.info("ai model config {} set as default", cfg.getName());
    }

    /** 测试连接:用该配置构建客户端发最小请求,验证 base-url + api-key + model 三要素。 */
    public Map<String, Object> test(Long id) {
        AiModelConfig cfg = require(id);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ChatClient client = modelRouter.buildChatClient(cfg, Duration.ofSeconds(10));
            String reply = client.prompt().user("ping").call().content();
            result.put("ok", true);
            result.put("message", "连接成功: " + (reply == null ? "(空响应)" : reply.trim()));
            log.info("ai model config test OK: {} (model={})", cfg.getName(), cfg.getModelName());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result.put("ok", false);
            result.put("message", "连接失败: " + msg);
            log.warn("ai model config test failed: {} ({}): {}", cfg.getName(), cfg.getModelName(), msg);
        }
        return result;
    }

    /** 当前生效模型信息(前端顶部提示条)。 */
    public Map<String, Object> status() {
        Optional<AiModelConfig> cfg = repository.findByEnabledTrueAndIsDefaultTrue();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", cfg.isPresent());
        status.put("model", cfg.map(AiModelConfig::getModelName).orElse(modelRouter.getModelName()));
        status.put("provider", cfg.map(AiModelConfig::getProvider).orElse(null));
        status.put("baseUrl", cfg.map(AiModelConfig::getBaseUrl).orElse(null));
        status.put("name", cfg.map(AiModelConfig::getName).orElse(null));
        status.put("usable", modelRouter.hasUsableModel());
        return status;
    }

    private AiModelConfig require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模型配置不存在: id=" + id));
    }

    private void validate(AiModelConfig cfg) {
        if (cfg.getName() == null || cfg.getName().isBlank()) {
            throw new IllegalArgumentException("配置名不能为空");
        }
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        if (!cfg.getBaseUrl().startsWith("http://") && !cfg.getBaseUrl().startsWith("https://")) {
            throw new IllegalArgumentException("Base URL 需以 http:// 或 https:// 开头");
        }
        if (cfg.getModelName() == null || cfg.getModelName().isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
    }

    /** 列表展示视图:apiKey 脱敏。 */
    private Map<String, Object> toView(AiModelConfig cfg) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", cfg.getId());
        view.put("name", cfg.getName());
        view.put("provider", cfg.getProvider());
        view.put("baseUrl", cfg.getBaseUrl());
        view.put("apiKey", maskApiKey(cfg.getApiKey()));
        view.put("modelName", cfg.getModelName());
        view.put("temperature", cfg.getTemperature());
        view.put("enabled", cfg.isEnabled());
        view.put("default", cfg.isDefault());
        view.put("updatedAt", cfg.getUpdatedAt());
        return view;
    }

    /** sk-abc12345 -> sk-****2345;短 key 全掩码。 */
    static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        String prefix = apiKey.substring(0, Math.min(3, apiKey.length() / 2));
        String suffix = apiKey.substring(apiKey.length() - 4);
        return prefix + "****" + suffix;
    }
}
