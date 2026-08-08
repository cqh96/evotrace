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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

/**
 * EvoTrace 自动配置。
 * <p>after = WebMvcAutoConfiguration:避免本 jar 的自动配置类插队改变宿主应用
 * (如 yudao)的 bean 创建顺序——宿主自身存在字段注入循环(permission↔role 等),
 * 依赖创建顺序巧合才能解析,过早注册 MVC 相关 bean 会把循环暴露成启动错误。</p>
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration")
@ConditionalOnProperty(prefix = "evotrace", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EvotraceProperties.class)
public class EvotraceAutoConfiguration {

    @Bean
    @Lazy
    @ConditionalOnBean(RequestMappingHandlerMapping.class)
    public ApiInventoryCollector apiInventoryCollector(ObjectProvider<RequestMappingHandlerMapping> handlerMapping,
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
