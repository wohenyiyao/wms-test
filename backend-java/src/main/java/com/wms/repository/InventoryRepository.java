package com.wms.repository;

import com.wms.dto.InventoryResponse;
import com.wms.entity.Inventory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 库存 Repository
 *
 * 库存查询（任务2）：单条 JPQL join 商品/库位/仓库，
 * 支持 商品名称/SKU/库位编码 模糊搜索 + 仓库筛选 + 告急(quantity<10)筛选 + 分页。
 * join 全部走主键 / 唯一键（products.id、locations.code、warehouses.id），
 * warehouseId 筛选走 locations.warehouse_id 外键索引。
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductIdAndLocationCode(Long productId, String locationCode);

    /**
     * 库存分页查询（任务2）。
     * - keyword 模糊匹配：商品名称 / SKU / 库位编码
     * - warehouseId 精确匹配仓库（空则不过滤）
     * - lowStockOnly=true 时仅返回 quantity < 10 的告急库存
     * 分页由 Spring Data 自动生成 count 查询。
     */
    @Query("""
            SELECT new com.wms.dto.InventoryResponse(
                i.productId, p.name, p.sku, i.locationCode, w.name, i.quantity, i.updatedAt)
            FROM Inventory i
            JOIN Product p ON p.id = i.productId
            JOIN Location l ON l.code = i.locationCode
            JOIN Warehouse w ON w.id = l.warehouseId
            WHERE (:keyword IS NULL OR :keyword = ''
                   OR p.name LIKE CONCAT('%', :keyword, '%')
                   OR p.sku LIKE CONCAT('%', :keyword, '%')
                   OR i.locationCode LIKE CONCAT('%', :keyword, '%'))
              AND (:warehouseId IS NULL OR l.warehouseId = :warehouseId)
              AND (:lowStockOnly = FALSE OR i.quantity < 10)
            """)
    Page<InventoryResponse> searchInventory(@Param("keyword") String keyword,
                                            @Param("warehouseId") Long warehouseId,
                                            @Param("lowStockOnly") boolean lowStockOnly,
                                            Pageable pageable);
}
