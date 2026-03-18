import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE 流式传输：必须禁用 http-proxy 的响应缓冲，否则 SSE 事件会被攒在一起最后才发送
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            // 移除 Accept-Encoding，防止后端返回 gzip 压缩的 SSE 响应（压缩会导致缓冲）
            proxyReq.removeHeader('Accept-Encoding')
          })
          proxy.on('proxyRes', (proxyRes) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache'
              delete proxyRes.headers['content-length']
            }
          })
        }
      }
    }
  }
})
