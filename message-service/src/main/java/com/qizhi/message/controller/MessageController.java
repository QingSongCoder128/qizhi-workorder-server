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
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@Tag(name = "消息管理")
@RestController
@RequestMapping("/api/v1/message")
@RequiredArgsConstructor
public class MessageController {

    private final MessageProducer messageProducer;
    private final SysMessageMapper messageMapper;
    private final DeadLetterMapper deadLetterMapper;

    @Operation(summary = "发送通知（供 Feign 调用）")
    @PostMapping("/send")
    public R<Void> send(@RequestBody Map<String, Object> request) {
        messageProducer.sendNotify(request);
        return R.ok();
    }

    @Operation(summary = "我的消息列表")
    @GetMapping("/list")
    public R<PageResult<SysMessage>> list(@RequestHeader("X-User-Id") Long userId,
                                          @RequestParam(defaultValue = "1") Integer current,
                                          @RequestParam(defaultValue = "10") Integer size) {
        Page<SysMessage> page = new Page<>(current, size);
        LambdaQueryWrapper<SysMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMessage::getReceiverId, userId)
                .orderByDesc(SysMessage::getCreatedAt);
        Page<SysMessage> result = messageMapper.selectPage(page, wrapper);
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
    public R<PageResult<DeadLetter>> dlqList(@RequestParam(defaultValue = "1") Integer current,
                                             @RequestParam(defaultValue = "10") Integer size) {
        Page<DeadLetter> page = new Page<>(current, size);
        Page<DeadLetter> result = deadLetterMapper.selectPage(page,
                new LambdaQueryWrapper<DeadLetter>().orderByDesc(DeadLetter::getCreatedAt));
        return R.ok(PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords()));
    }

    @Operation(summary = "死信手动重试")
    @PostMapping("/dlq/retry/{id}")
    public R<Void> dlqRetry(@PathVariable Long id) {
        DeadLetter dl = deadLetterMapper.selectById(id);
        if (dl == null) {
            return R.fail("死信记录不存在");
        }
        // 重新发送到 notify 队列
        messageProducer.sendNotify(dl.getMessageBody());
        dl.setRetryCount(dl.getRetryCount() + 1);
        dl.setStatus("RESOLVED");
        dl.setRetriedAt(LocalDateTime.now());
        deadLetterMapper.updateById(dl);
        return R.ok();
    }
}
