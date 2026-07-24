package com.qizhi.workorder.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderDetailVO {

    private Long id;
    private String orderNo;
    private Long submitterId;
    private String submitterName;
    private String type;
    private String title;
    private String detail;
    private String departmentCode;
    private Boolean urgent;
    private String priority;
    private String status;
    private String currentApproverName;
    private String currentNode;

    // AI 结果
    private String aiCategory;
    private Double aiConfidence;
    private String aiPriorityReason;
    private String aiSuggestion;
    private String aiSensitiveWords;

    private Integer versionNo;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    // 审批时间线
    private List<ApprovalTimeline> timeline;

    // 状态历史
    private List<StatusHistory> history;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalTimeline {
        private String nodeName;
        private String action;
        private String operatorName;
        private String opinion;
        private LocalDateTime operatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusHistory {
        private String fromStatus;
        private String toStatus;
        private String operatorName;
        private String remark;
        private LocalDateTime createdAt;
    }
}
