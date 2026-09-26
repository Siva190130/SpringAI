import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [react(), tailwindcss()],
    server: {
      proxy: {
        '/api': {
          target: env.BACKEND_URL || 'http://127.0.0.1:8080',
          changeOrigin: true,
          // Only the development server sees this key; never expose it as VITE_*.
          headers: env.CHAT_API_KEY ? { 'X-API-Key': env.CHAT_API_KEY } : {},
        },
      },
    },
    test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], restoreMocks: true },
  };
});
