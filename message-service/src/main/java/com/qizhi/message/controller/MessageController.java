package com.qizhi.message.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.core.result.R;
import com.qizhi.message.entity.DeadLetter;
import com.qizhi.message.entity.DelayedTask;
import com.qizhi.message.entity.SysMessage;
import com.qizhi.message.mapper.DeadLetterMapper;
import com.qizhi.message.mapper.DelayedTaskMapper;
import com.qizhi.message.mapper.SysMessageMapper;
import com.qizhi.message.producer.MessageProducer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Tag(name = "消息管理")
@RestController
@RequestMapping("/api/v1/message")
@RequiredArgsConstructor
@RefreshScope
public class MessageController {

    private final MessageProducer messageProducer;
    private final SysMessageMapper messageMapper;
    private final DeadLetterMapper deadLetterMapper;
    private final DelayedTaskMapper delayedTaskMapper;
    private final ObjectMapper objectMapper;

    /** 死信队列最大重试次数，超过则标记为需人工介入，Nacos: remind.dlq-max-retry */
    @Value("${remind.dlq-max-retry:3}")
    private int dlqMaxRetry;

    @Value("${remind.max-count:3}")
    private int maxRemindCount;

    @Operation(summary = "发送通知（供 Feign 调用）")
    @PostMapping("/send")
    public R<Void> send(@RequestBody Map<String, Object> request) {
        messageProducer.sendNotify(request);
        return R.ok();
    }

    /**
     * 发送延迟督办消息（供 work-order-service Feign 调用）
     * SRS 需求: MS-04/MS-05 根据优先级路由到对应延迟队列
     * URGENT → remind.delay.urgent.queue (TTL=60min)
     * NORMAL → remind.delay.normal.queue (TTL=240min)
     * LOW    → remind.delay.low.queue    (TTL=720min)
     */
    @Operation(summary = "发送延迟督办（供 Feign 调用）")
    @PostMapping("/send-delay-remind")
    public R<Void> sendDelayRemind(@RequestBody Map<String, Object> request) {
        String priority = request.get("priority") != null ? String.valueOf(request.get("priority")) : "NORMAL";
        Long workOrderId = Long.valueOf(String.valueOf(request.get("workOrderId")));
        Long approverId = Long.valueOf(String.valueOf(request.get("approverId")));
        DelayedTask task = delayedTaskMapper.selectOne(new LambdaQueryWrapper<DelayedTask>()
                .eq(DelayedTask::getWorkOrderId, workOrderId)
                .eq(DelayedTask::getApproverId, approverId)
                .eq(DelayedTask::getStatus, "PENDING")
                .last("LIMIT 1"));
        if (task == null) {
            task = new DelayedTask();
            task.setWorkOrderId(workOrderId);
            task.setOrderNo(String.valueOf(request.get("orderNo")));
            task.setApproverId(approverId);
            task.setPriority(priority);
            int delayMinutes = switch (priority.toUpperCase()) {
                case "URGENT" -> 60;
                case "LOW" -> 720;
                default -> 240;
            };
            task.setDelayMinutes(delayMinutes);
            task.setRemindCount(0);
            task.setMaxRemind(maxRemindCount);
            task.setEscalationLevel(0);
            task.setStatus("PENDING");
            task.setFireAt(LocalDateTime.now().plusMinutes(delayMinutes));
            delayedTaskMapper.insert(task);
        }
        request.put("delayedTaskId", task.getId());
        messageProducer.sendDelayRemind(request, priority);
        return R.ok();
    }

    @PostMapping("/reminder/scan")
    public R<Map<String, Object>> scanDueReminders() {
        List<DelayedTask> due = delayedTaskMapper.selectList(new LambdaQueryWrapper<DelayedTask>()
                .eq(DelayedTask::getStatus, "PENDING")
                .le(DelayedTask::getFireAt, LocalDateTime.now()));
        for (DelayedTask task : due) {
            Map<String, Object> message = new HashMap<>();
            message.put("delayedTaskId", task.getId());
            message.put("workOrderId", task.getWorkOrderId());
            message.put("orderNo", task.getOrderNo());
            message.put("approverId", task.getApproverId());
            message.put("priority", task.getPriority());
            messageProducer.sendReminderNow(message);
        }
        return R.ok(Map.of("scanned", due.size()));
    }

