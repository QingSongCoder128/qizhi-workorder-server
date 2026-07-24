package com.qizhi.common.core.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * 链路追踪拦截器（下游微服务使用）
 * <p>
 * 从网关传递的 X-Trace-Id 请求头中读取 traceId 并放入 MDC，
 * 使日志中可自动携带 traceId 信息，便于全链路问题排查。
 * </p>
 */
public class TraceIdInterceptor implements HandlerInterceptor {

    /** 链路追踪请求头名称（与 Gateway TraceIdFilter 一致） */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 从请求头获取 traceId（由 Gateway 注入）
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            // 如果没有（直接调用未经网关），自动生成
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put("traceId", traceId);

        // 响应头也返回 traceId
        response.setHeader(TRACE_ID_HEADER, traceId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求结束后清除 MDC，避免线程复用时 traceId 串扰
        MDC.remove("traceId");
    }
}
