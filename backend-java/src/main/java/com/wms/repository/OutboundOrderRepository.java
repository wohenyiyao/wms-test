package com.wms.repository;

import com.wms.entity.OutboundOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 出库单 Repository — 选做A
 */
@Repository
public interface OutboundOrderRepository extends JpaRepository<OutboundOrder, Long> {

    /**
     * 幂等查询：按 requestId 查找已创建的出库单（弱网重试兜底）
     */
    Optional<OutboundOrder> findByRequestId(String requestId);
}
