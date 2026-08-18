package com.wms.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 出库单明细 — 选做A
 *
 * 与入库单明细（InboundOrderItem）对称；locationCode 指明从哪个库位出库，
 * 扣减对应 (productId, locationCode) 的库存行。
 */
@Entity
@Table(name = "outbound_order_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboundOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "location_code", nullable = false, length = 50)
    private String locationCode;
}
