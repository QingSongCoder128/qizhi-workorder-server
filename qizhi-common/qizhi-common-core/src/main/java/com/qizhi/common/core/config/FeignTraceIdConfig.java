package com.qizhi.common.core.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

/**
 * Feign TraceId 传播配置
 * <p>
 * 在 Feign 调用下游服务时，自动将当前线程 MDC 中的 traceId
 * 注入到请求头 X-Trace-Id，实现跨服务链路追踪。
 * </p>
 */
@Configuration
@ConditionalOnClass(RequestInterceptor.class)
public class FeignTraceIdConfig implements RequestInterceptor {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isEmpty()) {
            template.header(TRACE_ID_HEADER, traceId);
        }
    }
}
