package io.evotrace.sdk.collector;

import io.evotrace.protocol.payload.InventoryReportPayload;
import io.evotrace.sdk.autoconfigure.EvotraceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

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
            List<Map<String, Object>> params = collectParams(method);
            Map<String, Object> requestSchema = collectRequestSchema(method);
            Map<String, Object> responseSchema = collectResponseSchema(method);
            List<String> tags = List.of(handler.getBeanType().getSimpleName()
                    .replace("Controller", "").replace("Api", ""));

            for (var path : info.getDirectPaths()) {
                info.getMethodsCondition().getMethods().forEach(httpMethod -> {
                    apis.add(new InventoryReportPayload.ApiItem(
                            httpMethod.name(),
                            path,
                            sigFingerprint,
                            "",
                            method.getName(),
                            tags,
                            params,
                            requestSchema,
                            responseSchema
                    ));
                });
            }
        }

        log.info("api inventory collected: {} endpoints", apis.size());
        return new InventoryReportPayload(null, null, null, apis, List.of(), null, null);
    }

    /** Collect path/query params from annotated method parameters. */
    private List<Map<String, Object>> collectParams(Method method) {
        List<Map<String, Object>> params = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            PathVariable pv = p.getAnnotation(PathVariable.class);
            if (pv != null) {
                params.add(Map.of("name", pv.value().isEmpty() ? p.getName() : pv.value(),
                        "in", "path", "required", true, "type", typeName(p.getType()), "desc", ""));
                continue;
            }
            RequestParam rp = p.getAnnotation(RequestParam.class);
            if (rp != null) {
                params.add(Map.of("name", rp.value().isEmpty() ? p.getName() : rp.value(),
                        "in", "query", "required", rp.required(), "type", typeName(p.getType()), "desc", ""));
            }
        }
        return params;
    }

    /** Collect a minimal JSON schema for the @RequestBody parameter. */
    private Map<String, Object> collectRequestSchema(Method method) {
        for (Parameter p : method.getParameters()) {
            if (p.getAnnotation(RequestBody.class) != null) {
                return schemaOf(p.getParameterizedType());
            }
        }
        return null;
    }

    /** Collect a minimal JSON schema for the response type. */
    private Map<String, Object> collectResponseSchema(Method method) {
        Type returnType = method.getGenericReturnType();
        if (returnType == void.class || returnType == Void.class) return null;
        return schemaOf(returnType);
    }

    private Map<String, Object> schemaOf(Type type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (type instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if ("java.util.List".equals(raw.getTypeName()) && pt.getActualTypeArguments().length > 0) {
                schema.put("type", "array");
                schema.put("items", schemaOf(pt.getActualTypeArguments()[0]));
                return schema;
            }
        }
        if (type instanceof Class<?> c) {
            if (c.isPrimitive() || c == String.class || c == Integer.class || c == Long.class
                    || c == Double.class || c == Float.class || c == Boolean.class
                    || c == Short.class || c == Byte.class || c == Character.class) {
                schema.put("type", jsonType(c));
                return schema;
            }
            if (c.isEnum()) {
                schema.put("type", "string");
                return schema;
            }
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                props.put(f.getName(), schemaOf(f.getGenericType()));
            }
            if (!props.isEmpty()) schema.put("properties", props);
            return schema;
        }
        schema.put("type", "object");
        return schema;
    }

    private String typeName(Class<?> c) {
        if (c.isPrimitive() || c == String.class || c == Integer.class || c == Long.class
                || c == Double.class || c == Float.class || c == Boolean.class) {
            return jsonType(c);
        }
        return "object";
    }

    private String jsonType(Class<?> c) {
        if (c == int.class || c == long.class || c == Integer.class || c == Long.class
                || c == double.class || c == float.class || c == Double.class || c == Float.class
                || c == short.class || c == byte.class || c == Short.class || c == Byte.class) {
            return "number";
        }
        if (c == boolean.class || c == Boolean.class) return "boolean";
        return "string";
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
