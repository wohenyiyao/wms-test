package com.wms.repository;

import com.wms.entity.InboundOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 入库单 Repository
 */
@Repository
public interface InboundOrderRepository extends JpaRepository<InboundOrder, Long> {

    /**
     * 查询指定前缀（如当天 "IN-20260508"）下已存在的入库单号，
     * 用于生成入库单号时计算当日最大序号。
     */
    @Query("SELECT o.orderNo FROM InboundOrder o WHERE o.orderNo LIKE CONCAT(:prefix, '%') ORDER BY o.orderNo DESC")
    List<String> findOrderNosByPrefix(@Param("prefix") String prefix);

    /**
     * 幂等查询：按 requestId 查找已创建的入库单（弱网重试兜底）
     */
    Optional<InboundOrder> findByRequestId(String requestId);
}
