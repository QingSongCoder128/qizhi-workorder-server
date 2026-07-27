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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 微服务第二道权限边界（基于权限码动态判断，不再硬编码角色）。
 * Gateway 通过后仍必须满足服务端权限规则。
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
        // 从网关注入的 header 中解析权限列表
        String permHeader = request.getHeader(CommonConstants.HEADER_USER_PERMISSIONS);
        List<String> permissions = (permHeader != null && !permHeader.isBlank())
                ? Arrays.asList(permHeader.split(","))
                : Collections.emptyList();
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (!allowed(permissions, path, method)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding("UTF-8");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), R.forbidden("后端接口权限校验失败"));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 基于权限码判断接口访问权限（动态，不再硬编码角色）
     */
    static boolean allowed(List<String> perms, String path, String method) {
        // 认证接口始终放行
        if (path.startsWith("/api/v1/auth/")) {
            return true;
        }
        // 统计看板
        if (path.startsWith("/api/v1/stats")) {
            return perms.contains("stats:view");
        }
        // AI 配置
        if (path.startsWith("/api/v1/ai")) {
            return perms.contains("config:manage");
        }
        // 死信管理
        if (path.startsWith("/api/v1/message/dead-letter") || path.startsWith("/api/v1/message/dlq")) {
            return perms.contains("deadletter:manage");
        }
        // 审批模板
        if (path.startsWith("/api/v1/approve/template")) {
            return perms.contains("template:manage");
        }
        // 内部接口禁止外部访问
        if (path.startsWith("/internal/")) {
            return false;
        }
        // 用户管理
        if (path.startsWith("/api/v1/user")) {
            // 自助操作（个人信息/头像/密码）始终允许
            boolean self = path.equals("/api/v1/user/me")
                    || path.equals("/api/v1/user/profile")
                    || path.equals("/api/v1/user/password")
                    || path.startsWith("/api/v1/user/avatar/");
            if (self) return true;
            // 其他用户管理操作需要 user:manage
            return perms.contains("user:manage");
        }
        // 部门管理
        if (path.startsWith("/api/v1/dept")) {
            // 部门列表/树读取：任何已认证用户均可访问（新建工单需选部门）
            if ("GET".equals(method) && (path.equals("/api/v1/dept/list") || path.equals("/api/v1/dept/tree"))) {
                return true;
            }
            // 其他部门管理操作需要 dept:manage
            return perms.contains("dept:manage");
        }
        // 角色管理
        if (path.startsWith("/api/v1/role")) {
            return perms.contains("role:manage");
        }
        // 审批流程
        if (path.startsWith("/api/v1/approve")) {
            // 查看审批记录：有 workorder:view 即可
            if ("GET".equals(method) && (path.contains("/records") || path.startsWith("/api/v1/approve/by-work-order/"))) {
                return perms.contains("workorder:view");
            }
            // 审批操作：需要 workorder:approve
            return perms.contains("workorder:approve");
        }
        // 工单管理
        if (path.startsWith("/api/v1/workorder")) {
            // 管理级操作（全量列表/导出/统计/状态变更）
            if (path.equals("/api/v1/workorder/list")
                    || path.equals("/api/v1/workorder/export-list")
                    || path.equals("/api/v1/workorder/stats")
                    || path.matches("/api/v1/workorder/\\d+/status")) {
                return perms.contains("workorder:admin");
            }
            // 基本工单操作
            return perms.contains("workorder:submit") || perms.contains("workorder:view");
        }
        // 消息中心
        if (path.startsWith("/api/v1/message")) {
            return perms.contains("message:view");
        }
        // 未匹配路径默认拒绝
        return false;
    }
}
