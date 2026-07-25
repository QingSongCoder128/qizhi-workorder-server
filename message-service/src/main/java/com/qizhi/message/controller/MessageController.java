package com.qizhi.message.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qizhi.common.core.result.PageResult;
import com.qizhi.common.core.result.R;
import com.qizhi.message.entity.DeadLetter;
import com.qizhi.message.entity.SysMessage;
import com.qizhi.message.mapper.DeadLetterMapper;
import com.qizhi.message.mapper.SysMessageMapper;
import com.qizhi.message.producer.MessageProducer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@Tag(name = "消息管理")
@RestController
@RequestMapping("/api/v1/message")
@RequiredArgsConstructor
@RefreshScope
public class MessageController {

    private final MessageProducer messageProducer;
    private final SysMessageMapper messageMapper;
    private final DeadLetterMapper deadLetterMapper;

    /** 死信队列最大重试次数，超过则标记为需人工介入，Nacos: remind.dlq-max-retry */
    @Value("${remind.dlq-max-retry:3}")
    private int dlqMaxRetry;

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
        messageProducer.sendDelayRemind(request, priority);
        return R.ok();
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
    public R<Void> markRead(@PathVariable Long id) {
        LambdaUpdateWrapper<SysMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SysMessage::getId, id)
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
    public R<Void> delete(@PathVariable Long id) {
        messageMapper.deleteById(id);
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
    public R<Void> markReadAlias(@PathVariable Long id) {
        return markRead(id);
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

    private R<Void> doRetryDeadLetter(Long id) {
        DeadLetter dl = deadLetterMapper.selectById(id);
        if (dl == null) {
            return R.fail("死信记录不存在");
        }
        // SRS 场景七: 死信手动重试最多 dlqMaxRetry 次，超过则标记为“需人工介入”
        if (dl.getRetryCount() != null && dl.getRetryCount() >= dlqMaxRetry) {
            dl.setStatus("MANUAL");
            deadLetterMapper.updateById(dl);
            return R.fail("该死信已重试" + dlqMaxRetry + "次仍失败，已标记为需人工介入");
        }
        // 重新发送到 notify 队列
        messageProducer.sendNotify(dl.getMessageBody());
        dl.setRetryCount((dl.getRetryCount() != null ? dl.getRetryCount() : 0) + 1);
        dl.setStatus(dl.getRetryCount() >= dlqMaxRetry ? "MANUAL" : "RESOLVED");
        dl.setRetriedAt(LocalDateTime.now());
        deadLetterMapper.updateById(dl);
        return R.ok();
    }
}
