package com.qizhi.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AuthGlobalFilterRoleTest {

    // 模拟三种典型权限集合
    private static final List<String> EMPLOYEE_PERMS = List.of(
            "workorder:submit", "workorder:view", "stats:view", "message:view");
    private static final List<String> APPROVER_PERMS = List.of(
            "workorder:submit", "workorder:view", "workorder:approve", "stats:view", "message:view");
    private static final List<String> ADMIN_PERMS = List.of(
            "workorder:submit", "workorder:view", "workorder:admin", "workorder:approve",
            "stats:view", "message:view", "user:manage", "dept:manage", "role:manage",
            "deadletter:manage", "config:manage", "template:manage");

    @Test
    void permissionBasedNamespaceEnforcement() {
        @SuppressWarnings("unchecked")
        ReactiveRedisTemplate<String, Object> redis = mock(ReactiveRedisTemplate.class);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);

        // 员工权限：只能访问 employee 命名空间
        assertTrue(allowed(filter, "/api/v1/employee/workorders", EMPLOYEE_PERMS));
        assertFalse(allowed(filter, "/api/v1/approver/approvals", EMPLOYEE_PERMS));
        assertFalse(allowed(filter, "/api/v1/admin/users", EMPLOYEE_PERMS));

        // 审批权限：可访问 employee + approver 命名空间
        assertTrue(allowed(filter, "/api/v1/employee/workorders", APPROVER_PERMS));
        assertTrue(allowed(filter, "/api/v1/approver/approvals", APPROVER_PERMS));
        assertFalse(allowed(filter, "/api/v1/admin/dead-letters", APPROVER_PERMS));

        // 管理权限：可访问所有命名空间
        assertTrue(allowed(filter, "/api/v1/employee/workorders", ADMIN_PERMS));
        assertTrue(allowed(filter, "/api/v1/approver/approvals", ADMIN_PERMS));
        assertTrue(allowed(filter, "/api/v1/admin/config/ai", ADMIN_PERMS));
    }

    @Test
    void dynamicPermissionGrant() {
        @SuppressWarnings("unchecked")
        ReactiveRedisTemplate<String, Object> redis = mock(ReactiveRedisTemplate.class);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);

        // 员工被动态分配了 deadletter:manage 权限 → 可访问 admin 死信接口
        List<String> customPerms = List.of("workorder:submit", "workorder:view", "deadletter:manage");
        assertTrue(allowed(filter, "/api/v1/admin/dead-letters", customPerms));
        // 但没有审批权限 → 不能访问 approver 命名空间
        assertFalse(allowed(filter, "/api/v1/approver/approvals", customPerms));
        // 也没有 user:manage → 不能访问 admin 用户接口
        assertFalse(allowed(filter, "/api/v1/admin/users", customPerms));
    }

    @Test
    void statsViewGrantsAccessToStatsOnly() {
        @SuppressWarnings("unchecked")
        ReactiveRedisTemplate<String, Object> redis = mock(ReactiveRedisTemplate.class);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);

        // 员工有 stats:view → 可访问统计接口
        assertTrue(allowed(filter, "/api/v1/admin/stats/dashboard", EMPLOYEE_PERMS));
        // 但不能访问其他 admin 接口
        assertFalse(allowed(filter, "/api/v1/admin/users", EMPLOYEE_PERMS));
        assertFalse(allowed(filter, "/api/v1/admin/config/ai", EMPLOYEE_PERMS));
    }

    @Test
    void legacyBusinessPathsAreNotExposed() {
        @SuppressWarnings("unchecked")
        ReactiveRedisTemplate<String, Object> redis = mock(ReactiveRedisTemplate.class);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);
        assertFalse(allowed(filter, "/api/v1/workorder/list", ADMIN_PERMS));
        assertFalse(allowed(filter, "/api/v1/message/dlq/list", ADMIN_PERMS));
    }

    private boolean allowed(AuthGlobalFilter filter, String path, List<String> permissions) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                filter, "isPathAllowed", path, permissions));
    }
}
