import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Dev server only; ports come from the repo root .env (single source of configuration).
// In Docker, nginx does this proxying instead.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '')
  const backend = `localhost:${env.BACKEND_HOST_PORT || '8081'}`
  return {
    plugins: [react()],
    server: {
      port: Number(env.DEV_WEB_PORT || 5173),
      proxy: {
        '/api': `http://${backend}`,
        '/ws': { target: `ws://${backend}`, ws: true },
      },
    },
  }
})
