package com.company.msisdn.oracle;

import com.company.msisdn.config.OracleAuditProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Configuration
@ConditionalOnProperty(prefix = "oracle-audit", name = "enabled", havingValue = "true")
public class OracleConfiguration {

    @Bean(destroyMethod = "close")
    public HikariDataSource oracleDataSource(OracleAuditProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(properties.pool().maximumPoolSize());
        config.setMinimumIdle(properties.pool().minimumIdle());
        config.setConnectionTimeout(properties.pool().connectionTimeoutMs());
        config.setIdleTimeout(properties.pool().idleTimeoutMs());
        config.setMaxLifetime(properties.pool().maxLifetimeMs());
        config.setValidationTimeout(properties.pool().validationTimeoutMs());
        config.setKeepaliveTime(properties.pool().keepaliveTimeMs());
        config.setInitializationFailTimeout(properties.pool().initializationFailTimeoutMs());
        config.setConnectionInitSql("ALTER SESSION SET TIME_ZONE = 'UTC'");
        config.setPoolName("msisdn-oracle");
        config.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", Integer.toString(properties.network().connectTimeoutMs()));
        config.addDataSourceProperty("oracle.jdbc.ReadTimeout", Integer.toString(properties.network().readTimeoutMs()));
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate oracleJdbcTemplate(DataSource oracleDataSource, OracleAuditProperties properties) {
        JdbcTemplate template = new JdbcTemplate(oracleDataSource);
        template.setQueryTimeout(properties.network().queryTimeoutSeconds());
        return template;
    }

    @Bean
    public DataSourceTransactionManager oracleTransactionManager(DataSource oracleDataSource) {
        return new DataSourceTransactionManager(oracleDataSource);
    }

    @Bean
    public OracleAuditSql oracleAuditSql(OracleAuditProperties properties) {
        return new OracleAuditSql(properties);
    }
}
