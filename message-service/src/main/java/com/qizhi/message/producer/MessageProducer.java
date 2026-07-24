package com.qizhi.message.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 消息生产者
 * <p>
 * 负责发送两类消息：
 *   1. 实时通知 — 发送到 notify.queue，由 NotifyConsumer 消费写入 sys_message
 *   2. 延迟督办 — 发送到 delay.remind.queue，TTL 过期后转发到 remind.fire.queue
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RefreshScope
public class MessageProducer {

    private final RabbitTemplate rabbitTemplate;

    /** 默认延迟督办 TTL（毫秒），从 Nacos 读取，默认 1 小时 */
    @Value("${remind.default-delay-ms:3600000}")
    private long defaultDelayMs;

    /**
     * 发送实时通知消息
     * <p>
     * 路由: direct.exchange + routingKey="notify" → notify.queue
     * </p>
     */
    public void sendNotify(Object message) {
        log.info("发送实时通知消息: {}", message);
        rabbitTemplate.convertAndSend("direct.exchange", "notify", message);
    }

    /**
     * 发送延迟督办消息
     * <p>
     * 路由: direct.exchange + routingKey="delay.remind" → delay.remind.queue
     * TTL 过期后通过死信转发到 remind.fire.queue → RemindConsumer 处理
     * </p>
     *
     * @param message  消息体（包含 workOrderId、orderNo、approverId 等信息）
     * @param ttlMs    延迟时间（毫秒），根据工单优先级设置不同值：
     *                 URGENT=60min(3600000ms), NORMAL=240min(14400000ms), LOW=720min(43200000ms)
     */
    public void sendDelayRemind(Object message, long ttlMs) {
        log.info("发送延迟督办消息: ttl={}ms, message={}", ttlMs, message);
        rabbitTemplate.convertAndSend("direct.exchange", "delay.remind", message, msgPostProcessor -> {
            msgPostProcessor.getMessageProperties().setExpiration(String.valueOf(ttlMs));
            return msgPostProcessor;
        });
    }

    /**
     * 发送延迟督办消息（默认 TTL 1 小时）
     */
    public void sendDelayRemind(Object message) {
        sendDelayRemind(message, defaultDelayMs);
    }
}
