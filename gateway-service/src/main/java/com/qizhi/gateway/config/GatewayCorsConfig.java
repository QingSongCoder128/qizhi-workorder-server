package com.qizhi.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Gateway 全局跨域配置
 * <p>
 * SRS 需求: GW-02 路由配置 + 兼容性需求
 * 所有跨域参数均从 Nacos 读取，支持热更新（@RefreshScope）。
 * </p>
 */
@Configuration
@RefreshScope
public class GatewayCorsConfig {

    /** 允许的源模式，Nacos: gateway.cors.allowed-origins，默认 * */
    @Value("${gateway.cors.allowed-origins:*}")
    private String allowedOrigins;

    /** 预检请求缓存时间（秒），Nacos: gateway.cors.max-age，默认 3600 */
    @Value("${gateway.cors.max-age:3600}")
    private long corsMaxAge;

    /** 是否允许携带 Cookie，Nacos: gateway.cors.allow-credentials，默认 true */
    @Value("${gateway.cors.allow-credentials:true}")
    private boolean allowCredentials;

    /**
     * 创建跨域过滤器
     */
    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 允许的来源（从 Nacos 读取，支持多个逗号分隔）
        for (String origin : allowedOrigins.split(",")) {
            config.addAllowedOriginPattern(origin.trim());
        }

        // 允许所有 HTTP 方法（GET/POST/PUT/DELETE/OPTIONS 等）
        config.addAllowedMethod("*");

        // 允许所有请求头（包括自定义的 X-Session-Id、X-User-Id 等）
        config.addAllowedHeader("*");

        // 暴露响应头给前端 JavaScript 读取
        config.addExposedHeader("X-Session-Id");
        config.addExposedHeader("X-Trace-Id");

        // 允许携带 Cookie
        config.setAllowCredentials(allowCredentials);

        // 预检请求缓存时间（秒）
        config.setMaxAge(corsMaxAge);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}
