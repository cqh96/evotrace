package io.evotrace.sdk.collector;

import io.evotrace.protocol.payload.InventoryReportPayload;
import io.evotrace.sdk.autoconfigure.EvotraceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
public class ApiInventoryCollector implements InventoryCollector {

    private static final Logger log = LoggerFactory.getLogger(ApiInventoryCollector.class);

    private final RequestMappingHandlerMapping handlerMapping;
    private final EvotraceProperties properties;

    public ApiInventoryCollector(RequestMappingHandlerMapping handlerMapping, EvotraceProperties properties) {
        this.handlerMapping = handlerMapping;
        this.properties = properties;
    }

    @Override
    public String category() {
        return "API";
    }

    @Override
    public InventoryReportPayload collect() {
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
