package com.wms.repository;

import com.wms.entity.InboundOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 入库单明细 Repository — 任务1 新建
 */
@Repository
public interface InboundOrderItemRepository extends JpaRepository<InboundOrderItem, Long> {

    List<InboundOrderItem> findByOrderId(Long orderId);

    /** 某商品在入库单明细中的行数（任务3：删除商品前校验用） */
    long countByProductId(Long productId);

    /** 删除某商品全部入库单明细（任务3：确认删除时级联清理） */
    void deleteByProductId(Long productId);
}
