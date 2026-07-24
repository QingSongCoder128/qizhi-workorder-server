package com.qizhi.ai.dto;

import lombok.Data;

@Data
public class AiProcessRequest {

    private Long workOrderId;
    private String type;
    private String title;
    private String detail;
    private Boolean urgent;
}
