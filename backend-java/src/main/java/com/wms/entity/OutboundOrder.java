package com.wms.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 出库单主表 — 选做A（出库单 + 库存扣减防超卖）
 *
 * 与入库单（InboundOrder）对称：status=COMPLETED 表示出库即时生效（库存已扣减）。
 * request_id 唯一索引用于幂等（弱网重试不重复出库/不重复扣减库存）。
 */
@Entity
@Table(name = "outbound_orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboundOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 50)
    private String orderNo;

    /** 客户名称（与入库单 supplierName 对称） */
    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(length = 20)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 幂等键（客户端生成 UUID，弱网重试时复用）。
     * 同一 requestId 重复提交直接返回已创建的出库单，避免重复扣减库存。
     */
    @Column(name = "request_id", unique = true, length = 64)
    private String requestId;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
