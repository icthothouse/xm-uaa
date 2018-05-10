package com.icthh.xm.uaa.config;

import io.github.jhipster.config.JHipsterConstants;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.config.java.AbstractCloudConfig;
import org.springframework.context.annotation.*;

import javax.sql.DataSource;

@Configuration
@Profile(JHipsterConstants.SPRING_PROFILE_CLOUD)
@Slf4j
public class CloudDatabaseConfiguration extends AbstractCloudConfig {

    @Bean
    public DataSource dataSource() {
        log.info("Configuring JDBC datasource from a cloud provider");
        return connectionFactory().dataSource();
    }
}
