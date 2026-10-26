import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import process from 'node:process'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server : {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy : {
      '/api' : process.env.BOOKBUDDY_API_TARGET || 'http://127.0.0.1:8080',
    },
  },
})
