<script setup lang="ts">
/**
 * ============================================
 *  入库管理页 — 任务1（已实现）
 * ============================================
 *
 * 功能：
 * 1. 供应商名称
 * 2. 入库明细以「弹窗表单」添加/编辑：商品（下拉搜索）→ 仓库 → 库位（级联）→ 数量
 * 3. 明细以表格展示（商品名/仓库名/库位/数量），支持编辑、删除
 * 4. 提交（调用 createInboundOrder API，携带 requestId 幂等键）
 *
 * 提交前逐行校验；成功后重置表单并提示入库单号。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createInboundOrder,
  getProducts,
  getWarehouses,
  getLocations,
  type Product,
  type Warehouse,
  type Location,
  type InboundItemRequest,
} from '@/api'
import PageCard from '@/components/PageCard.vue'

/** 一行入库明细（warehouseId 用于级联与展示，提交时剔除） */
interface InboundItemRow {
  productId?: number
  warehouseId?: number
  locationCode?: string
  quantity: number
}

const supplierName = ref('')
const items = ref<InboundItemRow[]>([])
const submitting = ref(false)

/**
 * 幂等键：本次表单会话的 UUID。
 * 弱网下提交超时后重试时复用同一 requestId，后端不会重复创建入库单；成功后重新生成。
 * 注意：crypto.randomUUID 仅在安全上下文可用（localhost 可以；局域网 IP 访问会缺失），
 * 因此提供降级实现，避免页面在非安全上下文下直接报错。
 */
const genRequestId = (): string => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // 降级：非安全上下文（如 http://192.168.x.x:5173）下的伪 UUID
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
const requestId = ref<string>(genRequestId())

// 基础数据
const products = ref<Product[]>([])
const warehouses = ref<Warehouse[]>([])
const locationCache = ref<Record<number, Location[]>>({})
const locationLoading = ref<Record<number, boolean>>({})

// 明细弹窗（-1 = 新增，>=0 = 编辑对应行）
const dialogVisible = ref(false)
const editingIndex = ref(-1)
const dialogTitle = computed(() => (editingIndex.value === -1 ? '添加入库明细' : '编辑入库明细'))
const draft = ref<InboundItemRow>({ productId: undefined, warehouseId: undefined, locationCode: undefined, quantity: 1 })

// 展示辅助：行内商品/仓库名称
const productLabel = (id?: number) => {
  const p = products.value.find((it) => it.id === id)
  return p ? `${p.name} (${p.sku})` : '-'
}
const warehouseLabel = (id?: number) => {
  const w = warehouses.value.find((it) => it.id === id)
  return w ? w.name : '-'
}

onMounted(async () => {
  try {
    const [p, w] = await Promise.all([getProducts(), getWarehouses()])
    products.value = p.data
    warehouses.value = w.data
  } catch (e: any) {
    ElMessage.error('加载基础数据失败: ' + (e.response?.data?.message || e.message))
  }
})

/** 加载某仓库下的库位（带缓存） */
const loadLocations = (warehouseId: number) => {
  if (locationCache.value[warehouseId]) return
  locationLoading.value[warehouseId] = true
  getLocations(warehouseId)
    .then((res) => {
      locationCache.value[warehouseId] = res.data
    })
    .catch((e: any) => {
      ElMessage.error('加载库位失败: ' + (e.response?.data?.message || e.message))
    })
    .finally(() => {
      locationLoading.value[warehouseId] = false
    })
}

// —— 弹窗表单 ——

const openAdd = () => {
  editingIndex.value = -1
  draft.value = { productId: undefined, warehouseId: undefined, locationCode: undefined, quantity: 1 }
  dialogVisible.value = true
}

const openEdit = (index: number) => {
  editingIndex.value = index
  draft.value = { ...items.value[index] }
  if (draft.value.warehouseId) {
    loadLocations(draft.value.warehouseId)
  }
  dialogVisible.value = true
}

/** 弹窗内选择仓库后重置库位并加载库位列表 */
const handleWarehouseChange = () => {
  draft.value.locationCode = undefined
  if (draft.value.warehouseId) {
    loadLocations(draft.value.warehouseId)
  }
}

const confirmDraft = () => {
  if (!draft.value.productId) {
    ElMessage.warning('请选择商品')
    return
  }
  if (!draft.value.warehouseId) {
    ElMessage.warning('请选择仓库')
    return
  }
  if (!draft.value.locationCode) {
    ElMessage.warning('请选择库位')
    return
  }
  if (!draft.value.quantity || draft.value.quantity < 1) {
    ElMessage.warning('数量必须大于 0')
    return
  }
  if (editingIndex.value === -1) {
    items.value.push({ ...draft.value })
  } else {
    items.value[editingIndex.value] = { ...draft.value }
  }
  dialogVisible.value = false
}