    @Operation(summary = "我的消息列表")
    @GetMapping("/list")
    public R<PageResult<SysMessage>> list(@RequestHeader("X-User-Id") Long userId,
                                          @RequestParam(required = false) Integer current,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer pageSize,
                                          @RequestParam(required = false) String msgType) {
        int c = (current != null) ? current : (page != null ? page : 1);
        int s = (size != null) ? size : (pageSize != null ? pageSize : 10);
        Page<SysMessage> pg = new Page<>(c, s);
        LambdaQueryWrapper<SysMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMessage::getReceiverId, userId);
        if (msgType != null && !msgType.isEmpty()) {
            wrapper.eq(SysMessage::getMsgType, msgType);
        }
        wrapper.orderByDesc(SysMessage::getCreatedAt);
        Page<SysMessage> result = messageMapper.selectPage(pg, wrapper);
        return R.ok(PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords()));
    }

    @Operation(summary = "未读消息数")
    @GetMapping("/unread-count")
    public R<Long> unreadCount(@RequestHeader("X-User-Id") Long userId) {
        Long count = messageMapper.selectCount(
                new LambdaQueryWrapper<SysMessage>()
                        .eq(SysMessage::getReceiverId, userId)
                        .eq(SysMessage::getIsRead, false));
        return R.ok(count);
    }

    @Operation(summary = "标记已读")
    @PutMapping("/read/{id}")
    public R<Void> markRead(@PathVariable Long id,
                            @RequestHeader("X-User-Id") Long userId) {
        LambdaUpdateWrapper<SysMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SysMessage::getId, id)
                .eq(SysMessage::getReceiverId, userId)
                .set(SysMessage::getIsRead, true)
                .set(SysMessage::getReadAt, LocalDateTime.now());
        messageMapper.update(null, wrapper);
        return R.ok();
    }

    @Operation(summary = "全部标记已读")
    @PutMapping("/read-all")
    public R<Void> markAllRead(@RequestHeader("X-User-Id") Long userId) {
        LambdaUpdateWrapper<SysMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SysMessage::getReceiverId, userId)
                .eq(SysMessage::getIsRead, false)
                .set(SysMessage::getIsRead, true)
                .set(SysMessage::getReadAt, LocalDateTime.now());
        messageMapper.update(null, wrapper);
        return R.ok();
    }

    @Operation(summary = "删除消息")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader("X-User-Id") Long userId) {
        messageMapper.delete(new LambdaQueryWrapper<SysMessage>()
                .eq(SysMessage::getId, id)
                .eq(SysMessage::getReceiverId, userId));
        return R.ok();
    }

    @Operation(summary = "死信列表")
    @GetMapping("/dlq/list")
    public R<PageResult<DeadLetter>> dlqList(@RequestParam(required = false) Integer current,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer pageSize) {
        int c = (current != null) ? current : (page != null ? page : 1);
        int s = (size != null) ? size : (pageSize != null ? pageSize : 10);
        Page<DeadLetter> pg = new Page<>(c, s);
        Page<DeadLetter> result = deadLetterMapper.selectPage(pg,
                new LambdaQueryWrapper<DeadLetter>().orderByDesc(DeadLetter::getCreatedAt));
        return R.ok(PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords()));
    }

    @Operation(summary = "死信手动重试")
    @PostMapping("/dlq/retry/{id}")
    public R<Void> dlqRetry(@PathVariable Long id) {
        return doRetryDeadLetter(id);
    }

    // ==================== 前端适配端点 ====================

    /**
     * 标记已读（前端 RESTful 风格 /{id}/read）
     */
    @Operation(summary = "标记已读（前端适配）")
    @PutMapping("/{id}/read")
    public R<Void> markReadAlias(@PathVariable Long id,
                                 @RequestHeader("X-User-Id") Long userId) {
        return markRead(id, userId);
    }

    /**
     * 死信列表（前端路径 /dead-letter/list）
     */
    @Operation(summary = "死信列表（前端适配）")
    @GetMapping("/dead-letter/list")
    public R<PageResult<DeadLetter>> deadLetterList(@RequestParam(required = false) Integer current,
                                                      @RequestParam(required = false) Integer size,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer pageSize) {
        return dlqList(current, size, page, pageSize);
    }

    /**
     * 死信手动重试（前端路径 /dead-letter/{id}/retry）
     */
    @Operation(summary = "死信重试（前端适配）")
    @PostMapping("/dead-letter/{id}/retry")
    public R<Void> deadLetterRetry(@PathVariable Long id) {
        return doRetryDeadLetter(id);
    }

    @Operation(summary = "死信详情")
    @GetMapping("/dead-letter/{id}")
    public R<DeadLetter> deadLetterDetail(@PathVariable Long id) {
        DeadLetter record = deadLetterMapper.selectById(id);
        return record == null ? R.fail("死信记录不存在") : R.ok(record);
    }

    private R<Void> doRetryDeadLetter(Long id) {
        DeadLetter dl = deadLetterMapper.selectById(id);
        if (dl == null) {
            return R.fail("死信记录不存在");
        }
        // SRS 场景七: 死信手动重试最多 dlqMaxRetry 次，超过则标记为“需人工介入”
        if (dl.getRetryCount() != null && dl.getRetryCount() >= dlqMaxRetry) {
            dl.setStatus("FAILED");
            deadLetterMapper.updateById(dl);
            return R.fail("该死信已达到最大重试次数");
        }
        int nextRetry = (dl.getRetryCount() != null ? dl.getRetryCount() : 0) + 1;
        try {
            Map<String, Object> body = objectMapper.readValue(
                    dl.getMessageBody(), new TypeReference<Map<String, Object>>() {});
            if (body.get("receiverId") == null || body.get("title") == null) {
                throw new IllegalArgumentException("消息缺少 receiverId/title");
            }
            messageProducer.sendNotify(body);
            dl.setRetryCount(nextRetry);
            dl.setStatus("RESOLVED");
            dl.setErrorReason(null);
            dl.setRetriedAt(LocalDateTime.now());
            deadLetterMapper.updateById(dl);
            return R.ok();
        } catch (Exception e) {
            dl.setRetryCount(nextRetry);
            dl.setStatus(nextRetry >= dlqMaxRetry ? "FAILED" : "UNRESOLVED");
            dl.setErrorReason("人工重投失败: " + e.getMessage());
            dl.setRetriedAt(LocalDateTime.now());
            deadLetterMapper.updateById(dl);
            return R.fail(dl.getErrorReason());
        }
    }
}
