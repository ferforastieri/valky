import { defineCollection, z } from 'astro:content';
import { glob } from 'astro/loaders';

// A documentação vem de docs/*.md do repositório (ver scripts/sync-docs.mjs):
// o site não mantém texto próprio, só apresenta o que está versionado.
export const collections = {
  docs: defineCollection({
    loader: glob({ pattern: '**/*.md', base: './src/content/docs' }),
    schema: z.object({
      title: z.string(),
      order: z.number().default(99),
    }),
  }),
};
