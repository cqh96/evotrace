package io.evotrace.sdk.collector;

import io.evotrace.protocol.payload.InventoryReportPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects dependency inventory from the Spring Boot application environment
 * and commonly known framework version properties.
 */
public class DependencyInventoryCollector implements InventoryCollector {

    private static final Logger log = LoggerFactory.getLogger(DependencyInventoryCollector.class);

    private final Environment environment;

    public DependencyInventoryCollector(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String category() {
        return "DEPENDENCY";
    }

    @Override
    public InventoryReportPayload collect() {
        List<InventoryReportPayload.DependencyItem> deps = new ArrayList<>();

        // Read major framework versions from the Spring environment
        String springVersion = environment.getProperty("spring-framework.version");
        if (springVersion != null) {
            deps.add(new InventoryReportPayload.DependencyItem(
                    "org.springframework", "spring-core", springVersion, "compile"));
        }

        String bootVersion = environment.getProperty("java.runtime.version");
        if (bootVersion != null) {
            deps.add(new InventoryReportPayload.DependencyItem(
                    "java", "jdk", bootVersion, "runtime"));
        }

        // Detect infra dependencies from active profiles
        for (var propName : environment.getActiveProfiles()) {
            if (propName.contains("mysql") || propName.contains("postgres") || propName.contains("redis")) {
                deps.add(new InventoryReportPayload.DependencyItem(
                        "infra", propName, "detected", "runtime"));
            }
        }

        log.info("dependency inventory collected: {} items", deps.size());
        return new InventoryReportPayload(null, null, null, List.of(), deps, null, null);
    }
}
