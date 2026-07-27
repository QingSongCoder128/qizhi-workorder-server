package com.qizhi.workorder.service;

import com.qizhi.common.core.result.PageResult;
import com.qizhi.workorder.dto.WorkOrderSubmitDTO;
import com.qizhi.workorder.entity.WorkOrder;
import com.qizhi.workorder.vo.WorkOrderDetailVO;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

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

    PageResult<WorkOrder> getMyList(Long userId, Integer current, Integer size, String status, String type, String priority, String keyword);

    /** 管理员全量工单列表（支持状态/类型/关键词筛选） */
    PageResult<WorkOrder> getAdminList(Integer current, Integer size, String status, String type, String priority, String keyword);

    WorkOrderDetailVO getDetail(Long id);

    void resubmit(Long id, WorkOrderSubmitDTO dto, Long userId, String username);

    /** 根据 ID 查询工单 */
    WorkOrder getById(Long id);

    /** 工单导出列表（支持部门、类型、时间范围筛选） */
    List<Map<String, Object>> getExportList(String deptCode, String type, String startDate, String endDate);

    /** 实时统计看板数据（供 statistics-service Feign 调用） */
    Map<String, Object> getStats(String deptCode, String startDate, String endDate, String workType);

    /** 撤销工单（仅待审批状态可撤销） */
    void revoke(Long id, Long userId);

    /** 重试处理（仅 PENDING_AI 状态，提交人或管理员一键重新触发 AI + 审批链路） */
    void retryProcess(Long id, Long userId, String username, String role);

    /** 更新工单状态（供 approve-service 回调） */
    void updateStatus(Long id, String status, String remark);

    /** 附件上传，返回文件访问 URL */
    Map<String, String> uploadAttachment(org.springframework.web.multipart.MultipartFile file);

    /** 安全解析模块内附件文件，禁止路径越界。 */
    Path resolveAttachment(String filename);

    String generateOrderNo();
}
