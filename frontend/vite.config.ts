import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev the backend runs in Docker on 8081 (see compose.yml); in prod nginx does this proxying.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8081',
      '/ws': { target: 'ws://localhost:8081', ws: true },
    },
  },
})
