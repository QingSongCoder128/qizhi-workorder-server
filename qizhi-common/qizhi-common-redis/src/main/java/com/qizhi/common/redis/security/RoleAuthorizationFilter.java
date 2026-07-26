package com.qizhi.common.redis.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qizhi.common.core.constant.CommonConstants;
import com.qizhi.common.core.result.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 微服务第二道权限边界。Gateway 通过后仍必须满足服务端角色规则。
 */
@Component
@Order(-900)
@RequiredArgsConstructor
public class RoleAuthorizationFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/")
                || request.getRequestURI().equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String caller = request.getHeader(CommonConstants.HEADER_INTERNAL_CALLER);
        if (!"gateway-service".equals(caller)) {
            chain.doFilter(request, response);
            return;
        }
        String role = request.getHeader(CommonConstants.HEADER_USER_ROLE);
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (!allowed(role, path, method)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding("UTF-8");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), R.forbidden("后端接口权限校验失败"));
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean allowed(String role, String path, String method) {
        if (path.startsWith("/api/v1/auth/")) {
            return true;
        }
        if ("ADMIN".equals(role)) {
            return true;
        }
        if (path.startsWith("/api/v1/stats") || path.startsWith("/api/v1/approve/template")
                || path.startsWith("/api/v1/message/dead-letter")
                || path.startsWith("/api/v1/message/dlq")
                || path.startsWith("/api/v1/ai")) {
            return false;
        }
        if (path.startsWith("/internal/")) {
            return false;
        }
        if (path.startsWith("/api/v1/user")) {
            boolean self = path.equals("/api/v1/user/me")
                    || path.equals("/api/v1/user/profile")
                    || path.equals("/api/v1/user/password")
                    || path.startsWith("/api/v1/user/avatar/");
            boolean approverLookup = path.equals("/api/v1/user/by-role")
                    && ("APPROVER".equals(role));
            return self || approverLookup;
        }
        if (path.startsWith("/api/v1/dept")) {
            return "GET".equals(method);
        }
        if (path.startsWith("/api/v1/role")) {
            return false;
        }
        if (path.startsWith("/api/v1/approve")) {
            if ("EMPLOYEE".equals(role)) {
                return "GET".equals(method)
                        && (path.contains("/records") || path.startsWith("/api/v1/approve/by-work-order/"));
            }
            return "APPROVER".equals(role);
        }
        if (path.startsWith("/api/v1/workorder")) {
            if (path.equals("/api/v1/workorder/list")
                    || path.equals("/api/v1/workorder/export-list")
                    || path.equals("/api/v1/workorder/stats")
                    || path.matches("/api/v1/workorder/\\d+/status")) {
                return false;
            }
            return "EMPLOYEE".equals(role) || "APPROVER".equals(role);
        }
        if (path.startsWith("/api/v1/message")) {
            return "EMPLOYEE".equals(role) || "APPROVER".equals(role);
        }
        return false;
    }
}
