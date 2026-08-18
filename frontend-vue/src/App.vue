<script setup lang="ts">
</script>

<template>
  <el-container style="min-height: 100vh">
    <el-header style="background: #409eff; color: white; display: flex; align-items: center; padding: 0 20px">
      <h2 style="margin: 0"> WMS 仓储管理系统</h2>
      <el-menu
        mode="horizontal"
        :default-active="$route.path"
        router
        style="margin-left: 40px; border-bottom: none; flex: 1"
        background-color="#409eff"
        text-color="#fff"
        active-text-color="#ffd04b"
      >
        <el-menu-item index="/products">商品管理</el-menu-item>
        <el-menu-item index="/inventory">库存查询</el-menu-item>
        <el-menu-item index="/inbound">入库管理</el-menu-item>
      </el-menu>
    </el-header>

    <el-main style="padding: 20px; background: #f5f7fa">
      <router-view />
    </el-main>
  </el-container>
</template>

<style>
body {
  margin: 0;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', Arial, sans-serif;
}
</style>
