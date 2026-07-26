package com.qizhi.message.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qizhi.message.entity.SysMessage;
import com.qizhi.message.mapper.SysMessageMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyConsumer {

    private final SysMessageMapper messageMapper;

    @RabbitListener(queues = "notify.queue")
    public void onMessage(Map<String, Object> msgMap, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            SysMessage sysMessage = new SysMessage();
            sysMessage.setReceiverId(Long.valueOf(String.valueOf(msgMap.get("receiverId"))));
            sysMessage.setTitle(String.valueOf(msgMap.getOrDefault("title", "")));
            sysMessage.setContent(String.valueOf(msgMap.getOrDefault("content", "")));
            sysMessage.setMsgType(String.valueOf(msgMap.getOrDefault("msgType", "SYSTEM")));
            sysMessage.setBizType(msgMap.get("bizType") == null
                    ? null : String.valueOf(msgMap.get("bizType")));
            sysMessage.setBizId(msgMap.get("bizId") == null
                    ? null : Long.valueOf(String.valueOf(msgMap.get("bizId"))));
            sysMessage.setMessageKey(resolveMessageKey(msgMap, sysMessage));
            sysMessage.setIsRead(false);

            if (messageMapper.selectCount(Wrappers.<SysMessage>lambdaQuery()
                    .eq(SysMessage::getMessageKey, sysMessage.getMessageKey())) > 0) {
                log.info("忽略重复通知消息, messageKey={}", sysMessage.getMessageKey());
                channel.basicAck(deliveryTag, false);
                return;
            }

            messageMapper.insert(sysMessage);
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException duplicate) {
            log.info("并发重复通知已由唯一约束拦截");
            acknowledge(channel, deliveryTag);
        } catch (Exception consumeError) {
            log.error("通知消息消费失败: {}", consumeError.getMessage());
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception nackError) {
                log.error("消息 NACK 失败: {}", nackError.getMessage());
            }
        }
    }

    private void acknowledge(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception ackError) {
            log.error("重复消息 ACK 失败: {}", ackError.getMessage());
        }
    }

    private String resolveMessageKey(Map<String, Object> msgMap, SysMessage message) {
        Object suppliedKey = msgMap.get("messageKey");
        if (suppliedKey != null && !String.valueOf(suppliedKey).isBlank()) {
            return String.valueOf(suppliedKey);
        }
        String source = String.join("|",
                String.valueOf(message.getReceiverId()),
                String.valueOf(message.getMsgType()),
                String.valueOf(message.getBizType()),
                String.valueOf(message.getBizId()),
                String.valueOf(message.getTitle()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }
}
