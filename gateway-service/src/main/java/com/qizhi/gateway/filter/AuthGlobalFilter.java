package com.qizhi.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qizhi.common.core.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 全局鉴权过滤器
 * 校验 X-Session-Id，从 Redis 读取会话信息注入请求头
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RefreshScope
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 白名单路径（无需鉴权） */
    private static final List<String> WHITE_LIST = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register"
    );

    /**
     * 管理端路径前缀（仅 ADMIN 角色可访问）
     * SRS 需求: GW-14 解析会话中权限集合，拦截无权限接口访问
     * 从 Nacos 读取，支持热更新
     */
    @Value("#{'${gateway.admin-paths:/api/v1/user/list,/api/v1/user/create,/api/v1/dept,/api/v1/role,/api/v1/approve/template,/api/v1/message/dlq,/api/v1/message/dead-letter,/api/v1/stats/refresh}'.split(',')}")
    private List<String> adminPaths;

    /** 会话 Redis Key 前缀 */
    private static final String SESSION_PREFIX = "session:";

    /** 会话续期时长（分钟），从 Nacos 读取，默认 30 */
    @Value("${gateway.session-expire-minutes:30}")
    private int sessionExpireMinutes;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 白名单放行
        for (String white : WHITE_LIST) {
            if (path.startsWith(white)) {
                return chain.filter(exchange);
            }
        }

        // 校验 Session
        String sessionId = exchange.getRequest().getHeaders().getFirst("X-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("未携带 Session，拦截请求: {}", path);
            return writeUnauthorizedResponse(exchange, "未登录，请重新登录");
        }

        // 从 Redis 读取 session 并验证
        String sessionKey = SESSION_PREFIX + sessionId;
        log.debug("准备从Redis读取session: key={}", sessionKey);
        return reactiveRedisTemplate.opsForValue().get(sessionKey)
                .doOnNext(sessionObj -> log.debug("Redis返回值: type={}, value={}",
                        sessionObj != null ? sessionObj.getClass().getName() : "null", sessionObj))
                .doOnError(err -> log.error("Redis读取异常: {}", err.getMessage(), err))
                .flatMap(sessionObj -> {
                    // session 存在，解析并注入请求头
                    log.info("Session查找成功, 数据类型: {}", sessionObj.getClass().getSimpleName());
                    if (sessionObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sessionData = (Map<String, Object>) sessionObj;

                        String userId = String.valueOf(sessionData.getOrDefault("userId", ""));
                        String username = String.valueOf(sessionData.getOrDefault("username", ""));
                        String role = String.valueOf(sessionData.getOrDefault("role", ""));

                        // GW-14: 角色权限校验 — 员工禁止访问管理端接口
                        if (isAdminPath(path) && !"ADMIN".equals(role)) {
                            log.warn("权限不足，拦截请求: role={}, path={}", role, path);
                            return writeForbiddenResponse(exchange, "无权限访问管理端接口");
                        }

                        // 构建新请求，注入用户信息到请求头
                        ServerHttpRequest newRequest = exchange.getRequest().mutate()
                                .header("X-User-Id", userId)
                                .header("X-Username", username)
                                .header("X-User-Role", role)
                                .build();

                        // 自动续期会话
                        reactiveRedisTemplate.expire(sessionKey, Duration.ofMinutes(sessionExpireMinutes))
                                .subscribe();

                        return chain.filter(exchange.mutate().request(newRequest).build());
                    } else {
                        log.warn("Session 数据格式异常: {}, 实际类型: {}", path, sessionObj.getClass().getName());
                        return writeUnauthorizedResponse(exchange, "会话数据异常，请重新登录");
                    }
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // session 不存在
                    log.warn("Session 已过期或不存在: {}, Redis返回empty", sessionId);
                    return writeUnauthorizedResponse(exchange, "未登录，请重新登录");
                }));
    }

    /**
     * 判断请求路径是否为管理端接口
     */
    private boolean isAdminPath(String path) {
        for (String adminPath : adminPaths) {
            if (path.startsWith(adminPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回 403 无权限 JSON 响应
     */
    private Mono<Void> writeForbiddenResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        R<Void> result = R.forbidden(message);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(result);
            DataBufferFactory bufferFactory = response.bufferFactory();
            return response.writeWith(Mono.just(bufferFactory.wrap(bytes)));
        } catch (JsonProcessingException e) {
            log.error("序列化响应失败", e);
            return response.setComplete();
        }
    }

    /**
     * 返回 401 未授权 JSON 响应
     */
    private Mono<Void> writeUnauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            log.warn("响应已提交，无法写入401: {}", message);
            return Mono.empty();
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        R<Void> result = R.unauthorized(message);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(result);
            DataBufferFactory bufferFactory = response.bufferFactory();
            return response.writeWith(Mono.just(bufferFactory.wrap(bytes)));
        } catch (JsonProcessingException e) {
            log.error("序列化响应失败", e);
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
