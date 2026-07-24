package com.qizhi.common.redis.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis 工具类
 */
@Component
@RequiredArgsConstructor
public class RedisUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    /** 写入 */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /** 写入并设置过期时间 */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /** 读取 */
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /** 删除 */
    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    /** 判断 key 是否存在 */
    public Boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    /** 设置过期时间 */
    public Boolean expire(String key, long timeout, TimeUnit unit) {
        return redisTemplate.expire(key, timeout, unit);
    }

    /** INCR 自增（限流用） */
    public Long increment(String key) {
        return redisTemplate.opsForValue().increment(key);
    }

    /**
     * 尝试获取分布式锁（SETNX）
     *
     * @param key     锁 key
     * @param timeout 过期时间（秒）
     * @return true=获取成功
     */
    public Boolean tryLock(String key, long timeout) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, "1", timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    /** 释放分布式锁 */
    public void unlock(String key) {
        redisTemplate.delete(key);
    }
}
