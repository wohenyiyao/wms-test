package com.wms.controller;

import com.wms.common.ApiResponse;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.InboundOrderResponse;
import com.wms.dto.InventoryResponse;
import com.wms.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
     * 库存查询 — 任务2（候选人实现）
     */
    @GetMapping("/inventory")
    public ApiResponse<List<InventoryResponse>> queryInventory(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        // TODO: 调用 inventoryService.queryInventory(...)
        return ApiResponse.error(501, "请实现库存查询功能（任务2）");
    }
}
