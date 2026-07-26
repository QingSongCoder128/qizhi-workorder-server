package com.qizhi.approve.service;

import com.qizhi.approve.dto.ApprovalActionDTO;
import com.qizhi.approve.dto.ApprovalCreateDTO;
import com.qizhi.approve.entity.ApprovalInstance;
import com.qizhi.approve.entity.ApprovalRecord;
import com.qizhi.common.core.result.PageResult;

import java.util.List;
import java.util.Map;

/**
 * 审批管理服务接口
 * <p>
 * SRS 需求: AP-01 ~ AP-04
 * 覆盖审批单创建（基于模板）、审批操作（通过/驳回/转交/加签/减签）、
 * 待办查询、审批记录查询、超时检测等核心功能。
 * </p>
 */
public interface ApproveService {

    /**
     * 创建审批单（供 work-order-service Feign 调用）
     * <p>
     * 流程：
     *   1. 根据 departmentCode + workType 匹配审批模板
     *   2. 读取模板节点生成多级 ApprovalRecord（status=PENDING）
     *   3. 创建 ApprovalInstance 并设置当前节点为第1级
     * </p>
     */
    ApprovalInstance createApproval(ApprovalCreateDTO dto);

    /** 待审批列表（按审批人过滤，状态为 PENDING/APPROVING，支持排序） */
    PageResult<ApprovalInstance> getPending(Long approverId, Integer current, Integer size, String sortBy, String order);

    /** 待审批统计（总数/紧急数/超时数/今日已处理数，供前端统计卡片展示） */
    Map<String, Object> getPendingStats(Long approverId);

    /** 统计处于 APPROVING（审批中，首节点已通过、流转中）状态的审批实例数，供统计看板使用 */
    long countApproving();

    /**
     * 审批操作（五种类型）
     * <p>
     * APPROVED: 通过当前节点，进入下一级或完结<br>
     * REJECTED: 驳回，审批单结束<br>
     * TRANSFER: 转交，变更当前节点审批人<br>
     * ADD_NODE: 加签，在当前节点后插入新节点<br>
     * REMOVE_NODE: 减签，跳过指定后续节点
     * </p>
     */
    void action(ApprovalActionDTO dto, Long operatorId, String operatorName);

    /** 获取审批单的全部审批记录（节点列表） */
    List<ApprovalRecord> getRecords(Long approvalId);

    /** 根据工单 ID 查询审批记录（供 Feign 调用） */
    List<ApprovalRecord> getRecordsByWorkOrderId(Long workOrderId);

    /**
     * 审批详情（前端适配）
     * 返回 {workOrder: {orderNo, title, ...}, nodes: [ApprovalRecord...] }
     */
    Map<String, Object> getDetail(Long approvalInstanceId);

    /**
     * 处理超时审批单（定时任务调用）
     * 查找超过 48 小时仍为 PENDING/APPROVING 状态的审批单，
     * 记录超时并发送督办通知。
     */
    void handleTimeoutApprovals();
}
