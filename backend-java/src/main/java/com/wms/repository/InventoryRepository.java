package com.wms.repository;

import com.wms.dto.InventoryResponse;
import com.wms.entity.Inventory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 库存 Repository
 *
 * 库存查询（任务2）采用「两步查询」分页优化，避免深分页全量回表：
 * 1. 第一步只查满足筛选条件的 id 集合（走主键/筛选索引，零回表），分页 + count 都在这一层；
 * 2. 第二步按 id 集合 IN 查询完整明细（join 商品/库位/仓库），回表只发生在真正要返回的行。
 *
 * 筛选字段索引：inventory.location_code（idx_inventory_location_code）、products.name（idx_products_name）、
 * locations.warehouse_id（外键索引）；排序字段 id 为主键（聚集索引，天然覆盖）。
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductIdAndLocationCode(Long productId, String locationCode);

    /**
     * 第一步：分页查满足筛选条件的库存行 id（只返回主键，无回表）。
     * - keyword 模糊匹配：商品名称 / SKU / 库位编码（仓库筛选走 warehouseId 下拉）
     * - warehouseId 精确匹配仓库（空则不过滤）
     * - productId 精确匹配商品（选做A：出库页展示某商品各库位可用库存用；空则不过滤）
     * - lowStockOnly=true 时仅返回 quantity < 10 的告急库存
     * 分页 + count 由 Spring Data 自动完成；排序必须稳定（按主键 id）。
     */
    @Query("""
            SELECT i.id FROM Inventory i
            JOIN Product p ON p.id = i.productId
            JOIN Location l ON l.code = i.locationCode
            WHERE (:keyword IS NULL OR :keyword = ''
                   OR p.name LIKE CONCAT('%', :keyword, '%')
                   OR p.sku LIKE CONCAT('%', :keyword, '%')
                   OR i.locationCode LIKE CONCAT('%', :keyword, '%'))
              AND (:warehouseId IS NULL OR l.warehouseId = :warehouseId)
              AND (:productId IS NULL OR i.productId = :productId)
              AND (:lowStockOnly = FALSE OR i.quantity < 10)
            """)
    Page<Long> findIdsByFilters(@Param("keyword") String keyword,
                                @Param("warehouseId") Long warehouseId,
                                @Param("productId") Long productId,
                                @Param("lowStockOnly") boolean lowStockOnly,
                                Pageable pageable);

    /**
     * 第二步：按 id 集合查完整明细（join 商品/库位/仓库，返回 InventoryResponse）。
     * 结果顺序不保证与入参一致，调用方需按第一步的 id 顺序重排。
     */
    @Query("""
            SELECT new com.wms.dto.InventoryResponse(
                i.id, i.productId, p.name, p.sku, i.locationCode, w.name, i.quantity, i.updatedAt)
            FROM Inventory i
            JOIN Product p ON p.id = i.productId
            JOIN Location l ON l.code = i.locationCode
            JOIN Warehouse w ON w.id = l.warehouseId
            WHERE i.id IN :ids
            """)
    List<InventoryResponse> findDetailsByIds(@Param("ids") Collection<Long> ids);

    /** 某商品关联的库存行数（任务3：删除商品前校验用） */
    long countByProductId(Long productId);

    /** 删除某商品全部库存行（任务3：确认删除时级联清理） */
    void deleteByProductId(Long productId);

    /**
     * 原子条件扣减（选做A：防超卖最终兜底）。
     *
     * 单条 UPDATE 在 InnoDB 下对命中行加 X 锁，同一行并发扣减天然串行；
     * 条件 quantity >= :qty 保证"检查+扣减"原子完成，任何并发交错下
     * 库存都不会被扣成负数。返回受影响行数：1=扣减成功，0=库存不足。
     *
     * 注意：JPA bulk update 绕过实体生命周期（@PreUpdate 不触发），
     * 这里手动刷新 updatedAt；调用方不要在同一事务内先加载该 Inventory 实体
     * （一级缓存旧值可能被脏检查覆盖扣减结果）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Inventory i
            SET i.quantity = i.quantity - :qty, i.updatedAt = CURRENT_TIMESTAMP
            WHERE i.productId = :productId AND i.locationCode = :locationCode AND i.quantity >= :qty
            """)
    int deductStock(@Param("productId") Long productId,
                    @Param("locationCode") String locationCode,
                    @Param("qty") int qty);
}
