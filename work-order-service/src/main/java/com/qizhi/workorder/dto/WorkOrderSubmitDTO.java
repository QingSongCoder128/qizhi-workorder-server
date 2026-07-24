package com.qizhi.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class WorkOrderSubmitDTO {

    @NotBlank(message = "工单类型不能为空")
    private String type;

    @NotBlank(message = "标题不能为空")
    private String title;

    @NotBlank(message = "详情不能为空")
    private String detail;

    @NotBlank(message = "部门编码不能为空")
    private String departmentCode;

    private Boolean urgent = false;

    private List<AttachmentInfo> attachments;

    @Data
    public static class AttachmentInfo {
        private String fileName;
        private String fileUrl;
        private String fileType;
        private Long fileSize;
    }
}
