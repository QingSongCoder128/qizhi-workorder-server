package com.qizhi.workorder.service;

import com.qizhi.common.core.result.PageResult;
import com.qizhi.workorder.dto.WorkOrderSubmitDTO;
import com.qizhi.workorder.entity.WorkOrder;
import com.qizhi.workorder.vo.WorkOrderDetailVO;

import java.util.List;
import java.util.Map;

public interface WorkOrderService {

    WorkOrder submit(WorkOrderSubmitDTO dto, Long userId, String username);

    /**
     * 本地事务：保存工单 + 附件（供 submit 内部调用，通过 self 代理确保 @Transactional 生效）
     */
    WorkOrder saveOrder(WorkOrderSubmitDTO dto, Long userId, String username);

    /**
     * Seata 分布式事务：更新工单状态 + 创建审批单（供 submit 内部调用）
     */
    void updateStatusAndCreateApproval(WorkOrder order, Long userId, String username);

    PageResult<WorkOrder> getMyList(Long userId, Integer current, Integer size, String status);

    WorkOrderDetailVO getDetail(Long id);

    void resubmit(Long id, WorkOrderSubmitDTO dto, Long userId, String username);

    /** 根据 ID 查询工单 */
    WorkOrder getById(Long id);

    /** 工单导出列表（支持部门、类型、时间范围筛选） */
    List<Map<String, Object>> getExportList(String deptCode, String type, String startDate, String endDate);

    String generateOrderNo();
}
