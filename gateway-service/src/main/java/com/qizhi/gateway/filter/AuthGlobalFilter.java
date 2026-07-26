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

    /** 白名单路径（无需鉴权），由 Nacos 管理 */
    @Value("#{'${gateway.auth-white-list:/api/v1/auth/login,/api/v1/user/avatar}'.split(',')}")
    private List<String> authWhiteList;

    /** 可动态屏蔽的高危路径 */
    @Value("#{'${gateway.black-list:}'.split(',')}")
    private List<String> blackList;

    /**
     * 管理端路径前缀（仅 ADMIN 角色可访问）
     * SRS 需求: GW-14 解析会话中权限集合，拦截无权限接口访问
     * 从 Nacos 读取，支持热更新
     */
    @Value("#{'${gateway.admin-paths:/api/v1/user/list,/api/v1/user/create,/api/v1/dept,/api/v1/role,/api/v1/approve/template,/api/v1/message/dlq,/api/v1/message/dead-letter,/api/v1/stats/refresh,/api/v1/workorder/list,/api/v1/workorder/export-list}'.split(',')}")
    private List<String> adminPaths;

    /** 会话 Redis Key 前缀 */
    private static final String SESSION_PREFIX = "session:";

    /** 会话续期时长（分钟），从 Nacos 读取，默认 30 */
    @Value("${gateway.session-expire-minutes:30}")
    private int sessionExpireMinutes;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod() != null
                ? exchange.getRequest().getMethod().name() : "GET";

        // 白名单放行（头像路径仅 GET 获取免鉴权，POST 上传仍需正常鉴权）
        for (String blocked : blackList) {
            if (!blocked.isBlank() && path.startsWith(blocked.trim())) {
                return writeForbiddenResponse(exchange, "接口已被安全策略禁用");
            }
        }

        for (String white : authWhiteList) {
            white = white.trim();
            if (path.startsWith(white)) {
                if ("/api/v1/user/avatar".equals(white) && !"GET".equals(method)) {
                    break; // 头像上传等非 GET 请求走下方正常鉴权流程
                }
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

                        // 统一角色命名空间，旧公共业务路径不再对外暴露。
                        if (!isRolePathAllowed(path, role)) {
                            log.warn("角色路径越权，拦截请求: role={}, path={}", role, path);
                            return writeForbiddenResponse(exchange, "当前角色无权访问该接口");
                        }

                        // 构建新请求，注入用户信息到请求头
                        ServerHttpRequest newRequest = exchange.getRequest().mutate()
                                .header("X-User-Id", userId)
                                .header("X-Username", username)
                                .header("X-User-Role", role)
                                .build();

                        // 自动续期必须处于同一响应式链中，避免独立 subscribe 丢失错误和上下文。
                        return reactiveRedisTemplate.expire(
                                        sessionKey, Duration.ofMinutes(sessionExpireMinutes))
                                .then(chain.filter(exchange.mutate().request(newRequest).build()));
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
        if (path.startsWith("/api/v1/admin/")) {
            return true;
        }
        for (String adminPath : adminPaths) {
            if (path.startsWith(adminPath)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRolePathAllowed(String path, String role) {
        if (path.startsWith("/api/v1/employee/")) {
            return "EMPLOYEE".equals(role) || "APPROVER".equals(role) || "ADMIN".equals(role);
        }
        if (path.startsWith("/api/v1/approver/")) {
            return "APPROVER".equals(role) || "ADMIN".equals(role);
        }
        if (path.startsWith("/api/v1/admin/")) {
            return "ADMIN".equals(role);
        }
        // 登录后只允许角色命名空间；头像读取由白名单提前放行。
        return !path.startsWith("/api/v1/");
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
        result.setTraceId(exchange.getRequest().getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER));
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
        result.setTraceId(exchange.getRequest().getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER));
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
