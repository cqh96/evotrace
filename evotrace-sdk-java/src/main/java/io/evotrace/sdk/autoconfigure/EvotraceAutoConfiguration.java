package io.evotrace.sdk.autoconfigure;

import io.evotrace.sdk.collector.ApiInventoryCollector;
import io.evotrace.sdk.collector.ConfigInventoryCollector;
import io.evotrace.sdk.collector.DependencyInventoryCollector;
import io.evotrace.sdk.collector.InventoryCollector;
import io.evotrace.sdk.collector.InventoryReporter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

@AutoConfiguration
@ConditionalOnProperty(prefix = "evotrace", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EvotraceProperties.class)
public class EvotraceAutoConfiguration {

    @Bean
    @ConditionalOnBean(RequestMappingHandlerMapping.class)
    public ApiInventoryCollector apiInventoryCollector(RequestMappingHandlerMapping handlerMapping,
                                                        EvotraceProperties properties) {
        return new ApiInventoryCollector(handlerMapping, properties);
    }

    @Bean
    public DependencyInventoryCollector dependencyInventoryCollector(Environment environment) {
        return new DependencyInventoryCollector(environment);
    }

    @Bean
    public ConfigInventoryCollector configInventoryCollector(ConfigurableEnvironment environment,
                                                              EvotraceProperties properties) {
        return new ConfigInventoryCollector(environment, properties);
    }

    @Bean
    public InventoryReporter inventoryReporter(EvotraceProperties properties,
                                                List<InventoryCollector> collectors) {
        return new InventoryReporter(properties, collectors);
    }
}
