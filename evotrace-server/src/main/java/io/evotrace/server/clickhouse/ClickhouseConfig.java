package io.evotrace.server.clickhouse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * ClickHouse 分析库配置（V2.5）。
 * <p>
 * 仅当 {@code evotrace.clickhouse.enabled=true} 时激活，未配置/禁用时优雅降级，
 * 所有事件/分析查询仍走 PostgreSQL，不影响主链路。
 */
@Configuration
@ConditionalOnProperty(prefix = "evotrace.clickhouse", name = "enabled", havingValue = "true")
public class ClickhouseConfig {

    @Bean
    public DataSource clickhouseDataSource(
            @Value("${evotrace.clickhouse.url}") String url,
            @Value("${evotrace.clickhouse.username}") String username,
            @Value("${evotrace.clickhouse.password}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    public JdbcTemplate clickhouseJdbcTemplate(DataSource clickhouseDataSource) {
        JdbcTemplate template = new JdbcTemplate(clickhouseDataSource);
        template.setQueryTimeout(30);
        return template;
    }
}