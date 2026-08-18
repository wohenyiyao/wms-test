<script setup lang="ts">
/**
 * 统一页面容器：白色卡片 + 标题 + 内容区
 * 三个页面（商品/库存/入库）共用，保证风格一致。
 */
defineProps<{ title: string }>()
</script>

<template>
  <div class="page-card">
    <h3 class="page-card__title">{{ title }}</h3>
    <div class="page-card__body">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.page-card {
  background: #fff;
  border-radius: 6px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}

.page-card__title {
  margin: 0 0 16px;
  padding-bottom: 12px;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  border-bottom: 1px solid #ebeef5;
}

.page-card__body :deep(.table-toolbar) {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  align-items: center;
  flex-wrap: wrap;
}

.page-card__body :deep(.table-pagination) {
  margin-top: 16px;
  text-align: right;
}
</style>
