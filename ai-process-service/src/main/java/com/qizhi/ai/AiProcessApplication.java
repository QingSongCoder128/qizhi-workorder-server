package com.qizhi.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.qizhi.ai", "com.qizhi.common"})
@EnableDiscoveryClient
@MapperScan("com.qizhi.ai.mapper")
public class AiProcessApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiProcessApplication.class, args);
    }
}
