package com.qizhi.message.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 交换机与队列配置
 * <p>
 * 队列结构:
 *   1. notify.queue — 实时通知（direct.exchange + routingKey=notify）
 *   2. delay.remind.queue — 延迟督办（TTL 过期后通过死信转发到 remind.fire.queue）
 *   3. remind.fire.queue — 督办触发（direct.exchange + routingKey=remind.fire）
 *   4. dlq.queue — 死信队列（dlx.exchange + routingKey=dead-letter）
 * </p>
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    // ========== 实时通知 ==========

    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange("direct.exchange", true, false);
    }

    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable("notify.queue").build();
    }

    @Bean
    public Binding notifyBinding(@Qualifier("notifyQueue") Queue notifyQueue, @Qualifier("directExchange") DirectExchange directExchange) {
        return BindingBuilder.bind(notifyQueue).to(directExchange).with("notify");
    }

    // ========== 死信交换机 ==========

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange("dlx.exchange", true, false);
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable("dlq.queue").build();
    }

    @Bean
    public Binding dlqBinding(@Qualifier("dlqQueue") Queue dlqQueue, @Qualifier("dlxExchange") DirectExchange dlxExchange) {
        return BindingBuilder.bind(dlqQueue).to(dlxExchange).with("dead-letter");
    }

    // ========== 延迟督办（使用 TTL + 死信转发方案） ==========
    // 流程: Producer → delay.remind.queue (TTL过期) → dlx.exchange → remind.fire.queue

    /**
     * 延迟督办队列
     * <p>
     * 消息写入后设置 TTL，过期后通过死信交换机转发到 remind.fire.queue。
     * 绑定到 direct.exchange 以便 Producer 可以通过 routingKey="delay.remind" 发送。
     * </p>
     */
    @Bean
    public Queue delayRemindQueue() {
        return QueueBuilder.durable("delay.remind.queue")
                .deadLetterExchange("direct.exchange")
                .deadLetterRoutingKey("remind.fire")
                .build();
    }

    /**
     * 将 delay.remind.queue 绑定到 direct.exchange，routingKey=delay.remind
     * 使 Producer 可以通过 direct.exchange + "delay.remind" 路由键发送消息
     */
    @Bean
    public Binding delayRemindBinding(@Qualifier("delayRemindQueue") Queue delayRemindQueue,
                                       @Qualifier("directExchange") DirectExchange directExchange) {
        return BindingBuilder.bind(delayRemindQueue).to(directExchange).with("delay.remind");
    }

    /**
     * 督办触发队列（接收从 delay.remind.queue 死信转发过来的消息）
     */
    @Bean
    public Queue remindFireQueue() {
        return QueueBuilder.durable("remind.fire.queue").build();
    }

    @Bean
    public Binding remindFireBinding(@Qualifier("remindFireQueue") Queue remindFireQueue,
                                      @Qualifier("directExchange") DirectExchange directExchange) {
        return BindingBuilder.bind(remindFireQueue).to(directExchange).with("remind.fire");
    }
}
