package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.dto.InboundItemRequest;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.ProductCreateRequest;
import com.wms.entity.Product;
import com.wms.repository.InboundOrderItemRepository;
import com.wms.repository.InventoryRepository;
import com.wms.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商品删除 Service 层测试（逻辑删除 + 渐进式确认回归用例）
 *
 * 删除 = 置 deleted 标记（不物理删除）：已删商品不可见/不可再引用，
 * 关联数据（库存、历史单据）保留可追溯；SKU 全局唯一（含已删记录）。
 * 渐进式确认：有关联数据且未传 force 时 400 提示数量，force=true（确认）后才执行删除。
 * 每个用例 @Transactional 回滚，不污染数据库。
 */
@SpringBootTest
@Transactional
class ProductDeleteServiceTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InboundOrderItemRepository inboundOrderItemRepository;

    @Autowired
    private InventoryService inventoryService;

    @Test
    void delete_withInventory_withoutForce_shouldRejectWithCounts() {
        // given：新商品 + 入库（产生库存 + 历史入库记录关联）
        Product p = saveProduct();
        inventoryService.createInboundOrder(inboundRequest(p.getId(), 5, "WH-A-01-01"));
        assertTrue(inventoryRepository.countByProductId(p.getId()) >= 1);
        assertTrue(inboundOrderItemRepository.countByProductId(p.getId()) >= 1);

        // when：不带 force 删除 → 应拒绝并提示关联数量（渐进式确认第一步）
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.delete(p.getId(), false));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("库存"), "提示应包含关联库存信息，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("删除后商品将不可见"), "提示应说明逻辑删除影响，实际: " + ex.getMessage());

        // then：商品未被删除（等待二次确认）
        assertTrue(productRepository.existsById(p.getId()), "未确认前商品不应被删除");
        assertTrue(inventoryRepository.countByProductId(p.getId()) >= 1);
    }

    @Test
    void delete_withInventory_forceTrue_shouldSoftDeleteAndKeepReferences() {
        // given：新商品 + 入库（产生库存 + 历史入库记录关联）
        Product p = saveProduct();
        inventoryService.createInboundOrder(inboundRequest(p.getId(), 5, "WH-A-01-01"));
        assertTrue(inventoryRepository.countByProductId(p.getId()) >= 1);
        assertTrue(inboundOrderItemRepository.countByProductId(p.getId()) >= 1);

        // when：force=true 删除（二次确认后，仍为逻辑删除）
        productService.delete(p.getId(), true);

        // then：商品不可见（已删），但关联数据全部保留（可追溯，无孤立脏数据）
        assertFalse(productRepository.existsById(p.getId()), "已删商品 existsById 应为 false（软删过滤）");
        assertTrue(inventoryRepository.countByProductId(p.getId()) >= 1, "关联库存应保留");
        assertTrue(inboundOrderItemRepository.countByProductId(p.getId()) >= 1, "历史入库记录应保留");
    }

    @Test
    void delete_withoutReferences_shouldSucceed() {
        // given：新商品，无任何关联
        Product p = saveProduct();
        assertEquals(0, inventoryRepository.countByProductId(p.getId()));

        // when：直接删除（无关联，无需 force）
        productService.delete(p.getId(), false);

        // then：成功逻辑删除
        assertFalse(productRepository.existsById(p.getId()));
    }

    @Test
    void deletedProduct_shouldBeInvisibleInQuery() {
        // given：新商品并删除
        Product p = saveProduct();
        productService.delete(p.getId(), true);

        // when/then：详情 404；列表搜索不到
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.getById(p.getId()));
        assertEquals(404, ex.getCode());
        boolean inList = productService.list(p.getSku()).stream().anyMatch(r -> r.getId().equals(p.getId()));
        assertFalse(inList, "搜索列表不应包含已删商品");
    }

    @Test
    void delete_notExist_shouldThrow404() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> productService.delete(999999L, false));
        assertEquals(404, ex.getCode());
    }

    @Test
    void create_sameSku_afterDelete_shouldReject() {
        // given：新商品并删除（SKU 仍被占用）
        Product p = saveProduct();
        productService.delete(p.getId(), true);

        // when：用同一 SKU 重建 → 应拒绝（SKU 全局唯一，含已删记录）
        ProductCreateRequest req = new ProductCreateRequest();
        req.setName("重建商品");
        req.setSku(p.getSku());
        req.setUnit("个");
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.create(req));
        assertTrue(ex.getMessage().contains("SKU已存在"), "提示应说明 SKU 已存在，实际: " + ex.getMessage());
    }

    // ---------- helpers ----------

    private Product saveProduct() {
        String tag = "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ProductCreateRequest req = new ProductCreateRequest();
        req.setName("删除测试商品-" + tag);
        req.setSku("SKU-DEL-" + tag);
        req.setUnit("个");
        Long id = productService.create(req).getId();
        return productRepository.findById(id).orElseThrow();
    }

    private InboundOrderCreateRequest inboundRequest(Long productId, int quantity, String locationCode) {
        InboundItemRequest item = new InboundItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        item.setLocationCode(locationCode);
        InboundOrderCreateRequest req = new InboundOrderCreateRequest();
        req.setSupplierName("删除测试");
        req.setItems(List.of(item));
        return req;
    }
}
