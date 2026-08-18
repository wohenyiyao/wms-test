<script setup lang="ts">
/**
 * 商品管理页 — 参考实现
 *
 * 展示了：
 * - 列表 + 搜索
 * - 新增 / 编辑弹窗
 * - 删除确认
 * - 分页（前端分页，简单示例）
 *
 * ️ BUG 预埋点：编辑后返回列表时页码会重置为第1页
 *   候选人需要在任务3中修复此问题
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
    // ️ BUG: 编辑后不保留当前页码
    currentPage.value = 1
    await loadProducts()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

// 删除
const handleDelete = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定删除该商品吗？', '确认删除', { type: 'warning' })
    await deleteProduct(id)
    ElMessage.success('删除成功')
    await loadProducts()
  } catch {
    // 取消
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
