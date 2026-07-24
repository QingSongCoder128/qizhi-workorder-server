package com.qizhi.message;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 消息服务启动类
 * <p>
 * 功能: 站内信通知、RabbitMQ 消息消费、延迟督办、死信队列管理
 * </p>
 */
@SpringBootApplication(scanBasePackages = {"com.qizhi.message", "com.qizhi.common"})
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.qizhi.message.mapper")
public class MessageApplication {

    public static void main(String[] args) {
        SpringApplication.run(MessageApplication.class, args);
    }
}
