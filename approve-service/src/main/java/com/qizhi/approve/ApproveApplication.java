package com.qizhi.approve;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 审批服务启动类
 * <p>
 * 功能: 审批模板管理、多级审批流程、转交/加签/减签、超时督办
 * </p>
 */
@SpringBootApplication(scanBasePackages = {"com.qizhi.approve", "com.qizhi.common"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
@MapperScan("com.qizhi.approve.mapper")
public class ApproveApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApproveApplication.class, args);
    }
}
