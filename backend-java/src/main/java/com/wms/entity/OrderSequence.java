package com.wms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单号序列表（选做A：并发安全发号）
 *
 * 生成入库/出库单号时，用 MySQL 原子发号技巧：
 *   UPDATE order_sequences SET next_value = LAST_INSERT_ID(next_value + 1) WHERE seq_type = ?
 *   SELECT LAST_INSERT_ID()
 * UPDATE 对目标行加行锁，并发请求的发号天然串行化；LAST_INSERT_ID(expr) 返回本连接的
 * 新值（与事务提交无关）。相比「查最大单号 + 1」在事务内不可靠（看不到未提交的并发单号，
 * 曾导致 Duplicate entry 冲突），这是标准的高并发发号方案。
 *
 * 序号全局递增（跨天不重置）：单号唯一性由「前缀 + 序号」保证，避免按日重置的并发边界。
 * 事务回滚时 UPDATE 一并回滚，序号不会浪费（未落库的单号可复用）。
 */
@Entity
@Table(name = "order_sequences")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderSequence {

    /** 序列类型：IN / OUT */
    @Id
    @Column(name = "seq_type", length = 20)
    private String seqType;

    @Column(name = "next_value", nullable = false)
    private Long nextValue;
}
