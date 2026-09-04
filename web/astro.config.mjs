import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

export default defineConfig({
  site: 'https://valkyris.vercel.app',
  output: 'static',
  trailingSlash: 'never',
  build: { format: 'directory' },
  integrations: [
    sitemap({
      filter: (page) => page !== 'https://valkyris.vercel.app/',
      i18n: {
        defaultLocale: 'pt-BR',
        locales: {
          'pt-BR': 'pt-BR',
          en: 'en',
        },
      },
    }),
  ],
});
