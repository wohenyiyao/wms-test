package com.wms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库单创建响应 — 任务1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboundOrderResponse {

    private Long id;
    private String orderNo;
    private String supplierName;
    private String status;
    private List<ItemResponse> items;
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemResponse {
        private Long productId;
        private String productName;
        private Integer quantity;
        private String locationCode;
    }
}
