package com.qizhi.log;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@MapperScan("com.qizhi.log.mapper")
@SpringBootApplication(scanBasePackages = {"com.qizhi.log", "com.qizhi.common"})
public class OperationLogApplication {

    public static void main(String[] args) {
        SpringApplication.run(OperationLogApplication.class, args);
    }
}
