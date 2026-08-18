<script setup lang="ts">
/**
 * ============================================
 *  库存查询页 — 任务2（已实现）
 * ============================================
 *
 * 功能：
 * 1. 搜索栏：商品名称/SKU/库位编码 模糊搜索（输入防抖 300ms 自动搜索 + 查询按钮）
 *             + 仓库下拉筛选（切换即查）
 * 2. 表格展示：商品名称、SKU、库位编码、仓库名、库存数量、更新时间，分页
 * 3. 库存 < 10 行红色加粗高亮
 * 4. 「告急库存」提示条：显示告急数量，点击切换为仅看告急库存（再点恢复）
 * 5. 自动搜索完成时轻提示用户（避免"列表怎么自己变了"的困惑）
 */
import { ref, watch, onMounted, type CSSProperties } from 'vue'
import { ElMessage } from 'element-plus'
import { getInventory, getWarehouses, type Warehouse, type InventoryItem } from '@/api'
import { buildInventoryQuery, debounce } from '@/utils/filters'
import PageCard from '@/components/PageCard.vue'

const keyword = ref('')
const warehouseId = ref<number | undefined>()
const lowStockOnly = ref(false)
const loading = ref(false)
const inventoryList = ref<InventoryItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const warehouses = ref<Warehouse[]>([])
/** 告急库存数量（非告急模式=全局告急数；告急模式=当前筛选结果数） */
const alarmCount = ref(0)

/** 搜索防抖（300ms，停止输入后自动搜索；逻辑抽取于 @/utils/filters 便于单测） */
const search = debounce(() => {
  page.value = 1
  loadInventory(true)
}, 300)

const loadInventory = async (autoHint = false) => {
  loading.value = true
  try {
    const query = buildInventoryQuery(keyword.value, warehouseId.value, lowStockOnly.value, page.value, pageSize.value)
    const listP = getInventory(query)
    // 非告急模式时并行取一次告急总数（轻量 count，pageSize=1 只取 total）
    const alarmP = lowStockOnly.value
      ? Promise.resolve(null)
      : getInventory({ ...query, lowStockOnly: true, page: 1, pageSize: 1 })
    const [listRes, alarmRes] = await Promise.all([listP, alarmP])
    inventoryList.value = listRes.data.list
    total.value = listRes.data.total
    alarmCount.value = lowStockOnly.value ? listRes.data.total : (alarmRes?.data.total ?? 0)
    if (autoHint) {
      ElMessage({ message: `已按条件自动筛选，共 ${total.value} 条`, type: 'info', duration: 1200 })
    }
  } catch (e: any) {
    ElMessage.error('加载库存失败: ' + (e.response?.data?.message || e.message))
  } finally {
    loading.value = false
  }
}

/** 手动查询：取消未触发的防抖后立即搜索 */
const manualSearch = () => {
  search.cancel()
  page.value = 1
  loadInventory()
}

/** 切换告急筛选：点击提示条时在「全部 / 仅告急」间切换 */
const toggleAlarm = () => {
  lowStockOnly.value = !lowStockOnly.value
  page.value = 1
  loadInventory()
}

/** 库存 < 10 的行红色加粗（el-table row-style 回调签名为 ({ row, rowIndex })） */
const getRowStyle = ({ row }: { row: any }): CSSProperties => {
  if (row.quantity < 10) {
    return { color: '#f56c6c', fontWeight: 'bold' }
  }
  return {}
}

/** 后端 ISO 时间转展示格式 */
const formatTime = (s?: string) => (s ? s.replace('T', ' ').substring(0, 19) : '-')

watch(keyword, search.run)
watch(warehouseId, () => {
  page.value = 1
  loadInventory()
})

onMounted(async () => {
  try {
    const w = await getWarehouses()
    warehouses.value = w.data
  } catch (e: any) {
    ElMessage.error('加载仓库列表失败: ' + (e.response?.data?.message || e.message))
  }
  loadInventory()
})
</script>

<template>
  <PageCard title="库存查询">
    <!-- 搜索栏 -->
    <div class="table-toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索商品名称/SKU/库位编码，输入后自动搜索"
        style="width: 320px"
        clearable
        @keyup.enter="manualSearch"
      />
      <el-select v-model="warehouseId" placeholder="选择仓库" clearable style="width: 200px">
        <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
      </el-select>
      <el-button type="primary" @click="manualSearch">查询</el-button>
    </div>

    <!-- 告急库存提示条（点击切换筛选） -->
    <div
      v-if="alarmCount > 0 || lowStockOnly"
      class="alarm-bar"
      :class="{ active: lowStockOnly }"
      @click="toggleAlarm"
    >
      <span>⚠️ 库存告急（&lt;10）：{{ alarmCount }} 项</span>
      <span class="alarm-action">{{ lowStockOnly ? '点击恢复全部库存' : '点击仅查看告急库存' }}</span>
    </div>

    <!-- 表格 -->
    <el-table :data="inventoryList" v-loading="loading" border stripe :row-style="getRowStyle">
      <el-table-column prop="productName" label="商品名称" min-width="160" />
      <el-table-column prop="sku" label="SKU" width="150" />
      <el-table-column prop="locationCode" label="库位编码" width="150" />
      <el-table-column prop="warehouseName" label="仓库" width="140" />
      <el-table-column prop="quantity" label="库存数量" width="100" align="center" />
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
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

<style scoped>
.alarm-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  padding: 8px 16px;
  border-radius: 4px;
  background: #fdf6ec;
  border: 1px solid #f5dab1;
  color: #e6a23c;
  cursor: pointer;
  transition: all 0.2s;
}
.alarm-bar:hover {
  border-color: #e6a23c;
}
.alarm-bar.active {
  background: #fef0f0;
  border-color: #f56c6c;
  color: #f56c6c;
}
.alarm-action {
  font-size: 12px;
  text-decoration: underline;
}
</style>
