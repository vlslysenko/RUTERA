import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.');
  const apiProxy = env.VITE_API_PROXY || 'http://localhost:8080';

  return {
    plugins: [
      react(),
      VitePWA({
        registerType: 'autoUpdate',
        includeAssets: ['favicon.svg'],
        manifest: {
          name: 'RUTERA',
          short_name: 'RUTERA',
          description: 'Fleet, drivers, tachograph and invoices for small road carriers',
          lang: 'en',
          start_url: '/',
          scope: '/',
          display: 'standalone',
          orientation: 'any',
          background_color: '#EEF1F5',
          theme_color: '#1F5FBF',
          icons: [
            { src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png' },
            { src: '/pwa-512x512.png', sizes: '512x512', type: 'image/png' },
            { src: '/pwa-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
            { src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' },
          ],
        },
        workbox: {
          // Only the built assets are precached. API calls (/app/...) are never served from the cache.
          globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
          navigateFallback: '/index.html',
          navigateFallbackDenylist: [/^\/app\//, /^\/api\//],
          cleanupOutdatedCaches: true,
          maximumFileSizeToCacheInBytes: 4 * 1024 * 1024,
        },
        devOptions: {
          enabled: false,
        },
      }),
    ],
    resolve: {
      alias: {
        '@': '/src',
      },
    },
    server: {
      port: 5173,
      proxy: {
        // Internal endpoints of the Spring Boot backend (session cookie, CSRF cookie).
        '/app': {
          target: apiProxy,
          changeOrigin: false,
        },
      },
    },
    build: {
      sourcemap: false,
      target: 'es2022',
    },
  };
});
