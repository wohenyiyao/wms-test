package com.wms.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 入库单明细请求项 — 任务1
 */
@Data
public class InboundItemRequest {
    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于0")
    @Max(value = 999999, message = "数量不能超过999999")
    private Integer quantity;

    @NotBlank(message = "库位编码不能为空")
    @Size(max = 50, message = "库位编码长度不能超过50")
    private String locationCode;
}
