package com.qizhi.statistics.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日统计汇总实体
 * <p>
 * 对应 stat_daily_summary 表，按部门+工单类型每日汇总。
 * </p>
 */
@Data
@TableName("stat_daily_summary")
public class StatDailySummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 统计日期 */
    private LocalDate statDate;

    /** 部门编码 */
    private String deptCode;

    /** 工单类型 */
    private String workType;

    /** 当日总工单数 */
    private Integer totalCount;

    /** 待处理数 */
    private Integer pendingCount;

    /** 已完成数 */
    private Integer completedCount;

    /** 已驳回数 */
    private Integer rejectedCount;

    /** 超时数 */
    private Integer timeoutCount;

    /** 平均审批时长（分钟） */
    private Integer avgApproveMinutes;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
