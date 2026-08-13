// SPDX-License-Identifier: Apache-2.0
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

// The FI-7 frontend. Built to `dist/`, which the BACKEND serves (see
// `api._mount_frontend`) — one deployable, no second web server, and therefore
// no CORS policy: in production the app and the API share an origin.
//
// In development they do not, so `/v1` is proxied to the backend rather than
// configured as an absolute base URL. That keeps the client's paths identical
// in both modes, which is what makes a dev-only mistake impossible to ship.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const api = env.VITE_API_TARGET || 'http://localhost:7290'

  return {
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    // ⚑ Absolute, and it has to be. The router is in history mode, so the app
    // is served at `/entry/12` as readily as at `/`; a relative base would make
    // that page ask for `/entry/assets/…` and get `index.html` back from the
    // catch-all — a blank screen with a 200 in the network tab.
    base: '/',
    server: {
      port: Number(env.VITE_PORT || 7291),
      proxy: {
        '/v1': { target: api, changeOrigin: true },
        '/healthz': { target: api, changeOrigin: true },
      },
    },
  }
})
