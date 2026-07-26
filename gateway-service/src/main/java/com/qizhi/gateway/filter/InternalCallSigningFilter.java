package com.qizhi.gateway.filter;

import com.qizhi.common.core.constant.CommonConstants;
import com.qizhi.common.core.security.InternalCallSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 移除客户端伪造的内部头，并为 Gateway 转发请求生成 HMAC 身份。
 */
@Component
public class InternalCallSigningFilter implements GlobalFilter, Ordered {

    private static final String CALLER = "gateway-service";

    private static final Map<String, String> PATH_MAPPINGS = new LinkedHashMap<>();

    static {
        PATH_MAPPINGS.put("/api/v1/employee/user", "/api/v1/user");
        PATH_MAPPINGS.put("/api/v1/employee/departments", "/api/v1/dept");
        PATH_MAPPINGS.put("/api/v1/employee/workorders", "/api/v1/workorder");
        PATH_MAPPINGS.put("/api/v1/employee/messages", "/api/v1/message");
        PATH_MAPPINGS.put("/api/v1/employee/approvals", "/api/v1/approve");
        PATH_MAPPINGS.put("/api/v1/approver/approvals", "/api/v1/approve");
        PATH_MAPPINGS.put("/api/v1/approver/user", "/api/v1/user");
        PATH_MAPPINGS.put("/api/v1/approver/workorders", "/api/v1/workorder");
        PATH_MAPPINGS.put("/api/v1/approver/messages", "/api/v1/message");
        PATH_MAPPINGS.put("/api/v1/admin/users", "/api/v1/user");
        PATH_MAPPINGS.put("/api/v1/admin/departments", "/api/v1/dept");
        PATH_MAPPINGS.put("/api/v1/admin/roles", "/api/v1/role");
        PATH_MAPPINGS.put("/api/v1/admin/workorders", "/api/v1/workorder");
        PATH_MAPPINGS.put("/api/v1/admin/approval-templates", "/api/v1/approve/template");
        PATH_MAPPINGS.put("/api/v1/admin/approvals", "/api/v1/approve");
        PATH_MAPPINGS.put("/api/v1/admin/messages", "/api/v1/message");
        PATH_MAPPINGS.put("/api/v1/admin/dead-letters", "/api/v1/message/dead-letter");
        PATH_MAPPINGS.put("/api/v1/admin/reminders", "/api/v1/message/reminder");
        PATH_MAPPINGS.put("/api/v1/admin/stats", "/api/v1/stats");
        PATH_MAPPINGS.put("/api/v1/admin/ai", "/api/v1/ai");
        PATH_MAPPINGS.put("/api/v1/admin/config/ai", "/api/v1/ai/config");
    }

    @Value("${security.internal.secret:${INTERNAL_CALL_SECRET:}}")
    private String secret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String originalPath = exchange.getRequest().getURI().getPath();
        String downstreamPath = downstreamPath(originalPath);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = UUID.randomUUID().toString();
        String method = exchange.getRequest().getMethod() == null
                ? "GET" : exchange.getRequest().getMethod().name();
        String signature = InternalCallSignature.sign(
                secret, CALLER, timestamp, nonce, method, downstreamPath);

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(CommonConstants.HEADER_INTERNAL_CALLER);
                    headers.remove(CommonConstants.HEADER_INTERNAL_TIMESTAMP);
                    headers.remove(CommonConstants.HEADER_INTERNAL_NONCE);
                    headers.remove(CommonConstants.HEADER_INTERNAL_PATH);
                    headers.remove(CommonConstants.HEADER_INTERNAL_SIGNATURE);
                })
                .header(CommonConstants.HEADER_INTERNAL_CALLER, CALLER)
                .header(CommonConstants.HEADER_INTERNAL_TIMESTAMP, timestamp)
                .header(CommonConstants.HEADER_INTERNAL_NONCE, nonce)
                .header(CommonConstants.HEADER_INTERNAL_PATH, downstreamPath)
                .header(CommonConstants.HEADER_INTERNAL_SIGNATURE, signature)
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    static String downstreamPath(String path) {
        for (Map.Entry<String, String> mapping : PATH_MAPPINGS.entrySet()) {
            if (path.equals(mapping.getKey()) || path.startsWith(mapping.getKey() + "/")) {
                return mapping.getValue() + path.substring(mapping.getKey().length());
            }
        }
        return InternalCallSignature.normalizePath(path);
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
