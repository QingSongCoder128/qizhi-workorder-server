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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

/**
 * QPS 限流过滤器
 * <p>
 * SRS 需求: GW-06 ~ GW-10
 * 基于 Redis INCR 计数器实现双维度限流（用户维度 + IP 维度），
 * 所有阈值均从 Nacos 配置中心读取，支持热更新（@RefreshScope）。
 * </p>
 * <p>
 * 执行顺序: order = -200（在 AuthGlobalFilter(-100) 之前执行）
 * 策略:
 *   1. 所有请求先做 IP 维度限流
 *   2. 白名单路径（登录/注册）跳过用户维度限流
 *   3. 工单提交接口使用独立的更高阈值
 *   4. 已认证请求做用户维度限流
 * </p>
 */
@Slf4j
@Component
@RefreshScope
@RequiredArgsConstructor
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;

    /** Jackson 序列化器，用于构建 JSON 响应体 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== Nacos 可配置参数（支持热更新） ====================

    /** 用户维度每秒最大请求数，Nacos: gateway.rate-limit.user-qps */
    @Value("${gateway.rate-limit.user-qps:20}")
    private int userQps;

    /** IP 维度每秒最大请求数，Nacos: gateway.rate-limit.ip-qps */
    @Value("${gateway.rate-limit.ip-qps:50}")
    private int ipQps;

    /** 工单提交接口专用阈值（更高），Nacos: gateway.rate-limit.submit-qps */
    @Value("${gateway.rate-limit.submit-qps:100}")
    private int submitQps;

    /** 免限流白名单路径列表，Nacos: gateway.white-list */
    @Value("#{'${gateway.white-list:/api/v1/auth/login,/api/v1/auth/register}'.split(',')}")
    private List<String> whiteList;

    // ==================== Redis Key 前缀 ====================

    private static final String LIMIT_USER_PREFIX = "limit:user:";
    private static final String LIMIT_IP_PREFIX = "limit:ip:";

    /** 工单提交接口路径，用于匹配独立阈值 */
    private static final String SUBMIT_PATH = "/api/v1/workorder/submit";

    /** 限流计数器过期时间（1 秒，实现每秒重置） */
    private static final Duration COUNTER_EXPIRE = Duration.ofSeconds(1);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // ---- 第一步: IP 维度限流（所有请求都检查） ----
        String clientIp = getClientIp(request);
        String ipKey = LIMIT_IP_PREFIX + clientIp;

        return checkRateLimit(ipKey, ipQps)
                .flatMap(ipAllowed -> {
                    if (!ipAllowed) {
                        log.warn("IP限流触发: ip={}, path={}", clientIp, path);
                        return writeTooManyResponse(exchange, "请求过于频繁，请稍后再试");
                    }

                    // ---- 第二步: 白名单路径跳过用户维度限流 ----
                    for (String white : whiteList) {
                        if (path.startsWith(white.trim())) {
                            return chain.filter(exchange);
                        }
                    }

                    // ---- 第三步: 用户维度限流（仅已认证请求） ----
                    String userId = request.getHeaders().getFirst("X-User-Id");
                    if (userId != null && !userId.isBlank()) {
                        String userKey = LIMIT_USER_PREFIX + userId;

                        // 工单提交接口使用独立的更高阈值，防止恶意刷单
                        int limit = path.startsWith(SUBMIT_PATH) ? submitQps : userQps;

                        return checkRateLimit(userKey, limit)
                                .flatMap(userAllowed -> {
                                    if (!userAllowed) {
                                        log.warn("用户限流触发: userId={}, path={}", userId, path);
                                        return writeTooManyResponse(exchange, "操作过于频繁，请稍后再试");
                                    }
                                    return chain.filter(exchange);
                                });
                    }

                    // 未认证且非白名单的请求直接放行（后续 AuthGlobalFilter 会拦截）
                    return chain.filter(exchange);
                });
    }

    /**
     * 检查 Redis 限流计数器是否超过阈值
     * <p>
     * 实现原理: 使用 Redis INCR 命令，Key 首次创建时设置 1 秒过期，
     * 实现每秒自动重置的滑动窗口计数器。
     * </p>
     *
     * @param key   Redis Key（如 limit:user:1 或 limit:ip:127.0.0.1）
     * @param limit 每秒最大请求数
     * @return true=允许通过，false=触发限流
     */
    private Mono<Boolean> checkRateLimit(String key, int limit) {
        return reactiveRedisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        // 首次创建计数器，设置 1 秒过期（保证每秒重置）
                        return reactiveRedisTemplate.expire(key, COUNTER_EXPIRE)
                                .thenReturn(true);
                    }
                    // 计数超过阈值则拒绝
                    return Mono.just(count <= limit);
                })
                .onErrorReturn(true); // Redis 异常时放行，避免阻断业务
    }

    /**
     * 获取客户端真实 IP
     * 优先从 X-Forwarded-For / X-Real-IP 头获取（经过反向代理时），
     * 回退到 socket 地址。
     */
    private String getClientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For 可能包含多个 IP，取第一个（最原始客户端）
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }

    /**
     * 返回 HTTP 429 限流 JSON 响应
     * SRS 需求 GW-08: {"code":429,"msg":"请求过于频繁，请稍后再试"}
     */
    private Mono<Void> writeTooManyResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            log.warn("响应已提交，无法写入429: {}", message);
            return Mono.empty();
        }
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        R<Void> result = R.tooMany(message);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(result);
            DataBufferFactory bufferFactory = response.bufferFactory();
            return response.writeWith(Mono.just(bufferFactory.wrap(bytes)));
        } catch (JsonProcessingException e) {
            log.error("序列化限流响应失败", e);
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        // 在 AuthGlobalFilter(-100) 之前执行，确保限流优先于鉴权
        return -200;
    }
}
