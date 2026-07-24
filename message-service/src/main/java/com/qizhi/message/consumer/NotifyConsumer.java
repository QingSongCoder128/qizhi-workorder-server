package com.qizhi.message.consumer;

import com.qizhi.message.entity.SysMessage;
import com.qizhi.message.mapper.SysMessageMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

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
            log.info("消费通知消息: {}", msgMap);

            SysMessage sysMessage = new SysMessage();
            sysMessage.setReceiverId(Long.valueOf(String.valueOf(msgMap.get("receiverId"))));
            sysMessage.setTitle(String.valueOf(msgMap.getOrDefault("title", "")));
            sysMessage.setContent(String.valueOf(msgMap.getOrDefault("content", "")));
            sysMessage.setMsgType(String.valueOf(msgMap.getOrDefault("msgType", "SYSTEM")));
            sysMessage.setBizType(msgMap.get("bizType") != null ? String.valueOf(msgMap.get("bizType")) : null);
            sysMessage.setBizId(msgMap.get("bizId") != null ? Long.valueOf(String.valueOf(msgMap.get("bizId"))) : null);
            sysMessage.setIsRead(false);
            messageMapper.insert(sysMessage);

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("消费通知消息失败: {}", e.getMessage(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ex) {
                log.error("NACK失败", ex);
            }
        }
    }
}
