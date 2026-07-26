package com.qizhi.common.core.security;

import com.qizhi.common.core.constant.CommonConstants;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.net.URI;
import java.util.UUID;

/**
 * 为所有 Feign 请求添加不可伪造的服务身份。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RequestInterceptor.class)
public class InternalCallFeignConfiguration {

    @Bean
    RequestInterceptor internalCallRequestInterceptor(
            @Value("${spring.application.name:unknown-service}") String caller,
            @Value("${security.internal.secret:${INTERNAL_CALL_SECRET:}}") String secret) {
        return template -> {
            String timestamp = String.valueOf(Instant.now().toEpochMilli());
            String nonce = UUID.randomUUID().toString();
            String basePath = "";
            if (template.feignTarget() != null && template.feignTarget().url() != null) {
                try {
                    basePath = URI.create(template.feignTarget().url()).getPath();
                } catch (IllegalArgumentException ignored) {
                    // A discovery-only target may not be a complete URI.
                }
            }
            String relativePath = template.path();
            String path = InternalCallSignature.normalizePath(
                    (basePath == null ? "" : basePath)
                            + (relativePath.startsWith("/") ? "" : "/") + relativePath);
            String signature = InternalCallSignature.sign(
                    secret, caller, timestamp, nonce, template.method(), path);
            template.header(CommonConstants.HEADER_INTERNAL_CALLER, caller);
            template.header(CommonConstants.HEADER_INTERNAL_TIMESTAMP, timestamp);
            template.header(CommonConstants.HEADER_INTERNAL_NONCE, nonce);
            template.header(CommonConstants.HEADER_INTERNAL_PATH, path);
            template.header(CommonConstants.HEADER_INTERNAL_SIGNATURE, signature);
        };
    }
}
