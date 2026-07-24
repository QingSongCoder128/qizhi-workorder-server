package com.qizhi.message.consumer;

import com.qizhi.message.entity.DeadLetter;
import com.qizhi.message.mapper.DeadLetterMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqConsumer {

    private final DeadLetterMapper deadLetterMapper;

    @RabbitListener(queues = "dlq.queue")
    public void onMessage(Object message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            log.warn("消费死信消息: {}", message);

            DeadLetter deadLetter = new DeadLetter();
            deadLetter.setExchangeName("direct.exchange");
            deadLetter.setQueueName("unknown");
            deadLetter.setMessageBody(String.valueOf(message));
            deadLetter.setRetryCount(0);
            deadLetter.setStatus("UNRESOLVED");
            deadLetterMapper.insert(deadLetter);

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("消费死信消息失败: {}", e.getMessage(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ex) {
                log.error("NACK失败", ex);
            }
        }
    }
}
