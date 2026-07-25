package com.qizhi.workorder.config;

import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenFeign 全局配置
 * <p>
 * SRS 需求: WO-09 Feign 配置：超时时间 3 秒、失败重试 1 次
 * 超时在 application.yml 中配置（connect-timeout / read-timeout = 3000ms），
 * 重试策略在此配置：初始间隔 100ms，最大间隔 1s，最多重试 1 次（共执行 2 次）。
 * </p>
 */
@Configuration
public class FeignConfig {

    /**
     * Feign 重试器：失败后重试 1 次
     * period=100ms（初始重试间隔），maxPeriod=1000ms（最大间隔），maxAttempts=2（总尝试次数=1+1次重试）
     */
    @Bean
    public Retryer feignRetryer() {
        return new Retryer.Default(100, 1000, 2);
    }
}
