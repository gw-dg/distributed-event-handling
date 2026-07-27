import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/tasks': 'http://localhost:8080',
      '/dlq': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    }
  }
})
