package io.evotrace.server.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpClient;
import io.evotrace.server.ai.config.AiModelConfig;
import io.evotrace.server.ai.config.AiModelConfigRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes AI tasks to the configured ChatModel.
 *
 * <p>模型配置来自 ai_model_config 表(系统页面维护):
 * <ul>
 *   <li>存在「启用 + 默认」的配置时,动态构建对应的 OpenAI 兼容客户端(火山方舟/DeepSeek/自定义网关)</li>
 *   <li>无配置或构建失败时,回退到 application.yml 构建的 ChatModel bean(保持历史行为)</li>
 * </ul>
 * 配置变更后调用 {@link #refresh()} 即时生效,无需重启。</p>
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final ChatModel fallbackModel;
    private final String fallbackModelName;
    private final String fallbackApiKey;
    private final AiModelConfigRepository configRepository;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    private volatile ChatClient activeClient;
    private volatile String activeModelName;
    private volatile String activeBaseUrl;
    private volatile String activeApiKey;

    /** 按配置 ID 构建的 client 缓存(如问答页选择指定模型),配置变更时由 refresh 清空。 */
    private final ConcurrentHashMap<Long, ChatClient> configClients = new ConcurrentHashMap<>();

    public ModelRouter(ChatModel fallbackModel,
                       AiModelConfigRepository configRepository,
                       ObjectProvider<ObservationRegistry> observationRegistry,
                       ObjectProvider<MeterRegistry> meterRegistry,
                       @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String fallbackModelName,
                       @Value("${spring.ai.openai.api-key:}") String fallbackApiKey) {
        this.fallbackModel = fallbackModel;
        this.configRepository = configRepository;
        this.observationRegistry = observationRegistry.getIfUnique();
        this.meterRegistry = meterRegistry.getIfUnique();
        this.fallbackModelName = fallbackModelName;
        this.fallbackApiKey = fallbackApiKey;
    }

    @PostConstruct
    public void load() {
        Optional<AiModelConfig> cfg = configRepository.findByEnabledTrueAndIsDefaultTrue();
        if (cfg.isPresent() && cfg.get().getBaseUrl() != null && !cfg.get().getBaseUrl().isBlank()) {
            try {
                ChatClient client = buildChatClient(cfg.get(), Duration.ofSeconds(30));
                this.activeClient = client;
                this.activeModelName = cfg.get().getModelName();
                this.activeBaseUrl = normalizeBaseUrl(cfg.get().getBaseUrl());
                this.activeApiKey = cfg.get().getApiKey();
                log.info("ModelRouter: active model '{}' from config '{}' ({}), base {}",
                        activeModelName, cfg.get().getName(), cfg.get().getProvider(), activeBaseUrl);
                return;
            } catch (Exception e) {
                log.warn("ModelRouter: failed to build client for config '{}', falling back to yml model '{}': {}",
                        cfg.get().getName(), fallbackModelName, e.getMessage());
            }
        }
        this.activeClient = ChatClient.builder(fallbackModel).build();
        this.activeModelName = fallbackModelName;
        this.activeBaseUrl = null;
        this.activeApiKey = fallbackApiKey;
        log.info("ModelRouter: no enabled default config, using yml fallback model '{}'", fallbackModelName);
    }

    /** 配置变更后调用,立即重建客户端(写操作由 AiModelConfigService 触发)。 */
    public void refresh() {
        configClients.clear();
        load();
    }

    /**
     * 按配置 ID 获取 ChatClient(问答等交互场景指定模型)。
     * 配置不存在/未启用/构建失败时回退当前生效模型;client 按配置缓存,变更时失效。
     */
    public ChatClient clientForConfig(Long configId) {
        if (configId == null) {
            return activeClient;
        }
        ChatClient cached = configClients.get(configId);
        if (cached != null) {
            return cached;
        }
        AiModelConfig cfg = configRepository.findById(configId).orElse(null);
        if (cfg == null || !cfg.isEnabled()) {
            log.warn("ModelRouter: config {} not found or disabled, using active model", configId);
            return activeClient;
        }
        try {
            ChatClient client = buildChatClient(cfg, Duration.ofSeconds(60));
            configClients.put(configId, client);
            log.info("ModelRouter: built client for config {} '{}' (model {})", configId, cfg.getName(), cfg.getModelName());
            return client;
        } catch (Exception e) {
            log.warn("ModelRouter: failed to build client for config {}, using active model: {}", configId, e.getMessage());
            return activeClient;
        }
    }

    /**
     * Get a ChatClient for the given task type.
     * 当前为单一默认模型路由;后续可按 taskType 扩展多模型路由。
     */
    public ChatClient clientFor(String taskType) {
        return activeClient;
    }

    public String getModelName() {
        return activeModelName;
    }

    /** 当前生效的模型是否配置了可用 API Key(用于 fallback 判断,如发布说明模板回退)。 */
    public boolean hasUsableModel() {
        return activeApiKey != null && !activeApiKey.isBlank() && !"sk-placeholder".equals(activeApiKey);
    }

    /**
     * 按配置动态构建 ChatClient(OpenAI 兼容协议)。
     * 与 Spring AI autoconfigure 的 OpenAiChatAutoConfiguration 构建链一致
     * (SpringAiOpenAiHttpClient → ClientOptions → OpenAIClientImpl → OpenAiChatModel),
     * 但绕开 OpenAiSetup —— 其 apiKey 参数在 2.0.0 存在已知问题(构建时丢失 credential)。
     * 供运行时路由与「测试连接」复用;timeout 由调用方决定(测试连接用短超时)。
     */
    public ChatClient buildChatClient(AiModelConfig cfg, Duration timeout) {
        HttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .observationRegistry(observationRegistry)
                .meterRegistry(meterRegistry)
                .timeout(timeout)
                .build();
        ClientOptions.Builder optionsBuilder = ClientOptions.builder()
                .httpClient(httpClient)
                .baseUrl(normalizeBaseUrl(cfg.getBaseUrl()))
                .timeout(timeout)
                .maxRetries(1)
                .putHeader("User-Agent", "spring-ai-openai");
        if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
            optionsBuilder.apiKey(cfg.getApiKey());
        }
        ClientOptions options = optionsBuilder.build();
        // OpenAiChatModel 同时需要 sync + async client:
        // 只传 sync 时,内部兜底创建的 async client 会丢失 apiKey 配置
        OpenAIClient openAiClient = new OpenAIClientImpl(options);
        OpenAIClientAsync openAiClientAsync = new OpenAIClientAsyncImpl(options);
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(cfg.getModelName())
                .temperature(cfg.getTemperature() != null ? cfg.getTemperature().doubleValue()
                        : new BigDecimal("0.20").doubleValue())
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(chatOptions)
                .build();
        return ChatClient.builder(model).build();
    }

    /** 统一去掉尾部斜杠,避免 base-url 拼接出双斜杠。 */
    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
