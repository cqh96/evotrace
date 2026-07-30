package io.evotrace.sdk.collector;

import io.evotrace.protocol.payload.InventoryReportPayload;
import io.evotrace.sdk.autoconfigure.EvotraceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Collects configuration inventory from Spring Environment property sources.
 * Sensitive values (matching sensitiveKeyPattern) are SHA-256 hashed instead of
 * reporting plain text.
 */
public class ConfigInventoryCollector implements InventoryCollector {

    private static final Logger log = LoggerFactory.getLogger(ConfigInventoryCollector.class);

    private final ConfigurableEnvironment environment;
    private final Pattern sensitivePattern;

    public ConfigInventoryCollector(ConfigurableEnvironment environment, EvotraceProperties properties) {
        this.environment = environment;
        this.sensitivePattern = Pattern.compile(properties.getSensitiveKeyPattern());
    }

    @Override
    public String category() {
        return "CONFIG";
    }

    @Override
    public InventoryReportPayload collect() {
        Map<String, String> fingerprints = new HashMap<>();

        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (!(ps instanceof EnumerablePropertySource<?> eps)) continue;
            if (ps.getName().contains("systemEnvironment") || ps.getName().contains("systemProperties")) {
                // Skip system-level properties — too many and not app-specific
                continue;
            }

            String prefix = ps.getName().replaceAll("[^a-zA-Z0-9]", "_");
            int count = 0;
            for (String key : eps.getPropertyNames()) {
                if (++count > 100) break; // limit per source to avoid explosion
                Object raw = eps.getProperty(key);
                if (raw == null) continue;

                String value = raw.toString();
                String fingerprintKey = prefix + "." + key;

                if (sensitivePattern.matcher(key).matches()) {
                    fingerprints.put(fingerprintKey, "sha256:" + sha256(value));
                } else if (value.length() < 200) {
                    fingerprints.put(fingerprintKey, value);
                } else {
                    fingerprints.put(fingerprintKey, "(truncated:" + value.length() + " chars)");
                }
            }
        }

        log.info("config inventory collected: {} keys (from {} sources)",
                fingerprints.size(), environment.getPropertySources().size());
        return new InventoryReportPayload(null, null, null, List.of(), List.of(), fingerprints, null);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "hash-error";
        }
    }
}
