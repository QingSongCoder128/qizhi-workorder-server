package com.qizhi.message.consumer;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qizhi.common.core.result.R;
import com.qizhi.message.entity.DelayedTask;
import com.qizhi.message.entity.SysMessage;
import com.qizhi.message.feign.WorkOrderFeignClient;
import com.qizhi.message.mapper.DelayedTaskMapper;
import com.qizhi.message.mapper.SysMessageMapper;
import com.qizhi.message.producer.MessageProducer;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 督办消息消费者
 * <p>
 * SRS 需求: MSG-03 督办提醒
 * 接收从 delay.remind.queue 死信转发过来的延迟消息，
 * 检查工单状态，若仍在审批中则生成督办站内信，并安排下次督办。
 * </p>
 * <p>
 * 督办策略:
 *   1. 查询 delayed_task 表获取工单信息
 *   2. Feign 调用 work-order-service 查询工单当前状态
 *   3. 若仍为 PENDING_APPROVE/APPROVING → 生成督办站内信
 *   4. remind_count + 1
 *   5. 若 remind_count < max_remind(3) → 重新发送延迟消息（2小时后再次提醒）
 *   6. 若 remind_count >= 3 → 升级通知上级领导
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RefreshScope
public class RemindConsumer {

    private final DelayedTaskMapper delayedTaskMapper;
    private final SysMessageMapper messageMapper;
    private final WorkOrderFeignClient workOrderFeignClient;
    private final MessageProducer messageProducer;

    /** 需要督办的工单状态集合 */
    private static final Set<String> REMINDABLE_STATUS = Set.of(
            "PENDING_APPROVE", "APPROVING");

    /** 最大督办次数，从 Nacos 读取，默认 3 */
    @Value("${remind.max-count:3}")
    private int maxRemindCount;

    /** 再次督办延迟时间（毫秒），从 Nacos 读取，默认 2 小时 */
    @Value("${remind.re-delay-ms:7200000}")
    private long reRemindDelayMs;

