package com.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 出库单创建请求 — 选做A
 */
@Data
public class OutboundOrderCreateRequest {

    @NotBlank(message = "客户名称不能为空")
    @Size(max = 200, message = "客户名称长度不能超过200")
    private String customerName;

    /**
     * 幂等键（可选）：客户端生成 UUID，弱网重试时复用，
     * 同一 requestId 重复提交不会重复创建出库单/重复扣减库存。
     */
    @Size(max = 64, message = "requestId 长度不能超过64")
    private String requestId;

    @NotEmpty(message = "出库明细不能为空")
    @Valid
    private List<OutboundItemRequest> items;
}
