package com.qizhi.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 全链路 TraceId 过滤器（Gateway 层）
 * <p>
 * 为每个请求生成唯一 traceId，放入:
 *   1. 响应头 X-Trace-Id（前端可用于问题反馈）
 *   2. 请求头 X-Trace-Id（传递给下游微服务）
 *   3. MDC（网关日志可打印 traceId）
 * </p>
 */
@Slf4j
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    /** 链路追踪请求头名称 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 生成 traceId（优先使用上游传入的，否则自动生成）
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        // 放入 MDC 供网关日志使用
        MDC.put("traceId", traceId);

        // 将 traceId 注入下游请求头
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(TRACE_ID_HEADER, traceId)
                .build();

        // 在响应头中也返回 traceId（便于前端排查问题）
        exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);

        final String finalTraceId = traceId;
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFinally(signalType -> {
                    MDC.remove("traceId");
                    log.debug("TraceId 清理: {}", finalTraceId);
                });
    }

    /** 最高优先级，确保在所有其他过滤器之前执行 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