    @RabbitListener(queues = "remind.fire.queue")
    public void onMessage(Object message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            log.info("消费延迟督办消息: {}", message);

            // 解析消息体（Map 格式）
            Map<String, Object> msgMap = parseMessage(message);
            if (msgMap == null || msgMap.get("workOrderId") == null) {
                log.warn("督办消息格式无效，跳过: {}", message);
                channel.basicAck(deliveryTag, false);
                return;
            }

            Long workOrderId = Long.valueOf(String.valueOf(msgMap.get("workOrderId")));
            Long delayedTaskId = msgMap.get("delayedTaskId") != null ?
                    Long.valueOf(String.valueOf(msgMap.get("delayedTaskId"))) : null;

            // 1. Feign 查询工单当前状态
            String currentStatus = queryWorkOrderStatus(workOrderId);
            if (currentStatus == null) {
                log.warn("工单不存在或查询失败: workOrderId={}", workOrderId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2. 判断是否需要督办
            if (!REMINDABLE_STATUS.contains(currentStatus)) {
                log.info("工单已结束，无需督办: workOrderId={}, status={}", workOrderId, currentStatus);
                // 取消 delayed_task
                if (delayedTaskId != null) {
                    cancelDelayedTask(delayedTaskId);
                }
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. 生成督办站内信
            String orderNo = msgMap.get("orderNo") != null ? String.valueOf(msgMap.get("orderNo")) : "未知";
            Long approverId = msgMap.get("approverId") != null ?
                    Long.valueOf(String.valueOf(msgMap.get("approverId"))) : null;

            if (approverId != null) {
                SysMessage sysMsg = new SysMessage();
                sysMsg.setReceiverId(approverId);
                sysMsg.setTitle("审批督办提醒");
                sysMsg.setContent("工单[" + orderNo + "]等待您审批，请尽快处理");
                sysMsg.setMsgType("DELAY_REMIND");
                sysMsg.setBizType("WORK_ORDER");
                sysMsg.setBizId(workOrderId);
                sysMsg.setIsRead(false);
                messageMapper.insert(sysMsg);
                log.info("督办站内信已发送: approverId={}, workOrderId={}", approverId, workOrderId);
            }

            // 4. 更新 delayed_task 的 remind_count
            int remindCount = 0;
            int maxRemind = maxRemindCount;
            if (delayedTaskId != null) {
                DelayedTask task = delayedTaskMapper.selectById(delayedTaskId);
                if (task != null) {
                    remindCount = (task.getRemindCount() != null ? task.getRemindCount() : 0) + 1;
                    maxRemind = task.getMaxRemind() != null ? task.getMaxRemind() : maxRemindCount;

                    LambdaUpdateWrapper<DelayedTask> updateWrapper = new LambdaUpdateWrapper<>();
                    updateWrapper.eq(DelayedTask::getId, delayedTaskId)
                            .set(DelayedTask::getRemindCount, remindCount);

                    if (remindCount >= maxRemind) {
                        // 督办次数用尽，标记为已升级
                        updateWrapper.set(DelayedTask::getStatus, "ESCALATED");
                        updateWrapper.set(DelayedTask::getEscalationLevel,
                                (task.getEscalationLevel() != null ? task.getEscalationLevel() : 0) + 1);
                    }
                    delayedTaskMapper.update(null, updateWrapper);
                }
            }

            // 5. 决定是否继续督办
            if (remindCount < maxRemind) {
                // 重新发送延迟消息（2小时后再次提醒）
                Map<String, Object> reRemindMsg = new HashMap<>(msgMap);
                reRemindMsg.put("delayedTaskId", delayedTaskId);
                messageProducer.sendDelayRemind(reRemindMsg, reRemindDelayMs);
                log.info("督办已安排下次提醒: workOrderId={}, remindCount={}/{}", workOrderId, remindCount, maxRemind);
            } else {
                // 督办次数用尽，发送升级通知给提交人
                Long submitterId = msgMap.get("submitterId") != null ?
                        Long.valueOf(String.valueOf(msgMap.get("submitterId"))) : null;
                if (submitterId != null) {
                    SysMessage escalationMsg = new SysMessage();
                    escalationMsg.setReceiverId(submitterId);
                    escalationMsg.setTitle("审批超时升级通知");
                    escalationMsg.setContent("您的工单[" + orderNo + "]审批已超时，系统已升级处理");
                    escalationMsg.setMsgType("ESCALATION");
                    escalationMsg.setBizType("WORK_ORDER");
                    escalationMsg.setBizId(workOrderId);
                    escalationMsg.setIsRead(false);
                    messageMapper.insert(escalationMsg);
                }
                log.warn("督办次数用尽，已升级: workOrderId={}, remindCount={}", workOrderId, remindCount);
            }

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("消费督办消息失败: {}", e.getMessage(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ex) {
                log.error("NACK失败", ex);
            }
        }
    }

    /**
     * 查询工单当前状态（Feign 调用 work-order-service）
     */
    private String queryWorkOrderStatus(Long workOrderId) {
        try {
            R<Map<String, Object>> result = workOrderFeignClient.getStatus(workOrderId);
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                return String.valueOf(result.getData().get("status"));
            }
        } catch (Exception e) {
            log.error("查询工单状态失败: workOrderId={}, error={}", workOrderId, e.getMessage());
        }
        return null;
    }

    /**
     * 取消延迟任务
     */
    private void cancelDelayedTask(Long delayedTaskId) {
        LambdaUpdateWrapper<DelayedTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(DelayedTask::getId, delayedTaskId)
                .set(DelayedTask::getStatus, "CANCELLED");
        delayedTaskMapper.update(null, wrapper);
    }

    /**
     * 解析消息体为 Map（兼容不同格式）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMessage(Object message) {
        if (message instanceof Map) {
            return (Map<String, Object>) message;
        }
        if (message instanceof String) {
            log.warn("消息类型为 String，无法解析: {}", message);
        }
        return null;
    }
}
