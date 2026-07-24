package com.qizhi.statistics.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

/**
 * 工单导出 Excel DTO
 * <p>
 * SRS 需求: ST-03 Excel 导出
 * 使用 EasyExcel 注解定义导出字段和列宽。
 * </p>
 */
@Data
public class WorkOrderExportDTO {

    /** 工单编号 */
    @ExcelProperty("工单编号")
    @ColumnWidth(20)
    private String orderNo;

    /** 工单标题 */
    @ExcelProperty("工单标题")
    @ColumnWidth(30)
    private String title;

    /** 提交人姓名 */
    @ExcelProperty("提交人")
    @ColumnWidth(15)
    private String submitterName;

    /** 工单类型 */
    @ExcelProperty("工单类型")
    @ColumnWidth(15)
    private String type;

    /** 优先级 */
    @ExcelProperty("优先级")
    @ColumnWidth(10)
    private String priority;

    /** 当前状态 */
    @ExcelProperty("状态")
    @ColumnWidth(15)
    private String status;

    /** 当前审批人 */
    @ExcelProperty("当前审批人")
    @ColumnWidth(15)
    private String currentApproverName;

    /** 部门编码 */
    @ExcelProperty("部门")
    @ColumnWidth(15)
    private String departmentCode;

    /** 创建时间 */
    @ExcelProperty("创建时间")
    @ColumnWidth(20)
    private String createdAt;

    /** 办结时间 */
    @ExcelProperty("办结时间")
    @ColumnWidth(20)
    private String completedAt;
}
