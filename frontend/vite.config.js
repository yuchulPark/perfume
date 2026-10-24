import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import http from 'node:http';

// Connection describes one transport hop. Let Vite manage its client connection
// independently when the backend closes its own socket (including keep-alive limits).
const proxy = { '/ws/support': { target: 'http://127.0.0.1:8081', ws: true, changeOrigin: false }, '/api': { target: 'http://127.0.0.1:8081', changeOrigin: true,
  agent: new http.Agent({ keepAlive: true }), headers: { Connection: 'keep-alive' },
  configure(proxyServer) {
    proxyServer.on('proxyRes', (response) => { delete response.headers.connection; });
  } } };

export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true, proxy },
});
