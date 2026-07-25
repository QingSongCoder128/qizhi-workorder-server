package com.qizhi.message.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 消息生产者
 * <p>
 * 负责发送两类消息：
 *   1. 实时通知 — 发送到 notify.queue，由 NotifyConsumer 消费写入 sys_message
 *   2. 延迟督办 — 根据优先级发送到对应延迟队列，TTL 过期后转发到 remind.fire.queue
 * </p>
 * <p>
 * 延迟队列按优先级拆分（解决队头阻塞问题）：
 *   - URGENT → remind.delay.urgent.queue (TTL=60min)
 *   - NORMAL → remind.delay.normal.queue (TTL=240min)
 *   - LOW    → remind.delay.low.queue    (TTL=720min)
 *   - 重复督办 → remind.delay.retry.queue (TTL=re-delay-ms)
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageProducer {

    private final RabbitTemplate rabbitTemplate;

    /** 延迟督办发布交换机 */
    private static final String REMIND_DELAY_EXCHANGE = "remind.delay.exchange";

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
     * 发送延迟督办消息（根据优先级路由到对应延迟队列）
     * <p>
     * 路由: remind.delay.exchange + routingKey="remind.delay.{priority}" → 对应延迟队列
     * 队列固定 TTL 过期后通过死信转发到 remind.fire.queue → RemindConsumer 处理
     * </p>
     *
     * @param message  消息体（包含 workOrderId、orderNo、approverId、priority 等信息）
     * @param priority 工单优先级: URGENT / NORMAL / LOW
     */
    public void sendDelayRemind(Object message, String priority) {
        String routingKey = resolveRoutingKey(priority);
        log.info("发送延迟督办消息: priority={}, routingKey={}, message={}", priority, routingKey, message);
        rabbitTemplate.convertAndSend(REMIND_DELAY_EXCHANGE, routingKey, message);
    }

    /**
     * 发送重复督办延迟消息（使用固定重试队列）
     * <p>
     * 路由: remind.delay.exchange + routingKey="remind.delay.retry" → remind.delay.retry.queue
     * </p>
     *
     * @param message 消息体
     */
    public void sendRetryRemind(Object message) {
        log.info("发送重复督办延迟消息: message={}", message);
        rabbitTemplate.convertAndSend(REMIND_DELAY_EXCHANGE, "remind.delay.retry", message);
    }

    /**
     * 根据优先级解析路由键
     * 未知优先级使用 NORMAL 队列兆底，记录 warning 日志
     */
    private String resolveRoutingKey(String priority) {
        if (priority == null || priority.isBlank()) {
            log.warn("督办消息优先级为空，使用 NORMAL 队列兆底");
            return "remind.delay.normal";
        }
        return switch (priority.toUpperCase()) {
            case "URGENT" -> "remind.delay.urgent";
            case "LOW" -> "remind.delay.low";
            case "NORMAL" -> "remind.delay.normal";
            default -> {
                log.warn("未知优先级 '{}'，使用 NORMAL 队列兆底", priority);
                yield "remind.delay.normal";
            }
        };
    }
}
