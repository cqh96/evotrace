package io.evotrace.sdk.collector;

import io.evotrace.protocol.payload.InventoryReportPayload;
import io.evotrace.sdk.autoconfigure.EvotraceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Scans Spring MVC RequestMappingHandlerMapping to produce an API inventory.
 * Extracts HTTP method, path, and a signature fingerprint of parameters and return type.
 *
 * <p>使用 ObjectProvider 懒获取 handlerMapping:构造器直注会在自动配置阶段强制提前
 * 初始化 MVC 核心 bean,可能把应用自身的 bean 循环依赖(如字段注入循环)暴露成启动错误。
 * 采集发生在应用就绪后,届时 handlerMapping 必然可用。</p>
 */
public class ApiInventoryCollector implements InventoryCollector {

    private static final Logger log = LoggerFactory.getLogger(ApiInventoryCollector.class);

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
    private final EvotraceProperties properties;

    public ApiInventoryCollector(ObjectProvider<RequestMappingHandlerMapping> handlerMapping,
                                 EvotraceProperties properties) {
        this.handlerMappingProvider = handlerMapping;
        this.properties = properties;
    }

    @Override
    public String category() {
        return "API";
    }

    @Override
    public InventoryReportPayload collect() {
        RequestMappingHandlerMapping handlerMapping = handlerMappingProvider.getIfAvailable();
        if (handlerMapping == null) {
            log.warn("api inventory skipped: RequestMappingHandlerMapping not available");
            return new InventoryReportPayload(null, null, null, List.of(), List.of(), null, null);
        }
        Map<RequestMappingInfo, HandlerMethod> mappings = handlerMapping.getHandlerMethods();
        List<InventoryReportPayload.ApiItem> apis = new ArrayList<>();

        for (var entry : mappings.entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            Method method = handler.getMethod();

            String sigFingerprint = fingerprint(method);

            for (var path : info.getDirectPaths()) {
                info.getMethodsCondition().getMethods().forEach(httpMethod -> {
                    apis.add(new InventoryReportPayload.ApiItem(
                            httpMethod.name(),
                            path,
                            sigFingerprint,
                            "" // schema fingerprint left for future OpenAPI generation
                    ));
                });
            }
        }

        log.info("api inventory collected: {} endpoints", apis.size());
        return new InventoryReportPayload(null, null, null, apis, List.of(), null, null);
    }

    private String fingerprint(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getReturnType().getName()).append("|");
        for (Parameter p : method.getParameters()) {
            sb.append(p.getType().getName()).append(",");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }
}
