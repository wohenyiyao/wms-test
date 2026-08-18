package com.wms.controller;

import com.wms.common.ApiResponse;
import com.wms.dto.OutboundOrderCreateRequest;
import com.wms.dto.OutboundOrderResponse;
import com.wms.service.OutboundOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 出库单 Controller — 选做A
 *
 * POST /api/outbound-orders — 创建出库单（Redis Lua 预扣 + DB 原子扣减防超卖）
 * RESTful：创建成功由 @ResponseStatus 提供 HTTP 201；响应体沿用统一信封（code=200）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundOrderService outboundOrderService;

    @PostMapping("/outbound-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OutboundOrderResponse> createOutboundOrder(@Valid @RequestBody OutboundOrderCreateRequest request) {
        return ApiResponse.success("出库单创建成功", outboundOrderService.createOutboundOrder(request));
    }
}
