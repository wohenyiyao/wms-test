<script setup lang="ts">
/**
 * ============================================
 *  入库管理页 — 任务1（已实现，交互迭代）
 * ============================================
 *
 * 功能：
 * 1. 供应商名称
 * 2. 入库明细在「表格内直接添加」：点「+ 添加明细」在表格末尾追加一行，
 *    单元格直接变为下拉/输入控件，填完点行内「保存」即固化；已确认行可编辑/删除
 * 3. 草稿持久化：供应商名 + 已确认明细 + 正在填的编辑行 存 localStorage，
 *    跳转/刷新页面不丢失；进入页面自动恢复并提示；提交成功后清除
 * 4. 提交（调用 createInboundOrder API，携带 requestId 幂等键）
 */
import { ref, computed, watch, onMounted } from 'vue'
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

/** 行内编辑态：null=无编辑行，-1=新增，>=0=编辑对应行 */
const editingIndex = ref<number | null>(null)
const draft = ref<InboundItemRow>({ productId: undefined, warehouseId: undefined, locationCode: undefined, quantity: 1 })

/** 编辑行占位标记（表格数据里用它代表"正在编辑的行"） */
const DRAFT_MARKER = { __draft: true } as const

/** 表格渲染数据：编辑态时把被编辑行替换为标记行（新增则在末尾追加） */
const tableData = computed<Array<InboundItemRow | typeof DRAFT_MARKER>>(() => {
  if (editingIndex.value === null) return items.value
  const list: Array<InboundItemRow | typeof DRAFT_MARKER> = items.value.map((r, i) =>
    i === editingIndex.value ? DRAFT_MARKER : r
  )
  if (editingIndex.value === -1) list.push(DRAFT_MARKER)
  return list
})
const isDraftRow = (row: any): boolean => row === DRAFT_MARKER

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
  restoreDraft()
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

// —— 草稿持久化（localStorage） ——

const DRAFT_KEY = 'wms.inbound.draft'

/** 把持久化读出的行规整为合法结构（JSON 会丢弃 undefined 字段） */
const normalizeRow = (r: any): InboundItemRow => ({
  productId: typeof r?.productId === 'number' ? r.productId : undefined,
  warehouseId: typeof r?.warehouseId === 'number' ? r.warehouseId : undefined,
  locationCode: typeof r?.locationCode === 'string' && r.locationCode ? r.locationCode : undefined,
  quantity: typeof r?.quantity === 'number' && r.quantity > 0 ? r.quantity : 1,
})

/** 内容变化即保存；表单已清空则移除（提交成功后自动清理） */
const saveDraft = () => {
  const hasContent =
    supplierName.value.trim() !== '' || items.value.length > 0 || editingIndex.value !== null
  if (!hasContent) {
    localStorage.removeItem(DRAFT_KEY)
    return
  }
  localStorage.setItem(
    DRAFT_KEY,
    JSON.stringify({
      supplierName: supplierName.value,
      items: items.value,
      editingIndex: editingIndex.value,
      draft: draft.value,
      savedAt: Date.now(),
    })
  )
}

/** 页面加载时恢复上次未提交的草稿 */
const restoreDraft = () => {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (!raw) return
    const data = JSON.parse(raw)
    if (!data || typeof data !== 'object') return
    if (typeof data.supplierName === 'string') supplierName.value = data.supplierName
    if (Array.isArray(data.items)) items.value = data.items.map(normalizeRow)
    if (
      typeof data.editingIndex === 'number' &&
      (data.editingIndex === -1 || data.editingIndex < items.value.length)
    ) {
      editingIndex.value = data.editingIndex
      draft.value = normalizeRow(data.draft)
      if (draft.value.warehouseId) loadLocations(draft.value.warehouseId)
    }
    // 补加载已确认行涉及的库位（后续编辑时选项可用）
    items.value.forEach((it) => {
      if (it.warehouseId) loadLocations(it.warehouseId)
    })
    if (supplierName.value || items.value.length > 0 || editingIndex.value !== null) {
      ElMessage.info('已恢复上次未提交的草稿')
    }
  } catch {
    localStorage.removeItem(DRAFT_KEY)
  }
}

