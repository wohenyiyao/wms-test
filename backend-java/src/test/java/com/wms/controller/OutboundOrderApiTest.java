package com.wms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.service.RedisStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 出库单创建 API 冒烟测试（选做A）
 *
 * 覆盖：HTTP 201 + 统一信封 code=200、单号格式 OUT-xxx、库存不足 400、幂等、商品/库位不存在 404。
 * DB 侧 @Transactional 回滚；Redis 预扣不随 DB 事务回滚，故 @AfterEach 清理测试用到的库存 key
 * （下次预扣时懒加载重建，不会污染真实库存）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OutboundOrderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisStockService redisStockService;

    /** 测试用到的 (商品, 库位) 组合，结束后清理其 Redis key */
    private static final Long PRODUCT_ID = 1L;
    private static final String LOCATION = "WH-A-01-01";

    @AfterEach
    void cleanupRedisKeys() {
        redisStockService.deleteKey(PRODUCT_ID, LOCATION);
    }

    @Test
    void createOutboundOrder_shouldReturn201WithBodyCode200AndOrderNo() throws Exception {
        String body = """
                {"customerName":"接口测试-客户","items":[{"productId":1,"quantity":6,"locationCode":"WH-A-01-01"}]}
                """;

        mockMvc.perform(post("/api/outbound-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("出库单创建成功"))
                .andExpect(jsonPath("$.data.orderNo").value(matchesPattern("OUT-\\d{8}-\\d{3,}")))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(6))
                .andExpect(jsonPath("$.data.items[0].locationCode").value("WH-A-01-01"));
    }

    @Test
    void createOutboundOrder_insufficientStock_shouldReturn400WithBodyCode400() throws Exception {
        // 出库量远大于实际库存：Redis 预扣阶段即拒绝
        String body = """
                {"customerName":"接口测试-客户","items":[{"productId":1,"quantity":999999,"locationCode":"WH-A-01-01"}]}
                """;

        mockMvc.perform(post("/api/outbound-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("库存不足")));
    }

    @Test
    void createOutboundOrder_sameRequestId_shouldReplaySameOrder() throws Exception {
        // 弱网重试场景：同一 requestId 提交两次，返回同一 orderNo（不重复出库/不重复扣库存）
        String body = """
                {"customerName":"幂等测试","requestId":"api-out-rid-001","items":[{"productId":1,"quantity":3,"locationCode":"WH-A-01-01"}]}
                """;

        String orderNo1 = postAndGetOrderNo(body);
        String orderNo2 = postAndGetOrderNo(body);

        assertEquals(orderNo1, orderNo2, "同 requestId 重试应返回同一出库单号");
    }

    @Test
    void createOutboundOrder_unknownProduct_shouldReturnBodyCode404() throws Exception {
        String body = """
                {"customerName":"X","items":[{"productId":999,"quantity":1,"locationCode":"WH-A-01-01"}]}
                """;

        mockMvc.perform(post("/api/outbound-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void createOutboundOrder_unknownLocation_shouldReturnBodyCode404() throws Exception {
        String body = """
                {"customerName":"X","items":[{"productId":1,"quantity":1,"locationCode":"NO-SUCH-LOC"}]}
                """;

        mockMvc.perform(post("/api/outbound-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    private String postAndGetOrderNo(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/outbound-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode node = new ObjectMapper()
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return node.path("data").path("orderNo").asText();
    }
}
