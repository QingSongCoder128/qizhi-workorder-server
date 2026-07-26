package com.qizhi.user.service.impl;

import com.qizhi.common.core.exception.BusinessException;
import com.qizhi.common.redis.util.RedisUtil;
import com.qizhi.user.dto.LoginDTO;
import com.qizhi.user.entity.SysUser;
import com.qizhi.user.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceImplLockTest {

    private SysUser user;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        SysUserMapper users = mock(SysUserMapper.class);
        user = new SysUser();
        user.setId(901L);
        user.setUsername("lock-test");
        user.setPassword(new BCryptPasswordEncoder().encode("correct"));
        user.setStatus("ENABLED");
        user.setLoginFail(0);
        when(users.selectOne(any())).thenReturn(user);
        service = new UserServiceImpl(
                users,
                mock(SysRoleMapper.class),
                mock(SysUserRoleMapper.class),
                mock(SysRolePermissionMapper.class),
                mock(SysDepartmentMapper.class),
                mock(RedisUtil.class));
        ReflectionTestUtils.setField(service, "loginLockCount", 5);
        ReflectionTestUtils.setField(service, "loginLockMinutes", 15);
    }

    @Test
    void fiveFailuresLockForFifteenMinutes() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("lock-test");
        dto.setPassword("wrong");
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThrows(BusinessException.class, () -> service.login(dto));
            assertEquals(attempt, user.getLoginFail());
        }
        assertEquals("LOCKED", user.getStatus());
        assertTrue(user.getLockTime().isAfter(LocalDateTime.now().plusMinutes(14)));

        BusinessException locked = assertThrows(BusinessException.class, () -> service.login(dto));
        assertEquals(403, locked.getCode());
    }

    @Test
    void expiredLockIsAtomicallyResetBeforeNextAttempt() {
        user.setStatus("LOCKED");
        user.setLoginFail(5);
        user.setLockTime(LocalDateTime.now().minusSeconds(1));
        LoginDTO dto = new LoginDTO();
        dto.setUsername("lock-test");
        dto.setPassword("wrong");

        assertThrows(BusinessException.class, () -> service.login(dto));

        assertEquals("ENABLED", user.getStatus());
        assertEquals(1, user.getLoginFail());
        assertNull(user.getLockTime());
    }
}
