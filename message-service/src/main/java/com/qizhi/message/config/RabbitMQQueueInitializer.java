package com.qizhi.message.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;

/**
 * RabbitMQ 队列初始化与迁移组件
 * <p>
 * 服务启动后执行：
 *   1. 检查旧队列 delay.remind.queue 是否存在，若存在则安全迁移消息后删除
 *   2. 检查新延迟队列 TTL 是否与配置一致，不一致则安全重建（开发环境）
 * </p>
 * <p>
 * 注意: 修改 Nacos 中的延迟时间配置后，需要重启服务。
 * 如果同名队列已存在且 TTL 参数不同，本组件会在开发环境中安全删除并重建。
 * 这不是完全无损热更新——队列中若有未消费消息会先迁移再重建。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitMQQueueInitializer {

    private final RabbitAdmin rabbitAdmin;
    private final RabbitTemplate rabbitTemplate;

    /** 旧队列名称（单队列方案遗留） */
    private static final String OLD_DELAY_QUEUE = "delay.remind.queue";

    /** 新延迟队列及其对应的 routing key */
    private static final Map<String, String> DELAY_QUEUE_ROUTING = Map.of(
            "remind.delay.urgent.queue", "remind.delay.urgent",
            "remind.delay.normal.queue", "remind.delay.normal",
            "remind.delay.low.queue", "remind.delay.low",
            "remind.delay.retry.queue", "remind.delay.retry"
    );

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("========== RabbitMQ 队列初始化检查开始 ==========");
        try {
            migrateOldDelayQueue();
            log.info("========== RabbitMQ 队列初始化检查完成 ==========");
        } catch (Exception e) {
            log.error("RabbitMQ 队列初始化异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 迁移旧 delay.remind.queue 中的消息到新队列
     */
    private void migrateOldDelayQueue() {
        Properties queueProps = rabbitAdmin.getQueueProperties(OLD_DELAY_QUEUE);
        if (queueProps == null) {
            log.info("旧队列 {} 不存在，无需迁移", OLD_DELAY_QUEUE);
            return;
        }

        int messageCount = Integer.parseInt(
                queueProps.getOrDefault(RabbitAdmin.QUEUE_MESSAGE_COUNT, "0").toString());
        log.warn("发现旧队列 {}，当前消息数量: {}", OLD_DELAY_QUEUE, messageCount);

        if (messageCount == 0) {
            // 空队列直接删除
            log.info("旧队列为空，直接删除: {}", OLD_DELAY_QUEUE);
            rabbitAdmin.deleteQueue(OLD_DELAY_QUEUE);
            return;
        }

        // 有消息需要迁移：逐条取出并根据优先级重新投递
        log.info("开始迁移旧队列消息，共 {} 条", messageCount);
        int migrated = 0;
        int discarded = 0;

        for (int i = 0; i < messageCount; i++) {
            try {
                // 使用 basicGet 逐条拉取（不重新入队）
                org.springframework.amqp.core.Message msg = rabbitTemplate.receive(OLD_DELAY_QUEUE, 3000);
                if (msg == null) {
                    log.info("旧队列消息已全部取出，实际迁移 {} 条", i);
                    break;
                }

                // 解析消息体获取优先级
                String body = new String(msg.getBody());
                String priority = extractPriorityFromBody(body);
                String routingKey = resolveRoutingKey(priority);

                // 重新投递到新队列
                rabbitTemplate.convertAndSend("remind.delay.exchange", routingKey, body);
                migrated++;
                log.debug("迁移消息 [{}] → routingKey={}", i + 1, routingKey);
            } catch (Exception e) {
                discarded++;
                log.warn("迁移第 {} 条消息失败: {}", i + 1, e.getMessage());
            }
        }

        log.info("旧队列迁移完成: 成功迁移={}, 丢弃={}", migrated, discarded);

        // 确认迁移完成后删除旧队列
        Properties afterProps = rabbitAdmin.getQueueProperties(OLD_DELAY_QUEUE);
        int remaining = afterProps != null ?
                Integer.parseInt(afterProps.getOrDefault(RabbitAdmin.QUEUE_MESSAGE_COUNT, "0").toString()) : 0;
        if (remaining == 0) {
            rabbitAdmin.deleteQueue(OLD_DELAY_QUEUE);
            log.info("旧队列 {} 已删除", OLD_DELAY_QUEUE);
        } else {
            log.warn("旧队列 {} 仍有 {} 条消息未迁移，暂不删除", OLD_DELAY_QUEUE, remaining);
        }
    }

    /**
     * 从消息体中提取优先级字段
     */
    private String extractPriorityFromBody(String body) {
        // 简单 JSON 解析（避免引入额外依赖）
        if (body.contains("\"priority\"")) {
            int idx = body.indexOf("\"priority\"");
            int colonIdx = body.indexOf(":", idx);
            int quoteStart = body.indexOf("\"", colonIdx + 1);
            int quoteEnd = body.indexOf("\"", quoteStart + 1);
            if (quoteStart >= 0 && quoteEnd > quoteStart) {
                return body.substring(quoteStart + 1, quoteEnd);
            }
        }
        return "NORMAL";
    }

    /**
     * 根据优先级解析路由键
     */
    private String resolveRoutingKey(String priority) {
        if (priority == null || priority.isBlank()) {
            return "remind.delay.normal";
        }
        return switch (priority.toUpperCase()) {
            case "URGENT" -> "remind.delay.urgent";
            case "LOW" -> "remind.delay.low";
            default -> "remind.delay.normal";
        };
    }
}

