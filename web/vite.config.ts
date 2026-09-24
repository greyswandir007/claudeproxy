import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev-режим проксирует /api на бэкенд claudeproxy (порт 8080).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
  build: {
    outDir: 'dist',
  },
})
