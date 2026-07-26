package com.qizhi.message.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 发布持久化通知和 TTL+DLX 督办消息，并为 Publisher Confirm 提供关联 ID。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageProducer {

    private static final String REMIND_DELAY_EXCHANGE = "remind.delay.exchange";

    private final RabbitTemplate rabbitTemplate;

    public void sendNotify(Object message) {
        sendPersistent("direct.exchange", "notify", message, "通知");
    }

    public void sendDelayRemind(Object message, String priority) {
        String routingKey = resolveRoutingKey(priority);
        sendPersistent(REMIND_DELAY_EXCHANGE, routingKey, message, "延迟督办");
    }

    public void sendRetryRemind(Object message) {
        sendPersistent(REMIND_DELAY_EXCHANGE, "remind.delay.retry", message, "重复督办");
    }

    private void sendPersistent(String exchange, String routingKey, Object payload, String type) {
        String messageId = UUID.randomUUID().toString();
        rabbitTemplate.convertAndSend(exchange, routingKey, payload, amqpMessage -> {
            amqpMessage.getMessageProperties().setMessageId(messageId);
            amqpMessage.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return amqpMessage;
        }, new CorrelationData(messageId));
        log.info("{}消息已发送, messageId={}, routingKey={}", type, messageId, routingKey);
    }

    private String resolveRoutingKey(String priority) {
        if (priority == null || priority.isBlank()) {
            log.warn("督办消息优先级为空，使用 NORMAL 队列兜底");
            return "remind.delay.normal";
        }
        return switch (priority.toUpperCase()) {
            case "URGENT" -> "remind.delay.urgent";
            case "LOW" -> "remind.delay.low";
            case "NORMAL" -> "remind.delay.normal";
            default -> {
                log.warn("未知优先级 '{}'，使用 NORMAL 队列兜底", priority);
                yield "remind.delay.normal";
            }
        };
    }
}
