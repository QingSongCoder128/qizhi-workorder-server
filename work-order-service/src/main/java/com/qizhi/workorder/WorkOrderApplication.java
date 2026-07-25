package com.qizhi.workorder;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.qizhi.workorder", "com.qizhi.common"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
@MapperScan("com.qizhi.workorder.mapper")
public class WorkOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkOrderApplication.class, args);
    }
}
