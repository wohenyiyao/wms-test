package com.wms.controller;

import com.wms.common.ApiResponse;
import com.wms.common.PageResult;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.InboundOrderResponse;
import com.wms.dto.InventoryResponse;
import com.wms.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 入库单 & 库存 Controller
 *
 * POST /api/inbound-orders        — 创建入库单（任务1，已实现）
 * GET  /api/inventory             — 库存查询（任务2，待实现）
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    /**
     * 创建入库单 — 任务1
     * RESTful：创建成功由 @ResponseStatus 提供 HTTP 201；
     * 响应体沿用统一信封（code=200 + message + data），与通用约定一致。
     */
    @PostMapping("/inbound-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InboundOrderResponse> createInboundOrder(@Valid @RequestBody InboundOrderCreateRequest request) {
        return ApiResponse.success("入库单创建成功", inventoryService.createInboundOrder(request));
    }

    /**
     * 库存查询 — 任务2
     * 支持 商品名称/SKU/库位编码 模糊搜索(keyword) + 仓库筛选(warehouseId) +
     * 告急筛选(lowStockOnly, quantity<10) + 分页；pageSize 上限 100（可用性兜底）。
     */
    @GetMapping("/inventory")
    public ApiResponse<PageResult<InventoryResponse>> queryInventory(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "false") boolean lowStockOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        return ApiResponse.success(
                inventoryService.queryInventory(keyword, warehouseId, lowStockOnly, safePage, safeSize));
    }
}
