package com.wms.service;

import com.wms.dto.InboundItemRequest;
import com.wms.dto.InboundOrderCreateRequest;
import com.wms.dto.OutboundItemRequest;
import com.wms.dto.OutboundOrderCreateRequest;
import com.wms.entity.Inventory;
import com.wms.entity.OutboundOrderItem;
import com.wms.entity.Product;
import com.wms.repository.InventoryRepository;
import com.wms.repository.InboundOrderItemRepository;
import com.wms.repository.InboundOrderRepository;
import com.wms.repository.OutboundOrderItemRepository;
import com.wms.repository.OutboundOrderRepository;
import com.wms.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 出库并发防超卖集成测试（选做A 核心验证）
 *
 * 真实链路（非 Mock）：Redis Lua 预扣 + DB 原子条件更新双层防线，
 * 20 个线程并发抢同一库存（初始 100，每单 6 件 → 理论上限 floor(100/6)=16 单）。
 *
 * 断言（无超卖、无丢失）：
 * - 成功数 + 失败数 = 请求总数，成功数 ≤ 16
 * - 最终库存 = 初始库存 - 成功数 × 每单数量，且 ≥ 0
 * - Redis 镜像与 DB 库存一致（预扣与 DB 扣减同步、补偿回滚自洽）
 *
 * 本类不 @Transactional（并发线程事务独立，需真实提交验证）；
 * @BeforeEach 造独立测试数据（唯一 SKU 商品 + 入库 100），@AfterEach 全量清理。
 */
@SpringBootTest
class OutboundConcurrencyTest {

    private static final int INITIAL_QTY = 100;
    private static final int PER_ORDER_QTY = 6;
    private static final int THREADS = 20;
    private static final String LOCATION = "WH-A-01-01";

    @Autowired
    private OutboundOrderService outboundOrderService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private OutboundOrderItemRepository outboundOrderItemRepository;
    @Autowired
    private OutboundOrderRepository outboundOrderRepository;
    @Autowired
    private InboundOrderItemRepository inboundOrderItemRepository;
    @Autowired
    private InboundOrderRepository inboundOrderRepository;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Product product;
    private Long inboundOrderId;

    @BeforeEach
    void setUp() {
        String tag = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        product = productRepository.save(Product.builder()
                .name("并发测试商品-" + tag)
                .sku("SKU-CONC-" + tag)
                .build());
        // 走入库完整链路：建库存行 100 + 同步 Redis 镜像
        inboundOrderId = inventoryService.createInboundOrder(inboundRequest(product.getId(), INITIAL_QTY)).getId();
    }

    @Test
    void concurrentDeduct_shouldNeverOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    outboundOrderService.createOutboundOrder(outboundRequest(product.getId(), PER_ORDER_QTY));
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "所有线程应在限时内就绪");
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务应在限时内完成");
        pool.shutdown();

        // 结果校验
        int finalQty = inventoryRepository.findByProductIdAndLocationCode(product.getId(), LOCATION)
                .map(Inventory::getQuantity)
                .orElse(-1);
        int maxPossible = INITIAL_QTY / PER_ORDER_QTY; // 16

        assertEquals(THREADS, success.get() + fail.get(), "所有请求都必须有明确结果（成功或库存不足）");
        assertTrue(success.get() <= maxPossible,
                "成功数不能超过理论上限 " + maxPossible + "，实际 " + success.get() + "（超卖！）");
        assertEquals(INITIAL_QTY - success.get() * PER_ORDER_QTY, finalQty,
                "库存扣减总量必须精确等于成功单量（不超卖、不丢失）");
        assertTrue(finalQty >= 0, "库存不能为负");

        // Redis 镜像应与 DB 一致
        String redisVal = redisTemplate.opsForValue().get(stockKey());
        assertNotNull(redisVal, "Redis 库存 key 应存在");
        assertEquals(finalQty, Integer.parseInt(redisVal),
                "Redis 镜像应与 DB 库存一致，实际 Redis=" + redisVal + " DB=" + finalQty);
    }

    // ---------- helpers ----------

    private String stockKey() {
        return "wms:stock:" + product.getId() + ":" + LOCATION;
    }

    private InboundOrderCreateRequest inboundRequest(Long productId, int quantity) {
        InboundItemRequest item = new InboundItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        item.setLocationCode(LOCATION);
        InboundOrderCreateRequest req = new InboundOrderCreateRequest();
        req.setSupplierName("并发测试-供应商");
        req.setItems(List.of(item));
        return req;
    }

    private OutboundOrderCreateRequest outboundRequest(Long productId, int quantity) {
        OutboundItemRequest item = new OutboundItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        item.setLocationCode(LOCATION);
        OutboundOrderCreateRequest req = new OutboundOrderCreateRequest();
        req.setCustomerName("并发测试-客户");
        req.setItems(List.of(item));
        return req;
    }

    @AfterEach
    void tearDown() {
        // 本类不 @Transactional（并发线程事务独立），清理操作需显式事务：
        // derived delete 在无事务时报 "No EntityManager with actual transaction"。
        // 注意删除顺序：outbound_order_items.order_id 有外键指向 outbound_orders.id，
        // 必须先删明细再删主表（此前顺序反了被 FK 拦截，导致残留脏数据）。
        transactionTemplate.executeWithoutResult(status -> {
            List<OutboundOrderItem> items = outboundOrderItemRepository.findByProductId(product.getId());
            List<Long> orderIds = items.stream().map(OutboundOrderItem::getOrderId).distinct().toList();
            outboundOrderItemRepository.deleteByProductId(product.getId());
            if (!orderIds.isEmpty()) {
                outboundOrderRepository.deleteAllById(orderIds);
            }
            // 清理 @BeforeEach 建的入库单（明细 + 主表）与库存行
            inboundOrderItemRepository.deleteByProductId(product.getId());
            if (inboundOrderId != null) {
                inboundOrderRepository.deleteById(inboundOrderId);
            }
            inventoryRepository.deleteByProductId(product.getId());
            productRepository.deleteById(product.getId());
        });
        // 清理 Redis key（不依赖 DB 事务）
        try {
            redisTemplate.delete(stockKey());
        } catch (Exception ignored) {
            // 清理兜底
        }
    }
}