const removeItem = (index: number) => {
  items.value.splice(index, 1)
}

// —— 提交 ——

const handleSubmit = async () => {
  if (!supplierName.value.trim()) {
    ElMessage.warning('请填写供应商名称')
    return
  }
  if (items.value.length === 0) {
    ElMessage.warning('请至少添加一条入库明细')
    return
  }
  for (let i = 0; i < items.value.length; i++) {
    const it = items.value[i]
    if (!it.productId || !it.warehouseId || !it.locationCode || !it.quantity || it.quantity < 1) {
      ElMessage.warning(`第 ${i + 1} 行明细不完整，请检查后重试`)
      return
    }
  }

  submitting.value = true
  try {
    const payload = {
      supplierName: supplierName.value.trim(),
      requestId: requestId.value,
      items: items.value.map<InboundItemRequest>((it) => ({
        productId: it.productId!,
        quantity: it.quantity,
        locationCode: it.locationCode!,
      })),
    }
    const res = await createInboundOrder(payload)
    const orderNo = (res as any)?.data?.orderNo
    ElMessage.success(orderNo ? `入库单创建成功：${orderNo}` : '入库单创建成功')
    // 成功后重置表单、更换幂等键（下一次提交是新的一单）
    supplierName.value = ''
    items.value = []
    requestId.value = genRequestId()
  } catch (e: any) {
    // 提交失败（如弱网超时）：保留 requestId，用户重试不会产生重复单
    ElMessage.error(e.response?.data?.message || '创建入库单失败，请重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <PageCard title="入库管理">
    <!-- 供应商 -->
    <el-form label-width="100px" style="max-width: 640px">
      <el-form-item label="供应商名称" required>
        <el-input
          v-model="supplierName"
          placeholder="请输入供应商名称"
          maxlength="200"
          clearable
          style="width: 320px"
        />
      </el-form-item>
    </el-form>

    <!-- 工具栏 -->
    <div class="table-toolbar">
      <el-button type="primary" @click="openAdd">+ 添加明细</el-button>
      <el-button
        type="success"
        :loading="submitting"
        @click="handleSubmit"
        :disabled="items.length === 0"
      >
        提交入库单
      </el-button>
    </div>

    <!-- 明细表格（展示） -->
    <el-table :data="items" border stripe>
      <el-table-column type="index" label="#" width="55" />
      <el-table-column label="商品" min-width="200">
        <template #default="{ row }">
          {{ productLabel(row.productId) }}
        </template>
      </el-table-column>
      <el-table-column label="仓库" width="150">
        <template #default="{ row }">
          {{ warehouseLabel(row.warehouseId) }}
        </template>
      </el-table-column>
      <el-table-column prop="locationCode" label="库位" width="150">
        <template #default="{ row }">{{ row.locationCode || '-' }}</template>
      </el-table-column>
      <el-table-column prop="quantity" label="数量" width="100" align="center" />
      <el-table-column label="操作" width="140" align="center">
        <template #default="{ $index }">
          <el-button size="small" @click="openEdit($index)">编辑</el-button>
          <el-button size="small" type="danger" @click="removeItem($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty
      v-if="items.length === 0"
      description="请点击「添加明细」按钮添加入库商品"
      style="margin-top: 24px"
    />

    <!-- 添加/编辑明细弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px">
      <el-form label-width="80px">
        <el-form-item label="商品" required>
          <el-select v-model="draft.productId" filterable placeholder="搜索选择商品" style="width: 100%">
            <el-option
              v-for="p in products"
              :key="p.id"
              :label="`${p.name} (${p.sku})`"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库" required>
          <el-select
            v-model="draft.warehouseId"
            placeholder="选择仓库"
            style="width: 100%"
            @change="handleWarehouseChange"
          >
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="库位" required>
          <el-select
            v-model="draft.locationCode"
            placeholder="选择库位"
            style="width: 100%"
            :loading="!!draft.warehouseId && locationLoading[draft.warehouseId]"
            :disabled="!draft.warehouseId"
          >
            <el-option
              v-for="loc in (draft.warehouseId ? locationCache[draft.warehouseId] || [] : [])"
              :key="loc.code"
              :label="loc.code"
              :value="loc.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="draft.quantity" :min="1" :max="999999" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmDraft">确定</el-button>
      </template>
    </el-dialog>
  </PageCard>
</template>
