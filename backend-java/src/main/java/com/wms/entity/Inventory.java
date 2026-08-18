package com.wms.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 库存表 — 核心业务实体
 * 候选人需要实现库存查询功能
 */
@Entity
@Table(name = "inventory", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"product_id", "location_code"})
}, indexes = {
    // 筛选/join 字段索引：location_code（keyword 模糊搜索该列；等值/前缀匹配走索引）
    @Index(name = "idx_inventory_location_code", columnList = "location_code")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "location_code", nullable = false, length = 50)
    private String locationCode;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 0;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
