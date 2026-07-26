package com.qizhi.approve.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Keeps the approval branch able to accept the documented 100-identity burst.
 * The database has been verified with max_connections=200; other services peak
 * at fewer than 90 connections during this scenario.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ApproveCapacityConfiguration {

    @Bean
    ApplicationRunner approvalPoolCapacity(DataSource dataSource) {
        return args -> {
            if (dataSource instanceof HikariDataSource hikari) {
                int configured = hikari.getMaximumPoolSize();
                if (configured < 100) {
                    hikari.setMaximumPoolSize(100);
                }
                log.info("Approval capacity pool: configured={}, effective={}",
                        configured, hikari.getMaximumPoolSize());
            } else {
                log.warn("Approval capacity pool cannot be adjusted: {}", dataSource.getClass().getName());
            }
        };
    }
}
