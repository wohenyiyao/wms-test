import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// vitest 配置（选做 B 前端单元测试）
// 当前测试均为纯函数（filters.ts），environment 用 node 即可（无需 jsdom）；
// 若后续补组件级测试，可切换到 happy-dom/jsdom 并补 @vue/test-utils。
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'node',
    include: ['src/**/__tests__/**/*.test.ts'],
  },
})
