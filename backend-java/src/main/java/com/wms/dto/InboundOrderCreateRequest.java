package com.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 入库单创建请求 — 任务1
 */
@Data
public class InboundOrderCreateRequest {

    @NotBlank(message = "供应商名称不能为空")
    @Size(max = 200, message = "供应商名称长度不能超过200")
    private String supplierName;

    /**
     * 幂等键（可选）：客户端生成 UUID，弱网重试时复用，
     * 同一 requestId 重复提交不会重复创建入库单。
     */
    @Size(max = 64, message = "requestId 长度不能超过64")
    private String requestId;

    @NotEmpty(message = "入库明细不能为空")
    @Valid
    private List<InboundItemRequest> items;
}
