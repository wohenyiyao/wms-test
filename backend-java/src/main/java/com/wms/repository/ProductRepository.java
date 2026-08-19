package com.wms.repository;

import com.wms.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * SKU 全局唯一检查（含已逻辑删除的记录）。
     *
     * 注意：必须用 native query —— 实体上的 @SQLRestriction("deleted = 0")
     * 会注入到所有 JPQL/derived query，若用它过滤，已删商品的 SKU 会被放行，
     * 但 DB 唯一约束仍在，INSERT 将撞唯一索引报 500。原生 SQL 绕过软删过滤，
     * 使"SKU 已存在"检查与 DB 唯一约束语义一致。
     * （返回 long 而非 boolean：MySQL 驱动把 COUNT(*) 映射为 BIGINT，boolean 会 ClassCastException）
     */
    @Query(value = "SELECT COUNT(*) FROM products WHERE sku = :sku", nativeQuery = true)
    long countBySkuAll(@Param("sku") String sku);

    /**
     * 模糊搜索商品（按名称或SKU）
     */
    @Query("SELECT p FROM Product p WHERE " +
           "(:keyword IS NULL OR p.name LIKE %:keyword% OR p.sku LIKE %:keyword%)")
    List<Product> search(@Param("keyword") String keyword);
}