watch([supplierName, items, editingIndex, draft], saveDraft, { deep: true })

// —— 行内编辑 ——

const openAdd = () => {
  if (editingIndex.value !== null) {
    ElMessage.warning('请先保存或取消当前编辑中的行')
    return
  }
  draft.value = { productId: undefined, warehouseId: undefined, locationCode: undefined, quantity: 1 }
  editingIndex.value = -1
}

const openEdit = (index: number) => {
  if (editingIndex.value !== null) {
    ElMessage.warning('请先保存或取消当前编辑中的行')
    return
  }
  draft.value = { ...items.value[index] }
  if (draft.value.warehouseId) {
    loadLocations(draft.value.warehouseId)
  }
  editingIndex.value = index
}

const cancelEdit = () => {
  editingIndex.value = null
  draft.value = { productId: undefined, warehouseId: undefined, locationCode: undefined, quantity: 1 }
}

/** 选择仓库后重置库位并加载库位列表 */
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
  } else if (editingIndex.value !== null) {
    items.value[editingIndex.value] = { ...draft.value }
  }
  cancelEdit()
}

const removeItem = (index: number) => {
  items.value.splice(index, 1)
  // 删除的是编辑行之前的行时，编辑行下标前移
  if (editingIndex.value !== null && editingIndex.value > index) {
    editingIndex.value -= 1
  }
}

// —— 提交 ——

const handleSubmit = async () => {
  if (editingIndex.value !== null) {
    ElMessage.warning('请先保存或取消当前编辑中的行')
    return
  }
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
    // 成功后重置表单、更换幂等键（下一次提交是新的一单）；watch 会自动清除本地草稿
    supplierName.value = ''
    items.value = []
    cancelEdit()
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
      <el-button type="primary" :disabled="editingIndex !== null" @click="openAdd">+ 添加明细</el-button>
      <el-button
        type="success"
        :loading="submitting"
        @click="handleSubmit"
        :disabled="items.length === 0"
      >
        提交入库单
      </el-button>
    </div>

    <!-- 明细表格：普通行展示，编辑行直接内嵌控件 -->
    <el-table :data="tableData" border stripe>
      <el-table-column type="index" label="#" width="55" />
      <el-table-column label="商品" min-width="200">
        <template #default="{ row }">
          <el-select
            v-if="isDraftRow(row)"
            v-model="draft.productId"
            filterable
            placeholder="搜索选择商品"
            style="width: 100%"
          >
            <el-option
              v-for="p in products"
              :key="p.id"
              :label="`${p.name} (${p.sku})`"
              :value="p.id"
            />
          </el-select>
          <span v-else>{{ productLabel(row.productId) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="仓库" width="150">
        <template #default="{ row }">
          <el-select
            v-if="isDraftRow(row)"
            v-model="draft.warehouseId"
            placeholder="选择仓库"
            style="width: 100%"
            @change="handleWarehouseChange"
          >
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
          <span v-else>{{ warehouseLabel(row.warehouseId) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="库位" width="150">
        <template #default="{ row }">
          <el-select
            v-if="isDraftRow(row)"
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
          <span v-else>{{ row.locationCode || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="130" align="center">
        <template #default="{ row }">
          <el-input-number
            v-if="isDraftRow(row)"
            v-model="draft.quantity"
            :min="1"
            :max="999999"
            style="width: 100%"
          />
          <span v-else>{{ row.quantity }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="center">
        <template #default="{ row, $index }">
          <template v-if="isDraftRow(row)">
            <el-button size="small" type="primary" @click="confirmDraft">保存</el-button>
            <el-button size="small" @click="cancelEdit">取消</el-button>
          </template>
          <template v-else>
            <el-button size="small" @click="openEdit($index)">编辑</el-button>
            <el-button size="small" type="danger" @click="removeItem($index)">删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-empty
      v-if="items.length === 0 && editingIndex === null"
      description="请点击「+ 添加明细」在表格中直接填写入库商品"
      style="margin-top: 24px"
    />
  </PageCard>
</template>
