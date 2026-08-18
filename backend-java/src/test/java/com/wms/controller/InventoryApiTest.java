package com.wms.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 库存查询 API 冒烟测试（任务 2）
 *
 * 依赖启动时注入的种子数据（商品 SKU-001 等已有库存行）。
 * 每个用例 @Transactional 回滚，不污染数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InventoryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getInventory_shouldReturn200WithPageResult() throws Exception {
        mockMvc.perform(get("/api/inventory").param("page", "1").param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(5));
    }

    @Test
    void getInventory_keyword_shouldReturnOnlyMatchingRows() throws Exception {
        // 种子商品 SKU-001：所有返回行的 sku 都应包含关键字
        mockMvc.perform(get("/api/inventory").param("keyword", "SKU-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.data.list[*].sku", everyItem(org.hamcrest.Matchers.containsString("SKU-001"))));
    }

    @Test
    void getInventory_warehouseId_shouldReturnRowsWithWarehouseName() throws Exception {
        // 仓库 1（WH-A）下应有库存行，且仓库名非空（join 反查成功）
        mockMvc.perform(get("/api/inventory").param("warehouseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.list[*].warehouseName", everyItem(notNullValue())));
    }

    @Test
    void getInventory_lowStockOnly_shouldReturnOnlyQuantityBelow10() throws Exception {
        mockMvc.perform(get("/api/inventory").param("lowStockOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[*].quantity", everyItem(lessThan(10))));
    }

    @Test
    void getInventory_pageSizeExceedsMax_shouldCapTo100() throws Exception {
        // 可用性兜底：pageSize=9999 应被截断到 100
        mockMvc.perform(get("/api/inventory").param("pageSize", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(lessThan(101)));
    }

    @Test
    void getInventory_pageZero_shouldFallbackToFirstPage() throws Exception {
        // 页码下限兜底：page=0 应视为第 1 页
        mockMvc.perform(get("/api/inventory").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1));
    }
}
