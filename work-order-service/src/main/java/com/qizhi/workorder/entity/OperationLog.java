package com.qizhi.workorder.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单服务写入的跨库操作日志。
 *
 * <p>日志表保留在 qizhi_log 库，但不再为它单独部署微服务。</p>
 */
@Data
public class OperationLog {
    private Long id;
    private Long userId;
    private String userName;
    private String module;
    private String action;
    private String targetType;
    private Long targetId;
    private String detail;
    private String seataXid;
    private LocalDateTime createdAt;
}
