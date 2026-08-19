package com.wms.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 商品实体 — 逻辑删除（deleted 标记）。
 *
 * 删除商品不再物理删除，而是置 deleted=1（@SQLDelete 将 delete 转为 UPDATE）；
 * 所有查询自动过滤 deleted=0（@SQLRestriction），已删商品不可见、不可再被
 * 商品选择/编辑/入库/出库引用，但历史单据（入库/出库明细）与库存记录保留可追溯。
 * SKU 仍全局唯一（含已删记录），避免历史引用语义混乱。
 */
@Entity
@Table(name = "products", indexes = {
    // 筛选字段索引：name（keyword 模糊搜索该列；等值/前缀匹配走索引）
    @Index(name = "idx_products_name", columnList = "name")
})
@SQLDelete(sql = "UPDATE products SET deleted = 1 WHERE id = ?")
@SQLRestriction("deleted = 0")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 50)
    private String sku;

    @Column(length = 20)
    @Builder.Default
    private String unit = "个";

    /** 逻辑删除标记：false=正常，true=已删除（查询自动过滤） */
    @Column(nullable = false, columnDefinition = "bit(1) default b'0'")
    @Builder.Default
    private Boolean deleted = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
