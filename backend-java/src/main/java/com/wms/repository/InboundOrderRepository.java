package com.wms.repository;

import com.wms.entity.InboundOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 入库单 Repository
 */
@Repository
public interface InboundOrderRepository extends JpaRepository<InboundOrder, Long> {

    /**
     * 幂等查询：按 requestId 查找已创建的入库单（弱网重试兜底）
     */
    Optional<InboundOrder> findByRequestId(String requestId);
}
