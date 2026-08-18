package com.wms.repository;

import com.wms.entity.OrderSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 单号序列表 Repository（选做A：并发安全发号）
 *
 * advance() 使用 MySQL 原子发号技巧：UPDATE 对目标行加行锁，
 * LAST_INSERT_ID(expr) 返回本连接的新序号，并发请求串行取号、互不冲突。
 * 必须在同一事务内先 advance 再 lastInsertId。
 */
@Repository
public interface OrderSequenceRepository extends JpaRepository<OrderSequence, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE order_sequences
            SET next_value = LAST_INSERT_ID(next_value + 1)
            WHERE seq_type = :seqType
            """, nativeQuery = true)
    int advance(@Param("seqType") String seqType);

    @Query(value = "SELECT LAST_INSERT_ID()", nativeQuery = true)
    long lastInsertId();
}
