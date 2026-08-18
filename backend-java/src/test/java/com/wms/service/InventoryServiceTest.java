package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.dto.InboundItemRequest;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.InboundOrderResponse;
import com.wms.entity.Inventory;
import com.wms.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 入库单创建 Service 层集成测试（任务 1）
 *
 * 依赖启动时注入的示例数据（5 商品 / 2 仓 / 4 库位）。
 * 每个用例 @Transactional 回滚，不污染数据库。
 */
@SpringBootTest
@Transactional
class InventoryServiceTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Test
    void createInboundOrder_shouldCreateOrderWithGeneratedNoAndAccumulateInventory() {
        // given：记录商品1 在 WH-A-01-01 的当前库存
        int before = quantityOf(1L, "WH-A-01-01");

        // when：创建含两条明细的入库单
        InboundOrderResponse resp = inventoryService.createInboundOrder(
                request("集成测试-供应商A",
                        item(1L, 10, "WH-A-01-01"),
                        item(2L, 5, "WH-A-01-02")));

        // then：单号格式 / 状态 / 明细回显
        assertNotNull(resp.getId());
        assertTrue(resp.getOrderNo().matches("IN-\\d{8}-\\d{3,}"), "单号应为 IN-YYYYMMDD-XXX，实际: " + resp.getOrderNo());
        assertEquals("COMPLETED", resp.getStatus());
        assertEquals(2, resp.getItems().size());

        // then：库存正确累加
        assertEquals(before + 10, quantityOf(1L, "WH-A-01-01"));
    }

    @Test
    void createInboundOrder_duplicateProductLocation_shouldAccumulateTogether() {
        // given：WH-A-02-01 初始无库存
        int before = quantityOf(3L, "WH-A-02-01");

        // when：同一订单两条相同 (商品, 库位) 明细 3+4
        inventoryService.createInboundOrder(request("集成测试-重复明细",
                item(3L, 3, "WH-A-02-01"),
                item(3L, 4, "WH-A-02-01")));

        // then：库存一次累加 7（upsert 语义）
        assertEquals(before + 7, quantityOf(3L, "WH-A-02-01"));
    }

    @Test
    void createInboundOrder_unknownProduct_shouldThrowBusinessException404() {
        InboundOrderCreateRequest req = request("X", item(999L, 1, "WH-A-01-01"));
        BusinessException ex = assertThrows(BusinessException.class, () -> inventoryService.createInboundOrder(req));
        assertEquals(404, ex.getCode());
    }

    @Test
    void createInboundOrder_unknownLocation_shouldThrowBusinessException404() {
        InboundOrderCreateRequest req = request("X", item(1L, 1, "NO-SUCH-LOC"));
        BusinessException ex = assertThrows(BusinessException.class, () -> inventoryService.createInboundOrder(req));
        assertEquals(404, ex.getCode());
    }

    @Test
    void createInboundOrder_sameRequestId_shouldReturnExistingOrderWithoutDuplicateAccumulation() {
        // 弱网重试场景：同一 requestId 提交两次，应返回同一入库单、库存只累加一次
        String rid = "test-rid-001";
        int before = quantityOf(1L, "WH-A-01-01");

        InboundOrderResponse first = inventoryService.createInboundOrder(
                requestWithId("重试供应商", rid, item(1L, 5, "WH-A-01-01")));
        InboundOrderResponse second = inventoryService.createInboundOrder(
                requestWithId("重试供应商", rid, item(1L, 5, "WH-A-01-01")));

        assertEquals(first.getId(), second.getId(), "同 requestId 应返回同一入库单");
        assertEquals(first.getOrderNo(), second.getOrderNo());
        assertEquals(before + 5, quantityOf(1L, "WH-A-01-01"), "库存应只累加一次");
    }

    // ---------- helpers ----------

    private int quantityOf(Long productId, String locationCode) {
        return inventoryRepository.findByProductIdAndLocationCode(productId, locationCode)
                .map(Inventory::getQuantity)
                .orElse(0);
    }

    private InboundOrderCreateRequest request(String supplierName, InboundItemRequest... items) {
        InboundOrderCreateRequest req = new InboundOrderCreateRequest();
        req.setSupplierName(supplierName);
        List<InboundItemRequest> list = new ArrayList<>();
        for (InboundItemRequest item : items) {
            list.add(item);
        }
        req.setItems(list);
        return req;
    }

    private InboundOrderCreateRequest requestWithId(String supplierName, String requestId, InboundItemRequest... items) {
        InboundOrderCreateRequest req = request(supplierName, items);
        req.setRequestId(requestId);
        return req;
    }

    private InboundItemRequest item(Long productId, int quantity, String locationCode) {
        InboundItemRequest item = new InboundItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        item.setLocationCode(locationCode);
        return item;
    }
}
