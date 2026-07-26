package com.qizhi.common.redis.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisUtilTest {

    @Test
    void lockAndReplayNonceUseAtomicSetIfAbsent() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("lock:1", "1", 30, TimeUnit.SECONDS)).thenReturn(true, false);
        when(values.setIfAbsent("nonce:1", "used", 5, TimeUnit.MINUTES)).thenReturn(true, false);
        RedisUtil redis = new RedisUtil(template);

        assertTrue(redis.tryLock("lock:1", 30));
        assertFalse(redis.tryLock("lock:1", 30));
        assertTrue(redis.setIfAbsent("nonce:1", "used", 5, TimeUnit.MINUTES));
        assertFalse(redis.setIfAbsent("nonce:1", "used", 5, TimeUnit.MINUTES));
    }

    @Test
    void scopedCacheInvalidationDeletesOnlyMatchedKeys() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        when(template.keys("stats:*")).thenReturn(Set.of("stats:a", "stats:empty"));
        when(template.delete(Set.of("stats:a", "stats:empty"))).thenReturn(2L);
        RedisUtil redis = new RedisUtil(template);

        assertEquals(2L, redis.deleteByPattern("stats:*"));
        verify(template, never()).keys("*");
    }
}
