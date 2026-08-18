package com.wms.service;

import com.wms.common.PageResult;
import com.wms.dto.InboundItemRequest;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.InventoryResponse;
import com.wms.entity.Product;
import com.wms.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 库存查询 Service 层集成测试（任务 2）
 *
 * 每个用例 @Transactional 回滚：测试内新建唯一商品并入库，
 * 数据完全确定，不依赖既有库存数据。
 */
@SpringBootTest
@Transactional
class InventoryQueryServiceTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void queryInventory_shouldReturnRowsWithFullFields() {
        // given：新建唯一商品并入库 5 件到 WH-A-01-01（新商品在该库位必为新行，quantity 确定=5）
        String tag = tag();
        Product product = saveProduct(tag);
        inventoryService.createInboundOrder(request("查询测试", item(product.getId(), 5, "WH-A-01-01")));

        // when：无条件查询
        PageResult<InventoryResponse> result = inventoryService.queryInventory(null, null, false, 1, 20);

        // then：返回结构正确，目标行字段完整
        assertTrue(result.getTotal() >= 1);
        InventoryResponse row = result.getList().stream()
                .filter(r -> r.getProductId().equals(product.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(row, "应包含刚入库的商品行");
        assertEquals("SKU-" + tag, row.getSku());
        assertNotNull(row.getProductName());
        assertNotNull(row.getWarehouseName(), "仓库名应通过 库位->仓库 join 反查出来");
        assertEquals("WH-A-01-01", row.getLocationCode());
        assertEquals(Integer.valueOf(5), row.getQuantity());
        assertNotNull(row.getUpdatedAt());
    }

    @Test
    void queryInventory_keyword_shouldMatchSkuAndLocationCode() {
        // given：两个唯一商品分别入 WH-A-01-01 / WH-A-01-02
        Product p1 = saveProduct(tag());
        Product p2 = saveProduct(tag());
        inventoryService.createInboundOrder(request("查询测试",
                item(p1.getId(), 3, "WH-A-01-01"),
                item(p2.getId(), 4, "WH-A-01-02")));

        // when：按 SKU 精确搜索 p1
        PageResult<InventoryResponse> bySku = inventoryService.queryInventory(p1.getSku(), null, false, 1, 20);
        // then：只返回 p1 的行
        assertEquals(1, bySku.getTotal(), "SKU 精确匹配应只返回该商品");
        assertEquals(p1.getSku(), bySku.getList().get(0).getSku());

        // when：按库位编码搜索 WH-A-01-02
        PageResult<InventoryResponse> byLoc = inventoryService.queryInventory("WH-A-01-02", null, false, 1, 20);
        // then：所有行库位编码包含该关键字
        assertTrue(byLoc.getTotal() >= 1);
        assertTrue(byLoc.getList().stream().allMatch(r -> r.getLocationCode().contains("WH-A-01-02")));
    }

    @Test
    void queryInventory_warehouseId_shouldFilterByWarehouse() {
        // given：p1 入 WH-A（仓1），p2 入 WH-B（仓2）
        Product p1 = saveProduct(tag());
        Product p2 = saveProduct(tag());
        inventoryService.createInboundOrder(request("查询测试",
                item(p1.getId(), 2, "WH-A-01-01"),
                item(p2.getId(), 6, "WH-B-01-01")));

        // when：筛仓库 1（WH-A）
        PageResult<InventoryResponse> whA = inventoryService.queryInventory(null, 1L, false, 1, 100);
        // then：包含 p1 行、不包含 p2 行
        assertTrue(whA.getList().stream().anyMatch(r -> r.getProductId().equals(p1.getId())));
        assertTrue(whA.getList().stream().noneMatch(r -> r.getProductId().equals(p2.getId())),
                "仓库1 不应包含 WH-B 的行");

        // when：筛仓库 2（WH-B）
        PageResult<InventoryResponse> whB = inventoryService.queryInventory(null, 2L, false, 1, 100);
        assertTrue(whB.getList().stream().anyMatch(r -> r.getProductId().equals(p2.getId())));
    }

    @Test
    void queryInventory_lowStockOnly_shouldReturnOnlyQuantityBelow10() {
        // given：两行库存，分别 5 和 99
        Product p1 = saveProduct(tag());
        Product p2 = saveProduct(tag());
        inventoryService.createInboundOrder(request("查询测试",
                item(p1.getId(), 5, "WH-A-01-01"),
                item(p2.getId(), 99, "WH-A-01-02")));

        // when：告急筛选
        PageResult<InventoryResponse> alarm = inventoryService.queryInventory(null, null, true, 1, 100);

        // then：所有行 quantity < 10，且包含 p1 行
        assertTrue(alarm.getTotal() >= 1);
        assertTrue(alarm.getList().stream().allMatch(r -> r.getQuantity() < 10),
                "告急筛选只应返回 quantity<10 的行");
        assertTrue(alarm.getList().stream().anyMatch(r -> r.getProductId().equals(p1.getId())));

        // then：非告急筛选的 total 应不少于告急 total
        PageResult<InventoryResponse> all = inventoryService.queryInventory(null, null, false, 1, 100);
        assertTrue(all.getTotal() >= alarm.getTotal());
    }

    @Test
    void queryInventory_pagination_shouldRespectPageAndPageSize() {
        // given：三个商品各入一行
        for (int i = 0; i < 3; i++) {
            Product p = saveProduct(tag());
            inventoryService.createInboundOrder(request("查询测试", item(p.getId(), 1, "WH-A-01-01")));
        }

        // when：pageSize=2 取第一页
        PageResult<InventoryResponse> page1 = inventoryService.queryInventory(null, null, false, 1, 2);
        assertEquals(2, page1.getList().size(), "第一页应返回 2 行");
        assertTrue(page1.getTotal() >= 3);
        assertEquals(1, page1.getPage());
        assertEquals(2, page1.getPageSize());

        // when：第二页
        PageResult<InventoryResponse> page2 = inventoryService.queryInventory(null, null, false, 2, 2);
        assertTrue(page2.getList().size() >= 1);
        assertTrue(page2.getList().size() <= 2);
    }

    // ---------- helpers ----------

    private String tag() {
        return "Q" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private Product saveProduct(String tag) {
        return productRepository.save(Product.builder()
                .name("查询测试商品-" + tag)
                .sku("SKU-" + tag)
                .build());
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

    private InboundItemRequest item(Long productId, int quantity, String locationCode) {
        InboundItemRequest item = new InboundItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        item.setLocationCode(locationCode);
        return item;
    }
}
