package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.dto.OutboundItemRequest;
import com.wms.dto.OutboundOrderCreateRequest;
import com.wms.dto.OutboundOrderResponse;
import com.wms.entity.Location;
import com.wms.entity.OutboundOrder;
import com.wms.entity.OutboundOrderItem;
import com.wms.entity.Product;
import com.wms.repository.InventoryRepository;
import com.wms.repository.LocationRepository;
import com.wms.repository.OrderSequenceRepository;
import com.wms.repository.OutboundOrderItemRepository;
import com.wms.repository.OutboundOrderRepository;
import com.wms.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 出库单创建 Service 层单元测试（选做A）
 *
 * Mock 掉 repository 与 Redis 门控，聚焦业务流程：
 * 预扣成功/库存不足/DB 兜底与补偿/幂等/明细合并/商品不存在。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboundOrderServiceTest {

    @Mock
    private OutboundOrderRepository outboundOrderRepository;
    @Mock
    private OutboundOrderItemRepository outboundOrderItemRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private RedisStockService redisStockService;
    @Mock
    private OrderSequenceRepository orderSequenceRepository;

    @InjectMocks
    private OutboundOrderService service;

    private static final long PRODUCT_ID = 1L;
    private static final String LOCATION = "WH-A-01-01";

    @BeforeEach
    void setUp() {
        Product p = Product.builder().id(PRODUCT_ID).name("测试商品").sku("SKU-TEST").build();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(locationRepository.findByCode(LOCATION)).thenReturn(Optional.of(
                Location.builder().id(1L).warehouseId(1L).code(LOCATION).build()));
        // 单号发号：序列表返回序号 101（advance 先行，lastInsertId 取号）
        when(orderSequenceRepository.advance("OUT")).thenReturn(1);
        when(orderSequenceRepository.lastInsertId()).thenReturn(101L);
        // save 主表时回填 id
        when(outboundOrderRepository.save(any(OutboundOrder.class))).thenAnswer(inv -> {
            OutboundOrder o = inv.getArgument(0);
            o.setId(1L);
            return o;
        });
    }

    @Test
    void create_shouldPreDeductDeductAndSave() {
        // given：Redis 预扣成功、DB 原子扣减成功
        when(redisStockService.preDeduct(PRODUCT_ID, LOCATION, 6)).thenReturn(true);
        when(inventoryRepository.deductStock(PRODUCT_ID, LOCATION, 6)).thenReturn(1);

        // when
        OutboundOrderResponse res = service.createOutboundOrder(request("客户A", item(6)));

        // then：单号/状态/明细正确
        assertTrue(res.getOrderNo().matches("OUT-\\d{8}-\\d{3,}"), "单号应为 OUT-YYYYMMDD-XXX，实际: " + res.getOrderNo());
        assertEquals("客户A", res.getCustomerName());
        assertEquals("COMPLETED", res.getStatus());
        assertEquals(1, res.getItems().size());
        assertEquals(Integer.valueOf(6), res.getItems().get(0).getQuantity());
        assertEquals("测试商品", res.getItems().get(0).getProductName());

        // 预扣 1 次、DB 扣减 1 次、明细保存 1 次、无补偿
        verify(redisStockService).preDeduct(PRODUCT_ID, LOCATION, 6);
        verify(inventoryRepository).deductStock(PRODUCT_ID, LOCATION, 6);
        verify(outboundOrderItemRepository).save(argThat(oi -> oi.getOrderId().equals(1L)
                && oi.getProductId().equals(PRODUCT_ID)
                && oi.getQuantity() == 6
                && oi.getLocationCode().equals(LOCATION)));
        verify(redisStockService, never()).revert(anyLong(), anyString(), anyInt());
    }

    @Test
    void create_redisInsufficient_shouldReject400WithoutAnyDeduct() {
        // given：Redis 预扣失败（库存不足）
        when(redisStockService.preDeduct(PRODUCT_ID, LOCATION, 6)).thenReturn(false);

        // when/then：抛 400，且不建单、不扣 DB、无补偿
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createOutboundOrder(request("客户A", item(6))));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("库存不足"), "提示应含库存不足，实际: " + ex.getMessage());

        verify(outboundOrderRepository, never()).save(any());
        verify(inventoryRepository, never()).deductStock(anyLong(), anyString(), anyInt());
        verify(redisStockService, never()).revert(anyLong(), anyString(), anyInt());
    }

    @Test
    void create_dbFallback_shouldRejectAndRevertRedis() {
        // given：Redis 预扣成功，但 DB 原子扣减返回 0（兜底拦截：Redis 与 DB 不一致/数据过期）
        when(redisStockService.preDeduct(PRODUCT_ID, LOCATION, 6)).thenReturn(true);
        when(inventoryRepository.deductStock(PRODUCT_ID, LOCATION, 6)).thenReturn(0);

        // when/then：抛 400，且已预扣的 Redis 库存被补偿回滚；
        // 主表 save 已发生但事务回滚，明细因扣减失败未保存
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createOutboundOrder(request("客户A", item(6))));
        assertEquals(400, ex.getCode());
        verify(redisStockService).revert(PRODUCT_ID, LOCATION, 6);
        verify(outboundOrderItemRepository, never()).save(any());
    }

    @Test
    void create_sameRequestId_shouldReplaySameOrder() {
        // given：已存在同 requestId 的出库单
        OutboundOrder existing = OutboundOrder.builder()
                .id(9L).orderNo("OUT-20260818-001").customerName("客户A").status("COMPLETED").build();
        when(outboundOrderRepository.findByRequestId("rid-out-1")).thenReturn(Optional.of(existing));
        when(outboundOrderItemRepository.findByOrderId(9L)).thenReturn(List.of(
                OutboundOrderItem.builder().orderId(9L).productId(PRODUCT_ID).quantity(6).locationCode(LOCATION).build()));

        // when：同 requestId 重复提交
        OutboundOrderCreateRequest req = request("客户A", item(6));
        req.setRequestId("rid-out-1");
        OutboundOrderResponse res = service.createOutboundOrder(req);

        // then：直接返回原单（不预扣、不扣减、不建单）
        assertEquals("OUT-20260818-001", res.getOrderNo());
        assertEquals(Integer.valueOf(6), res.getItems().get(0).getQuantity());
        verify(redisStockService, never()).preDeduct(anyLong(), anyString(), anyInt());
        verify(inventoryRepository, never()).deductStock(anyLong(), anyString(), anyInt());
        verify(outboundOrderRepository, never()).save(any());
    }

    @Test
    void create_mergeSameProductLocation_shouldDeductOnce() {
        // given：同 (商品, 库位) 两行 2 + 3，Redis/DB 均成功
        when(redisStockService.preDeduct(PRODUCT_ID, LOCATION, 5)).thenReturn(true);
        when(inventoryRepository.deductStock(PRODUCT_ID, LOCATION, 5)).thenReturn(1);

        OutboundOrderCreateRequest req = request("客户A", item(2), item(3));

        // when
        OutboundOrderResponse res = service.createOutboundOrder(req);

        // then：合并为一行总量 5，只扣减一次
        assertEquals(1, res.getItems().size());
        assertEquals(Integer.valueOf(5), res.getItems().get(0).getQuantity());
        verify(inventoryRepository, times(1)).deductStock(PRODUCT_ID, LOCATION, 5);
        verify(redisStockService, times(1)).preDeduct(PRODUCT_ID, LOCATION, 5);
    }

    @Test
    void create_unknownProduct_shouldThrow404() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());
        OutboundItemRequest bad = new OutboundItemRequest();
        bad.setProductId(999L);
        bad.setQuantity(1);
        bad.setLocationCode(LOCATION);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createOutboundOrder(request("客户A", bad)));
        assertEquals(404, ex.getCode());
        verify(redisStockService, never()).preDeduct(anyLong(), anyString(), anyInt());
    }

    // ---------- helpers ----------

    private OutboundOrderCreateRequest request(String customerName, OutboundItemRequest... items) {
        OutboundOrderCreateRequest req = new OutboundOrderCreateRequest();
        req.setCustomerName(customerName);
        req.setItems(List.of(items));
        return req;
    }

    private OutboundItemRequest item(int quantity) {
        OutboundItemRequest item = new OutboundItemRequest();
        item.setProductId(PRODUCT_ID);
        item.setQuantity(quantity);
        item.setLocationCode(LOCATION);
        return item;
    }
}
