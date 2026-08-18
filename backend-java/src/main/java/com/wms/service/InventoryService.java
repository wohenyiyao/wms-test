package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.common.PageResult;
import com.wms.dto.InboundItemRequest;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.InboundOrderResponse;
import com.wms.dto.InventoryResponse;
import com.wms.entity.InboundOrder;
import com.wms.entity.InboundOrderItem;
import com.wms.entity.Inventory;
import com.wms.entity.Product;
import com.wms.repository.InboundOrderItemRepository;
import com.wms.repository.InboundOrderRepository;
import com.wms.repository.InventoryRepository;
import com.wms.repository.LocationRepository;
import com.wms.repository.OrderSequenceRepository;
import com.wms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ============================================
 *  入库单创建（任务1）— 已实现
 *  库存查询（任务2）— 待实现
 * ============================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    /** 深分页兜底：允许的最大 OFFSET（行数）。超过则拒绝查询，防止页码无限增大导致全表扫描 */
    private static final long MAX_OFFSET = 10_000L;

    private final InventoryRepository inventoryRepository;
    private final InboundOrderRepository inboundOrderRepository;
    private final InboundOrderItemRepository inboundOrderItemRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final RedisStockService redisStockService;
    private final OrderSequenceRepository orderSequenceRepository;

    /**
     * 入库单创建 — 任务1
     *
     * 流程：
     * 0. 幂等检查：若带 requestId 且已存在对应入库单，直接返回原单（弱网重试兜底，避免重复入库/库存虚增）
     * 1. 生成入库单号（格式 IN-YYYYMMDD-XXX，XXX 为当日序号）
     * 2. 创建入库单（status=COMPLETED，库存即时生效）
     * 3. 保存明细，并按 (productId, locationCode) 累加库存
     *
     * 整个方法处于同一数据库事务中：任一步失败（商品/库位不存在、唯一键冲突等）
     * 则整体回滚，保证"入库单 + 明细 + 库存累加"的一致性。
     *
     * 注意：入库单号基于当日最大序号生成，极端并发下两个请求可能算出相同序号，
     * 由 order_no 唯一约束兜底（后者会抛异常回滚）。测试场景下可接受。
     */
    @Transactional
    public InboundOrderResponse createInboundOrder(InboundOrderCreateRequest request) {
        // 0. 幂等检查：同一 requestId 重复提交（弱网重试/连点）直接返回已创建的入库单
        String requestId = request.getRequestId();
        if (requestId != null && !requestId.isBlank()) {
            InboundOrder existing = inboundOrderRepository.findByRequestId(requestId).orElse(null);
            if (existing != null) {
                log.info("幂等命中: requestId={}, 返回已存在入库单 orderNo={}", requestId, existing.getOrderNo());
                return toResponse(existing);
            }
        }

        // 1. 生成入库单号
        String orderNo = generateOrderNo();

        // 2. 创建入库单主表
        InboundOrder order = InboundOrder.builder()
                .orderNo(orderNo)
                .supplierName(request.getSupplierName())
                .status("COMPLETED")
                .requestId(requestId)
                .build();
        order = inboundOrderRepository.save(order);

        // 3. 保存明细 + 累加库存（校验失败抛 BusinessException，事务整体回滚）
        List<InboundItemRequest> items = request.getItems();
        List<InboundOrderResponse.ItemResponse> itemResponses = new ArrayList<>(items.size());
        for (InboundItemRequest item : items) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new BusinessException(404, "商品不存在: id=" + item.getProductId()));
            locationRepository.findByCode(item.getLocationCode())
                    .orElseThrow(() -> new BusinessException(404, "库位不存在: " + item.getLocationCode()));

            inboundOrderItemRepository.save(InboundOrderItem.builder()
                    .orderId(order.getId())
                    .productId(product.getId())
                    .quantity(item.getQuantity())
                    .locationCode(item.getLocationCode())
                    .build());

            // upsert 库存：存在则累加，不存在则新建
            Inventory inventory = inventoryRepository
                    .findByProductIdAndLocationCode(product.getId(), item.getLocationCode())
                    .orElseGet(() -> Inventory.builder()
                            .productId(product.getId())
                            .locationCode(item.getLocationCode())
                            .quantity(0)
                            .build());
            inventory.setQuantity(inventory.getQuantity() + item.getQuantity());
            inventoryRepository.save(inventory);
            // 同步 Redis 库存镜像（选做A：出库预扣门控的数据源；失败不影响 DB，由懒加载/启动重建自愈）
            redisStockService.increase(product.getId(), item.getLocationCode(), item.getQuantity());

            itemResponses.add(InboundOrderResponse.ItemResponse.builder()
                    .productId(product.getId())
                    .productName(product.getName())
                    .quantity(item.getQuantity())
                    .locationCode(item.getLocationCode())
                    .build());
        }

        log.info("入库单创建成功: orderNo={}, supplier={}, items={}",
                orderNo, request.getSupplierName(), items.size());

        return InboundOrderResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .supplierName(order.getSupplierName())
                .status(order.getStatus())
                .items(itemResponses)
                .createdAt(order.getCreatedAt())
                .build();
    }

    /**
     * 幂等命中时，把已存在的入库单映射为响应（含明细与商品名）
     */
    private InboundOrderResponse toResponse(InboundOrder order) {
        List<InboundOrderResponse.ItemResponse> items = inboundOrderItemRepository.findByOrderId(order.getId())
                .stream()
                .map(oi -> InboundOrderResponse.ItemResponse.builder()
                        .productId(oi.getProductId())
                        .productName(productRepository.findById(oi.getProductId())
                                .map(Product::getName)
                                .orElse(""))
                        .quantity(oi.getQuantity())
                        .locationCode(oi.getLocationCode())
                        .build())
                .toList();
        return InboundOrderResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .supplierName(order.getSupplierName())
                .status(order.getStatus())
                .items(items)
                .createdAt(order.getCreatedAt())
                .build();
    }

    /**
     * 生成入库单号：IN-YYYYMMDD-XXX。
     * 序号来自 order_sequences 表（MySQL 原子发号：UPDATE 行锁 + LAST_INSERT_ID），
     * 并发安全（详见 OrderSequence 注释）；XXX 全局递增，单号唯一性由
     * 「前缀 + 序号」保证（跨天不重置，避免按日重置的并发边界）。
     */
    private String generateOrderNo() {
        orderSequenceRepository.advance("IN");
        long seq = orderSequenceRepository.lastInsertId();
        return "IN-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%03d", seq);
    }

    /**
     * 库存查询 — 任务2（选做A 扩展：支持按商品精确过滤，出库页展示可用库存用）
     *
     * 按 商品名称/SKU/库位编码 模糊搜索 + 仓库筛选 + 商品筛选 + 告急筛选 + 分页。
     * 分页采用「两步查询」优化（先查 id 集合、再 IN 取详情），配合 OFFSET 深度上限，
     * 避免深分页全量回表（详见 InventoryRepository 注释）。
     *
     * @throws BusinessException 400 查询深度超过上限（深分页兜底：数据量大时应缩小筛选范围）
     */
    @Transactional(readOnly = true)
    public PageResult<InventoryResponse> queryInventory(String keyword, Long warehouseId, Long productId,
                                                        boolean lowStockOnly,
                                                        int page, int pageSize) {
        // 深分页兜底：OFFSET 深度上限，防止页码无限增大导致全表扫描
        long offset = (long) (page - 1) * pageSize;
        if (offset > MAX_OFFSET) {
            throw new BusinessException(400,
                    "查询深度超过上限（" + MAX_OFFSET + " 行），请使用筛选条件缩小数据范围");
        }

        String safeKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        // 第一步：只查 id 集合（分页 + count 走主键/筛选索引，零回表）
        Page<Long> ids = inventoryRepository.findIdsByFilters(
                safeKeyword, warehouseId, productId, lowStockOnly,
                PageRequest.of(Math.max(page - 1, 0), pageSize, Sort.by("id")));

        // 第二步：按 id 集合查完整明细，并按第一步的 id 顺序重排
        List<InventoryResponse> details = ids.isEmpty()
                ? List.of()
                : inventoryRepository.findDetailsByIds(ids.getContent());
        Map<Long, InventoryResponse> byId = details.stream()
                .collect(Collectors.toMap(InventoryResponse::getInventoryId, r -> r));
        List<InventoryResponse> ordered = ids.getContent().stream()
                .map(byId::get)
                .toList();

        return new PageResult<>(ordered, ids.getTotalElements(), page, pageSize);
    }
}
