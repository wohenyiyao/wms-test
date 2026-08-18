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
 * 商品删除 Service 层测试（任务 3：删除关联校验修复的回归用例）
 *
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
    void delete_withInventory_shouldRejectWithoutForce() {
        // given：新商品 + 入库（产生库存关联）
        Product p = saveProduct();
        inventoryService.createInboundOrder(inboundRequest(p.getId(), 5, "WH-A-01-01"));
        assertTrue(inventoryRepository.countByProductId(p.getId()) >= 1);

        // when：不传 force 删除 → 应拒绝并提示关联数量
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.delete(p.getId(), false));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("库存"), "提示应包含关联库存信息，实际: " + ex.getMessage());

        // then：商品与库存都还在（未被误删）
        assertTrue(productRepository.existsById(p.getId()));
        assertTrue(inventoryRepository.countByProductId(p.getId()) >= 1);
    }

    @Test
    void delete_withInventory_forceTrue_shouldCascadeClean() {
        // given：新商品 + 入库（库存 + 入库明细关联）
        Product p = saveProduct();
        inventoryService.createInboundOrder(inboundRequest(p.getId(), 7, "WH-A-01-01"));
        long invBefore = inventoryRepository.countByProductId(p.getId());
        long itemBefore = inboundOrderItemRepository.countByProductId(p.getId());
        assertTrue(invBefore >= 1);
        assertTrue(itemBefore >= 1);

        // when：force=true 删除
        productService.delete(p.getId(), true);

        // then：商品、关联库存、关联入库明细全部清理（无孤立脏数据）
        assertFalse(productRepository.existsById(p.getId()), "商品应被删除");
        assertEquals(0, inventoryRepository.countByProductId(p.getId()), "关联库存应被级联清理");
        assertEquals(0, inboundOrderItemRepository.countByProductId(p.getId()), "关联入库明细应被级联清理");
    }

    @Test
    void delete_withoutAnyReference_shouldSucceed() {
        // given：新商品，无任何库存/明细关联
        Product p = saveProduct();
        assertEquals(0, inventoryRepository.countByProductId(p.getId()));
        assertEquals(0, inboundOrderItemRepository.countByProductId(p.getId()));

        // when：删除
        productService.delete(p.getId(), false);

        // then：成功删除
        assertFalse(productRepository.existsById(p.getId()));
    }

    @Test
    void delete_notExist_shouldThrow404() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> productService.delete(999999L, false));
        assertEquals(404, ex.getCode());
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
