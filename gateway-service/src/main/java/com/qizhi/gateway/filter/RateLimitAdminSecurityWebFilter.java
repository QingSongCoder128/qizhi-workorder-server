package com.qizhi.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qizhi.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Spring Cloud Gateway GlobalFilter only wraps routed exchanges. The rate-limit
 * admin endpoint is a local WebFlux controller, so it needs this explicit guard.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class RateLimitAdminSecurityWebFilter implements WebFilter {

    private static final String PATH = "/api/v1/admin/config/rate-limit";
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getURI().getPath().startsWith(PATH)) {
            return chain.filter(exchange);
        }
        if (exchange.getRequest().getMethod() != null
                && "OPTIONS".equals(exchange.getRequest().getMethod().name())) {
            return chain.filter(exchange);
        }
        String sessionId = exchange.getRequest().getHeaders().getFirst("X-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            return deny(exchange, HttpStatus.UNAUTHORIZED, R.unauthorized("未登录"));
        }
        return redisTemplate.opsForValue().get("session:" + sessionId)
                .flatMap(value -> {
                    if (value instanceof Map<?, ?> session) {
                        // 基于权限码判断（动态，不再硬编码角色）
                        List<String> permissions = Collections.emptyList();
                        Object permObj = session.get("permissions");
                        if (permObj instanceof List<?> permList) {
                            @SuppressWarnings("unchecked")
                            List<String> casted = (List<String>) permList;
                            permissions = casted;
                        }
                        if (permissions.contains("config:manage")) {
                            return chain.filter(exchange);
                        }
                    }
                    return deny(exchange, HttpStatus.FORBIDDEN, R.forbidden("无权访问限流配置"));
                })
                .switchIfEmpty(Mono.defer(() ->
                        deny(exchange, HttpStatus.UNAUTHORIZED, R.unauthorized("会话已过期"))));
    }

    private Mono<Void> deny(ServerWebExchange exchange, HttpStatus status, R<Void> body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap(bytes)));
        } catch (Exception exception) {
            return exchange.getResponse().setComplete();
        }
    }
}
