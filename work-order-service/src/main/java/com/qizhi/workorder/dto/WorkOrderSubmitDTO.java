package com.qizhi.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
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

    private List<@Valid AttachmentInfo> attachments;

    @Data
    public static class AttachmentInfo {
        @NotBlank(message = "附件名称不能为空")
        private String fileName;

        @NotBlank(message = "附件地址不能为空")
        private String fileUrl;

        @NotBlank(message = "附件类型不能为空")
        private String fileType;

        private Long fileSize;
    }
}
