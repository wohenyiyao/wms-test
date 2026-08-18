package com.wms.repository;

import com.wms.entity.OutboundOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 出库单明细 Repository — 选做A
 */
@Repository
public interface OutboundOrderItemRepository extends JpaRepository<OutboundOrderItem, Long> {

    List<OutboundOrderItem> findByOrderId(Long orderId);

    /** 某商品全部出库明细（并发测试清理、审计用） */
    List<OutboundOrderItem> findByProductId(Long productId);

    /** 某商品在出库单明细中的行数（删除商品前关联校验用） */
    long countByProductId(Long productId);

    /** 删除某商品全部出库单明细（确认删除时级联清理） */
    void deleteByProductId(Long productId);
}
