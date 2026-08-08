import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: true, // 监听 0.0.0.0，允许局域网访问
    port: 5173,
    proxy: {
      // 后端地址：默认本地 8080；直连远程服务时通过 SSH 隧道转发，用
      // 环境变量指定，例如 EVOTRACE_PROXY_TARGET=http://localhost:18080
      '/api': { target: process.env.EVOTRACE_PROXY_TARGET ?? 'http://localhost:8080', changeOrigin: true },
      '/open-api': { target: process.env.EVOTRACE_PROXY_TARGET ?? 'http://localhost:8080', changeOrigin: true }
    }
  }
})
