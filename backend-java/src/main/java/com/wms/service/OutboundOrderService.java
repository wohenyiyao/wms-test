package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.dto.OutboundItemRequest;
import com.wms.dto.OutboundOrderCreateRequest;
import com.wms.dto.OutboundOrderResponse;
import com.wms.entity.OutboundOrder;
import com.wms.entity.OutboundOrderItem;
import com.wms.entity.Product;
import com.wms.repository.InventoryRepository;
import com.wms.repository.LocationRepository;
import com.wms.repository.OrderSequenceRepository;
import com.wms.repository.OutboundOrderItemRepository;
import com.wms.repository.OutboundOrderRepository;
import com.wms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出库单创建 — 选做A
 *
 * 核心难点：高并发下库存扣减防超卖。采用「Redis Lua 原子预扣 + DB 原子条件更新」双层防线：
 *
 * 1. Redis 预扣（第一道闸，事务内执行）：
 *    对每个合并后的明细行执行 Lua 原子脚本（检查剩余>=需求 → DECRBY），
 *    高并发下在内存中快速拦截库存不足的请求，减少打到 DB 的无效事务；
 *    Redis 不可用时 fail-open 放行（RedisStockService 内部处理）。
 *
 * 2. DB 原子条件更新（最终兜底）：
 *    UPDATE inventory SET quantity = quantity - N WHERE ... AND quantity >= N，
 *    受影响行数=0 即数据库判定库存不足 → 抛 400 回滚整个事务。
 *    即使 Redis 预扣成功（或 Redis 挂了被跳过），正确性始终由这条原子 SQL 保证：
 *    同一库存行的并发扣减在 InnoDB 行锁下串行执行，库存永远不会被扣成负数。
 *
 * 3. 一致性：DB 事务失败 → catch 中对已预扣的行 revert() 补偿回滚 Redis；
 *    预扣与 DB 扣减在同一请求内同步完成（无延迟扣减窗口），
 *    Redis 与 DB 不一致只会"少卖"（Redis 多挡掉本可成功的请求），绝不超卖。
 *
 * 4. 幂等：requestId 唯一索引 + 命中返回原单（弱网重试不重复出库/重复扣库存）。
 *
 * 5. 明细合并：同一 (productId, locationCode) 多行先求和再一次性扣减，
 *    避免"第一行扣成功、第二行不够导致整单回滚"的不必要失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundOrderService {

    private final OutboundOrderRepository outboundOrderRepository;
    private final OutboundOrderItemRepository outboundOrderItemRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final InventoryRepository inventoryRepository;
    private final RedisStockService redisStockService;
    private final OrderSequenceRepository orderSequenceRepository;

    /** 出库明细行（合并后） */
    private record OutboundLine(Long productId, String productName, String locationCode, int quantity) {}

    /**
     * 创建出库单。
     *
     * 整个方法处于同一数据库事务中；Redis 操作不参与 DB 事务，
     * 任一步失败（商品/库位不存在、预扣不足、DB 兜底拦截）都整体回滚，
     * 并补偿回滚已预扣的 Redis 库存，保证"出库单 + 明细 + 库存扣减"一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public OutboundOrderResponse createOutboundOrder(OutboundOrderCreateRequest request) {
        // 0. 幂等检查：同一 requestId 重复提交（弱网重试/连点）直接返回已创建的出库单
        String requestId = request.getRequestId();
        if (requestId != null && !requestId.isBlank()) {
            OutboundOrder existing = outboundOrderRepository.findByRequestId(requestId).orElse(null);
            if (existing != null) {
                log.info("幂等命中: requestId={}, 返回已存在出库单 orderNo={}", requestId, existing.getOrderNo());
                return toResponse(existing);
            }
        }

        // 1. 校验商品/库位存在，并按 (productId, locationCode) 合并明细
        List<OutboundLine> lines = mergeAndValidate(request.getItems());

        // 2. 生成出库单号（OUT-YYYYMMDD-XXX，当日序号递增；极端并发由 order_no 唯一约束兜底）
        String orderNo = generateOrderNo();

        // 3-4. Redis 原子预扣（第一道闸）+ DB 事务主体（建单/明细/原子扣减兜底）
        // 整个区块在 try 中：任一步失败（预扣不足 / DB 兜底拦截 / 校验异常）都会在 catch 中
        // 补偿已预扣的 Redis 库存，再抛出让 DB 事务回滚，保证两侧一致。
        List<OutboundLine> deducted = new ArrayList<>(lines.size());
        try {
            for (OutboundLine line : lines) {
                if (!redisStockService.preDeduct(line.productId(), line.locationCode(), line.quantity())) {
                    throw new BusinessException(400,
                            "库存不足: " + line.productName() + " 库位 " + line.locationCode()
                                    + " 当前可出不足 " + line.quantity() + " 件");
                }
                deducted.add(line);
            }

            OutboundOrder order = outboundOrderRepository.save(OutboundOrder.builder()
                    .orderNo(orderNo)
                    .customerName(request.getCustomerName())
                    .status("COMPLETED")
                    .requestId(requestId)
                    .build());

            List<OutboundOrderResponse.ItemResponse> itemResponses = new ArrayList<>(lines.size());
            for (OutboundLine line : lines) {
                // DB 原子条件扣减：affected=0 表示数据库判定库存不足（Redis 预扣成功但 DB 侧不足，
                // 或 Redis 不可用降级放行后由这里拦截）→ 抛异常回滚并补偿 Redis，防止超卖
                int affected = inventoryRepository.deductStock(line.productId(), line.locationCode(), line.quantity());
                if (affected == 0) {
                    throw new BusinessException(400,
                            "库存不足: " + line.productName() + " 库位 " + line.locationCode()
                                    + " 当前可出不足 " + line.quantity() + " 件");
                }

                outboundOrderItemRepository.save(OutboundOrderItem.builder()
                        .orderId(order.getId())
                        .productId(line.productId())
                        .quantity(line.quantity())
                        .locationCode(line.locationCode())
                        .build());

                itemResponses.add(OutboundOrderResponse.ItemResponse.builder()
                        .productId(line.productId())
                        .productName(line.productName())
                        .quantity(line.quantity())
                        .locationCode(line.locationCode())
                        .build());
            }

            log.info("出库单创建成功: orderNo={}, customer={}, items={}", orderNo, request.getCustomerName(), lines.size());

            return OutboundOrderResponse.builder()
                    .id(order.getId())
                    .orderNo(order.getOrderNo())
                    .customerName(order.getCustomerName())
                    .status(order.getStatus())
                    .items(itemResponses)
                    .createdAt(order.getCreatedAt())
                    .build();
        } catch (Exception e) {
            for (OutboundLine line : deducted) {
                redisStockService.revert(line.productId(), line.locationCode(), line.quantity());
            }
            throw e;
        }
    }

    /**
     * 校验商品/库位存在，并按 (productId, locationCode) 合并数量（保持首次出现顺序）。
     */
    private List<OutboundLine> mergeAndValidate(List<OutboundItemRequest> items) {
        Map<String, OutboundLine> merged = new LinkedHashMap<>();
        for (OutboundItemRequest item : items) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new BusinessException(404, "商品不存在: id=" + item.getProductId()));
            locationRepository.findByCode(item.getLocationCode())
                    .orElseThrow(() -> new BusinessException(404, "库位不存在: " + item.getLocationCode()));

            String key = item.getProductId() + ":" + item.getLocationCode();
            merged.merge(key,
                    new OutboundLine(product.getId(), product.getName(), item.getLocationCode(), item.getQuantity()),
                    (a, b) -> new OutboundLine(a.productId(), a.productName(), a.locationCode(),
                            a.quantity() + b.quantity()));
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 生成出库单号：OUT-YYYYMMDD-XXX。
     * 序号来自 order_sequences 表（MySQL 原子发号：UPDATE 行锁 + LAST_INSERT_ID），
     * 并发安全（详见 OrderSequence 注释）；XXX 全局递增，跨天不重置。
     */
    private String generateOrderNo() {
        orderSequenceRepository.advance("OUT");
        long seq = orderSequenceRepository.lastInsertId();
        return "OUT-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%03d", seq);
    }

    /**
     * 幂等命中时，把已存在的出库单映射为响应（含明细与商品名）
     */
    private OutboundOrderResponse toResponse(OutboundOrder order) {
        List<OutboundOrderResponse.ItemResponse> items = outboundOrderItemRepository.findByOrderId(order.getId())
                .stream()
                .map(oi -> OutboundOrderResponse.ItemResponse.builder()
                        .productId(oi.getProductId())
                        .productName(productRepository.findById(oi.getProductId())
                                .map(Product::getName)
                                .orElse(""))
                        .quantity(oi.getQuantity())
                        .locationCode(oi.getLocationCode())
                        .build())
                .toList();
        return OutboundOrderResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .customerName(order.getCustomerName())
                .status(order.getStatus())
                .items(items)
                .createdAt(order.getCreatedAt())
                .build();
    }
}
