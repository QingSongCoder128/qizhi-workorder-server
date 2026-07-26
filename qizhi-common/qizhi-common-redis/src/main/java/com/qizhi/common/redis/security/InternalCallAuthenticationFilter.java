package com.qizhi.common.redis.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qizhi.common.core.constant.CommonConstants;
import com.qizhi.common.core.result.R;
import com.qizhi.common.core.security.InternalCallSignature;
import com.qizhi.common.redis.util.RedisUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 微服务入口保护：校验 HMAC、时间窗口、真实路径和 Redis nonce。
 */
@Component
@Order(-1000)
@RequiredArgsConstructor
@Slf4j
public class InternalCallAuthenticationFilter extends OncePerRequestFilter {

    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    @Value("${security.internal.secret:${INTERNAL_CALL_SECRET:}}")
    private String secret;

    @Value("${security.internal.max-skew-seconds:300}")
    private long maxSkewSeconds;

    @Value("${security.internal.allowed-callers:gateway-service,user-service,work-order-service,approve-service,message-service,ai-process-service,statistics-service}")
    private String allowedCallersConfig;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String caller = request.getHeader(CommonConstants.HEADER_INTERNAL_CALLER);
        String timestamp = request.getHeader(CommonConstants.HEADER_INTERNAL_TIMESTAMP);
        String nonce = request.getHeader(CommonConstants.HEADER_INTERNAL_NONCE);
        String signedPath = request.getHeader(CommonConstants.HEADER_INTERNAL_PATH);
        String actualSignature = request.getHeader(CommonConstants.HEADER_INTERNAL_SIGNATURE);

        boolean callerAllowed = isAllowedCaller(caller);
        boolean fresh = isFresh(timestamp);
        boolean nonceValid = nonce != null && nonce.length() >= 16;
        boolean pathMatches = InternalCallSignature.normalizePath(request.getRequestURI()).equals(
                InternalCallSignature.normalizePath(signedPath));
        if (!callerAllowed || !fresh || !nonceValid || !pathMatches) {
            log.warn("Rejected internal identity: caller={}, callerAllowed={}, fresh={}, nonceValid={}, "
                            + "signedPath={}, requestPath={}, pathMatches={}",
                    caller, callerAllowed, fresh, nonceValid, signedPath, request.getRequestURI(), pathMatches);
            reject(response, "不可信的内部调用身份");
            return;
        }

        String expected = InternalCallSignature.sign(secret, caller, timestamp, nonce,
                request.getMethod(), signedPath);
        if (!InternalCallSignature.verify(expected, actualSignature)) {
            reject(response, "内部调用签名无效");
            return;
        }

        String nonceKey = "security:internal:nonce:" + caller + ":" + nonce;
        if (!redisUtil.setIfAbsent(nonceKey, "1", maxSkewSeconds + 10, TimeUnit.SECONDS)) {
            reject(response, "内部调用请求已被重放");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAllowedCaller(String caller) {
        if (caller == null || caller.isBlank() || secret == null || secret.length() < 32) {
            return false;
        }
        Set<String> allowed = Arrays.stream(allowedCallersConfig.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        return allowed.contains(caller);
    }

    private boolean isFresh(String timestamp) {
        try {
            long requestMillis = Long.parseLong(timestamp);
            long drift = Math.abs(Instant.now().toEpochMilli() - requestMillis);
            return drift <= TimeUnit.SECONDS.toMillis(maxSkewSeconds);
        } catch (Exception e) {
            return false;
        }
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), R.forbidden(message));
    }
}
