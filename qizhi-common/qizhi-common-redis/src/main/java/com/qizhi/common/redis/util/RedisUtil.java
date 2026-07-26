package com.qizhi.common.redis.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.Set;

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

    /** Delete all matching keys in a narrowly scoped cache namespace. */
    public long deleteByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        return keys == null || keys.isEmpty() ? 0L : redisTemplate.delete(keys);
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

    /** INCRBY 按指定步长自增（key 不存在时初始化为 delta，用于序号计数器初始化） */
    public Long incrementBy(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
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

    /** 写入一次性值（SET NX EX），用于内部调用 nonce 防重放。 */
    public boolean setIfAbsent(String key, Object value, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit));
    }

    /** 释放分布式锁 */
    public void unlock(String key) {
        redisTemplate.delete(key);
    }
}
