<script setup lang="ts">
/**
 * 商品管理页 — 参考实现
 *
 * 展示了：
 * - 列表 + 搜索
 * - 新增 / 编辑弹窗
 * - 删除确认（有关联数据时二次确认后 force 删除）
 * - 分页（前端分页，简单示例）
 *
 * 任务 3 已修复：
 * - 编辑/新增后保持当前页码，不再跳回第 1 页
 * - 删除有库存/历史入库记录的商品：提示 → 二次确认 → 后端事务内级联清理
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getProducts, createProduct, updateProduct, deleteProduct, type Product } from '@/api'
import PageCard from '@/components/PageCard.vue'

const products = ref<Product[]>([])
const keyword = ref('')
const loading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('新增商品')
const form = ref({ id: 0, name: '', sku: '', unit: '个' })
const currentPage = ref(1)
const pageSize = ref(10)

// 搜索
const loadProducts = async () => {
  loading.value = true
  try {
    const res = await getProducts(keyword.value || undefined)
    products.value = res.data
  } catch (e: any) {
    ElMessage.error('加载失败: ' + (e.response?.data?.message || e.message))
  } finally {
    loading.value = false
  }
}

// 分页后的数据
const pagedProducts = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return products.value.slice(start, start + pageSize.value)
})

onMounted(loadProducts)

// 新增
const handleAdd = () => {
  dialogTitle.value = '新增商品'
  form.value = { id: 0, name: '', sku: '', unit: '个' }
  dialogVisible.value = true
}

// 编辑
const handleEdit = (product: Product) => {
  dialogTitle.value = '编辑商品'
  form.value = { id: product.id, name: product.name, sku: product.sku, unit: product.unit }
  dialogVisible.value = true
}

// 提交
const handleSubmit = async () => {
  try {
    if (form.value.id) {
      await updateProduct(form.value.id, { name: form.value.name, unit: form.value.unit })
      ElMessage.success('更新成功')
    } else {
      await createProduct({ name: form.value.name, sku: form.value.sku, unit: form.value.unit })
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    // 修复：编辑/新增后保持当前页码，不再重置回第 1 页（预埋 Bug）
    await loadProducts()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

// 删除：默认有关联库存/历史入库记录时后端返回 400 提示 → 二次确认后 force 重试（任务 3 修复）
const handleDelete = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定删除该商品吗？', '确认删除', { type: 'warning' })
    await deleteProduct(id)
    ElMessage.success('删除成功')
    await afterDeleteReload()
  } catch (e: any) {
    const msg = e.response?.data?.message || ''
    if (e.response?.status === 400 && msg) {
      // 有关联数据：提示并二次确认，确认后强制删除（后端事务内级联清理）
      try {
        await ElMessageBox.confirm(msg + '，是否继续删除？', '存在关联数据', {
          type: 'warning',
          confirmButtonText: '确认删除',
          cancelButtonText: '取消',
        })
        await deleteProduct(id, true)
        ElMessage.success('删除成功（已清理关联数据）')
        await afterDeleteReload()
      } catch {
        // 用户取消二次确认
      }
    }
    // 用户取消首次确认 / 其他错误：静默（确认框取消不报错）
  }
}

/** 删除后重新加载并处理空页回退：若当前页已无数据则回退一页 */
const afterDeleteReload = async () => {
  await loadProducts()
  const maxPage = Math.max(1, Math.ceil(products.value.length / pageSize.value))
  if (currentPage.value > maxPage) {
    currentPage.value = maxPage
    await loadProducts()
  }
}
</script>

<template>
  <PageCard title="商品管理">
    <!-- 搜索栏 + 操作按钮 -->
    <div class="table-toolbar">
      <el-input v-model="keyword" placeholder="搜索商品名称/SKU..." style="width: 300px" clearable
        @keyup.enter="loadProducts" @clear="loadProducts" />
      <el-button type="primary" @click="loadProducts">搜索</el-button>
      <el-button type="success" @click="handleAdd">新增商品</el-button>
    </div>

    <!-- 表格 -->
    <el-table :data="pagedProducts" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="商品名称" />
      <el-table-column prop="sku" label="SKU" width="150" />
      <el-table-column prop="unit" label="单位" width="80" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" @click="handleEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="table-pagination">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="products.length"
        layout="total, prev, pager, next"
      />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="商品名称">
          <el-input v-model="form.name" maxlength="200" />
        </el-form-item>
        <el-form-item label="SKU" v-if="!form.id">
          <el-input v-model="form.sku" maxlength="50" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="form.unit" maxlength="20" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </PageCard>
</template>
