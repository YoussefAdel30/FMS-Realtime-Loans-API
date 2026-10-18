package com.company.msisdn;

import com.company.msisdn.config.ApiProperties;
import com.company.msisdn.config.AuditProperties;
import com.company.msisdn.config.BlacklistProperties;
import com.company.msisdn.config.CsvExportProperties;
import com.company.msisdn.config.MonitoringProperties;
import com.company.msisdn.config.OracleAuditProperties;
import com.company.msisdn.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
@EnableConfigurationProperties({
        ApiProperties.class,
        BlacklistProperties.class,
        AuditProperties.class,
        OracleAuditProperties.class,
        CsvExportProperties.class,
        MonitoringProperties.class,
        SecurityProperties.class
})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
