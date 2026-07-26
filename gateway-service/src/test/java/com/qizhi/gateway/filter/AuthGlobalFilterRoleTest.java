package com.qizhi.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AuthGlobalFilterRoleTest {

    @Test
    void roleNamespacesEnforceThreeRoleBoundary() {
        @SuppressWarnings("unchecked")
        ReactiveRedisTemplate<String, Object> redis = mock(ReactiveRedisTemplate.class);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);

        assertTrue(allowed(filter, "/api/v1/employee/workorders", "EMPLOYEE"));
        assertFalse(allowed(filter, "/api/v1/approver/approvals", "EMPLOYEE"));
        assertFalse(allowed(filter, "/api/v1/admin/users", "EMPLOYEE"));

        assertTrue(allowed(filter, "/api/v1/employee/workorders", "APPROVER"));
        assertTrue(allowed(filter, "/api/v1/approver/approvals", "APPROVER"));
        assertFalse(allowed(filter, "/api/v1/admin/dead-letters", "APPROVER"));

        assertTrue(allowed(filter, "/api/v1/employee/workorders", "ADMIN"));
        assertTrue(allowed(filter, "/api/v1/approver/approvals", "ADMIN"));
        assertTrue(allowed(filter, "/api/v1/admin/config/ai", "ADMIN"));
    }

    @Test
    void legacyBusinessPathsAreNotExposed() {
        @SuppressWarnings("unchecked")
        ReactiveRedisTemplate<String, Object> redis = mock(ReactiveRedisTemplate.class);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);
        assertFalse(allowed(filter, "/api/v1/workorder/list", "ADMIN"));
        assertFalse(allowed(filter, "/api/v1/message/dlq/list", "ADMIN"));
    }

    private boolean allowed(AuthGlobalFilter filter, String path, String role) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                filter, "isRolePathAllowed", path, role));
    }
}
