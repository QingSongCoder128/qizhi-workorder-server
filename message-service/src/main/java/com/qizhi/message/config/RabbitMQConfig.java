package com.qizhi.message.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

/**
 * RabbitMQ 交换机与队列配置
 * <p>
 * 队列结构:
 *   1. notify.queue — 实时通知（direct.exchange + routingKey=notify）
 *   2. remind.delay.urgent.queue — URGENT 延迟督办（固定 TTL，过期死信转发到 remind.fire.queue）
 *   3. remind.delay.normal.queue — NORMAL 延迟督办
 *   4. remind.delay.low.queue — LOW 延迟督办
 *   5. remind.delay.retry.queue — 重复督办延迟队列
 *   6. remind.fire.queue — 督办触发（remind.fire.exchange + routingKey=remind.fire）
 *   7. dlq.queue — 死信队列（dlx.exchange + routingKey=dead-letter）
 * </p>
 * <p>
 * 注意: 延迟队列的 x-message-ttl 在声明时固定，修改 Nacos 配置后需重启服务。
 * 如果同名队列已存在且 TTL 不同，需先手动删除旧队列再重启（开发环境）。
 * </p>
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    // ========== 延迟督办 TTL（从 Nacos 读取，单位：分钟） ==========
    @Value("${remind.delay.urgent-minutes:60}")
    private long urgentDelayMinutes;

    @Value("${remind.delay.normal-minutes:240}")
    private long normalDelayMinutes;

    @Value("${remind.delay.low-minutes:720}")
    private long lowDelayMinutes;

    /** 重复督办间隔（毫秒），默认 2 小时 */
    @Value("${remind.re-delay-ms:7200000}")
    private long reDelayMs;

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        // MS-02: 生产者确认回调，消息到达 Exchange 后触发
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("消息发送失败(未到达Exchange): correlationData={}, cause={}", correlationData, cause);
            }
        });
        // 消息无法路由到队列时触发（mandatory=true 时生效）
        template.setReturnsCallback(returned -> {
            log.error("消息无法路由到队列: exchange={}, routingKey={}, replyCode={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText());
        });
        template.setMandatory(true);
        return template;
    }

    // ========== 实时通知 ==========

    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange("direct.exchange", true, false);
    }

    @Bean
    public Queue notifyQueue() {
        // MS-07: 消费失败(nack)的消息通过死信交换机转入 dlq.queue
        return QueueBuilder.durable("notify.queue")
                .deadLetterExchange("dlx.exchange")
                .deadLetterRoutingKey("dead-letter")
                .build();
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

    // ========== 延迟督办（按优先级拆分固定 TTL 队列，解决队头阻塞） ==========
    // 流程: Producer → remind.delay.{priority}.queue (固定TTL过期) → remind.fire.exchange → remind.fire.queue

    /** 延迟督办发布交换机（Producer 发送延迟消息的入口） */
    @Bean
    public DirectExchange remindDelayExchange() {
        return new DirectExchange("remind.delay.exchange", true, false);
    }

    /** 督办触发交换机（延迟队列死信转发的目标） */
    @Bean
    public DirectExchange remindFireExchange() {
        return new DirectExchange("remind.fire.exchange", true, false);
    }

    /**
     * URGENT 延迟队列（固定 TTL = urgent-minutes）
     * 无消费者，消息过期后死信转发到 remind.fire.queue
     */
    @Bean
    public Queue remindDelayUrgentQueue() {
        long ttl = urgentDelayMinutes * 60 * 1000;
        log.info("声明延迟队列 remind.delay.urgent.queue, TTL={}ms ({}min)", ttl, urgentDelayMinutes);
        return QueueBuilder.durable("remind.delay.urgent.queue")
                .withArgument("x-message-ttl", ttl)
                .deadLetterExchange("remind.fire.exchange")
                .deadLetterRoutingKey("remind.fire")
                .build();
    }

    /**
     * NORMAL 延迟队列（固定 TTL = normal-minutes）
     */
    @Bean
    public Queue remindDelayNormalQueue() {
        long ttl = normalDelayMinutes * 60 * 1000;
        log.info("声明延迟队列 remind.delay.normal.queue, TTL={}ms ({}min)", ttl, normalDelayMinutes);
        return QueueBuilder.durable("remind.delay.normal.queue")
                .withArgument("x-message-ttl", ttl)
                .deadLetterExchange("remind.fire.exchange")
                .deadLetterRoutingKey("remind.fire")
                .build();
    }

    /**
     * LOW 延迟队列（固定 TTL = low-minutes）
     */
    @Bean
    public Queue remindDelayLowQueue() {
        long ttl = lowDelayMinutes * 60 * 1000;
        log.info("声明延迟队列 remind.delay.low.queue, TTL={}ms ({}min)", ttl, lowDelayMinutes);
        return QueueBuilder.durable("remind.delay.low.queue")
                .withArgument("x-message-ttl", ttl)
                .deadLetterExchange("remind.fire.exchange")
                .deadLetterRoutingKey("remind.fire")
                .build();
    }

    /**
     * 重复督办延迟队列（固定 TTL = re-delay-ms，用于第2/3次督办）
     */
    @Bean
    public Queue remindDelayRetryQueue() {
        log.info("声明延迟队列 remind.delay.retry.queue, TTL={}ms", reDelayMs);
        return QueueBuilder.durable("remind.delay.retry.queue")
                .withArgument("x-message-ttl", reDelayMs)
                .deadLetterExchange("remind.fire.exchange")
                .deadLetterRoutingKey("remind.fire")
                .build();
    }

    // --- 延迟队列绑定到 remind.delay.exchange ---

    @Bean
    public Binding remindDelayUrgentBinding(@Qualifier("remindDelayUrgentQueue") Queue queue,
                                             @Qualifier("remindDelayExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("remind.delay.urgent");
    }

    @Bean
    public Binding remindDelayNormalBinding(@Qualifier("remindDelayNormalQueue") Queue queue,
                                             @Qualifier("remindDelayExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("remind.delay.normal");
    }

    @Bean
    public Binding remindDelayLowBinding(@Qualifier("remindDelayLowQueue") Queue queue,
                                          @Qualifier("remindDelayExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("remind.delay.low");
    }

    @Bean
    public Binding remindDelayRetryBinding(@Qualifier("remindDelayRetryQueue") Queue queue,
                                            @Qualifier("remindDelayExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with("remind.delay.retry");
    }

    // --- 督办触发队列（接收所有延迟队列的死信转发） ---

    @Bean
    public Queue remindFireQueue() {
        // 消费失败(nack)的消息通过死信交换机转入 dlq.queue
        return QueueBuilder.durable("remind.fire.queue")
                .deadLetterExchange("dlx.exchange")
                .deadLetterRoutingKey("dead-letter")
                .build();
    }

    @Bean
    public Binding remindFireBinding(@Qualifier("remindFireQueue") Queue remindFireQueue,
                                      @Qualifier("remindFireExchange") DirectExchange remindFireExchange) {
        return BindingBuilder.bind(remindFireQueue).to(remindFireExchange).with("remind.fire");
    }
}
