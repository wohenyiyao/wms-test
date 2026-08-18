package com.wms.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 入库单创建 API 冒烟测试（任务 1）
 *
 * 覆盖：HTTP 201 + 统一信封 code=200、单号格式、异常（404/400）。
 * 每个用例 @Transactional 回滚，不污染数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InboundOrderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createInboundOrder_shouldReturn201WithBodyCode200AndOrderNo() throws Exception {
        String body = """
                {"supplierName":"接口测试-供应商","items":[{"productId":1,"quantity":6,"locationCode":"WH-A-01-01"}]}
                """;

        mockMvc.perform(post("/api/inbound-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("入库单创建成功"))
                .andExpect(jsonPath("$.data.orderNo").value(matchesPattern("IN-\\d{8}-\\d{3,}")))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(6));
    }

    @Test
    void createInboundOrder_unknownProduct_shouldReturnBodyCode404() throws Exception {
        String body = """
                {"supplierName":"X","items":[{"productId":999,"quantity":1,"locationCode":"WH-A-01-01"}]}
                """;

        mockMvc.perform(post("/api/inbound-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void createInboundOrder_nullQuantity_shouldReturn400() throws Exception {
        // quantity 缺失：@NotNull 校验兜底（此前 review 修复的 Bug 回归用例）
        String body = """
                {"supplierName":"X","items":[{"productId":1,"locationCode":"WH-A-01-01"}]}
                """;

        mockMvc.perform(post("/api/inbound-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void createInboundOrder_sameRequestId_shouldReplaySameOrder() throws Exception {
        // 弱网重试场景：同一 requestId 提交两次，返回同一 orderNo（不重复建单）
        String body = """
                {"supplierName":"幂等测试","requestId":"api-test-rid-001","items":[{"productId":2,"quantity":3,"locationCode":"WH-A-01-02"}]}
                """;

        String orderNo1 = postAndGetOrderNo(body);
        String orderNo2 = postAndGetOrderNo(body);

        assertEquals(orderNo1, orderNo2, "同 requestId 重试应返回同一入库单号");
    }

    private String postAndGetOrderNo(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/inbound-orders")
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
