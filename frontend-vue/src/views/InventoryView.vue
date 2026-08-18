<script setup lang="ts">
/**
 * ============================================
 *  库存查询页 — 候选人需要实现（任务2）
 * ============================================
 *
 * 需求：
 * 1. 搜索栏：商品名称/SKU 模糊搜索 + 仓库下拉筛选
 * 2. 表格展示：商品名称、SKU、库位编码、仓库名、库存数量、更新时间
 * 3. 库存数量 < 10 的行高亮为红色
 * 4. 支持分页
 *
 * 页面骨架已按统一风格（PageCard + 工具栏 + 表格 + 分页）搭好，
 * 待实现逻辑见下方 TODO。
 */
import { ref } from 'vue'
import PageCard from '@/components/PageCard.vue'

const keyword = ref('')
const warehouseId = ref<number | undefined>()
const loading = ref(false)
const inventoryList = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

// TODO: 候选人实现 loadInventory 函数
const loadInventory = async () => {
  // 提示：调用 getInventory({ keyword, warehouseId, page, pageSize })
}

// TODO: 候选人实现表格行样式
const getRowStyle = (row: any) => {
  // 提示：当 row.quantity < 10 时返回红色样式
  return {}
}
</script>

<template>
  <PageCard title="库存查询">
    <!-- 搜索栏 -->
    <div class="table-toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索商品名称/SKU..."
        style="width: 300px"
        clearable
        @keyup.enter="loadInventory"
        @clear="loadInventory"
      />
      <el-select v-model="warehouseId" placeholder="选择仓库" clearable style="width: 200px">
        <!-- TODO: 加载仓库列表 -->
      </el-select>
      <el-button type="primary" @click="loadInventory">查询</el-button>
    </div>

    <!-- 表格 -->
    <el-table :data="inventoryList" v-loading="loading" border stripe :row-style="getRowStyle">
      <el-table-column prop="productName" label="商品名称" />
      <el-table-column prop="sku" label="SKU" width="150" />
      <el-table-column prop="locationCode" label="库位编码" width="150" />
      <el-table-column prop="warehouseName" label="仓库" width="120" />
      <el-table-column prop="quantity" label="库存数量" width="100" />
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
    </el-table>

    <!-- 分页 -->
    <div class="table-pagination">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="loadInventory"
      />
    </div>

    <el-empty v-if="!loading && inventoryList.length === 0" description="暂无库存数据，请先完成入库操作" />
  </PageCard>
</template>
