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
import com.wms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    private final InventoryRepository inventoryRepository;
    private final InboundOrderRepository inboundOrderRepository;
    private final InboundOrderItemRepository inboundOrderItemRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;

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
     * 生成入库单号：IN-YYYYMMDD-XXX（当日序号，从 001 递增）
     */
    private String generateOrderNo() {
        String prefix = "IN-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int maxSeq = inboundOrderRepository.findOrderNosByPrefix(prefix).stream()
                .map(no -> no.substring(no.lastIndexOf('-') + 1))
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        return prefix + "-" + String.format("%03d", maxSeq + 1);
    }

    /**
     * 库存查询 — 任务2
     *
     * 按 商品名称/SKU/库位编码 模糊搜索 + 仓库筛选 + 告急筛选 + 分页。
     * 由 Repository 单条 JPQL 完成 join 与筛选（见 InventoryRepository#searchInventory），
     * 分页由 Spring Data 自动 count，避免全表拉取。
     */
    public PageResult<InventoryResponse> queryInventory(String keyword, Long warehouseId,
                                                        boolean lowStockOnly,
                                                        int page, int pageSize) {
        String safeKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Page<InventoryResponse> result = inventoryRepository.searchInventory(
                safeKeyword,
                warehouseId,
                lowStockOnly,
                PageRequest.of(Math.max(page - 1, 0), pageSize));
        return new PageResult<>(result.getContent(), result.getTotalElements(), page, pageSize);
    }
}
