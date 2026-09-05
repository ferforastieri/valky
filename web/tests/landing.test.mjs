import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import test from 'node:test';

const pt = await readFile(new URL('../dist/pt-BR/index.html', import.meta.url), 'utf8');
const en = await readFile(new URL('../dist/en/index.html', import.meta.url), 'utf8');
const root = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8');
const ptDocs = await readFile(new URL('../dist/pt-BR/docs/index.html', import.meta.url), 'utf8');
const enDocs = await readFile(new URL('../dist/en/docs/index.html', import.meta.url), 'utf8');
const landingSource = await readFile(new URL('../src/components/Landing.astro', import.meta.url), 'utf8');
const stylesSource = await readFile(new URL('../src/styles/global.css', import.meta.url), 'utf8');

test('publica as duas localizações com idioma correto', () => {
  assert.match(pt, /<html lang="pt-BR"/);
  assert.match(en, /<html lang="en"/);
  assert.match(pt, /curl -fsSL https:\/\/valkyris\.vercel\.app\/install\.sh \| sh/);
  assert.match(root, /valkyris-locale/);
});

test('controles e navegação possuem nomes acessíveis', async () => {
  assert.match(pt, /aria-label="[^"]+"/);
  assert.match(pt, /href="\/en"/);
  assert.match(en, /href="\/pt-BR"/);
  assert.match(pt, /class="language-menu"/);
  assert.match(pt, /href="\/pt-BR"/);
  assert.match(pt, /class="floating-dock"/);
  assert.doesNotMatch(pt, /<header[\s>]/);
  assert.doesNotMatch(pt, /<footer[\s>]/);
  assert.match(pt, /© 2026 Fernando Forastieri · MIT/);
  assert.match(pt, /valkyris-mark\.svg/);
  assert.match(pt, /screenshots\/cameras-light\.png/);
  assert.match(pt, /screenshots\/rules-light\.png/);
  assert.match(pt, /screenshots\/settings-dark\.png/);
  assert.match(pt, /screenshots\/events-light\.png/);
  assert.match(pt, /class="app-carousel"/);
  assert.match(pt, /href="\/pt-BR\/docs"/);
  assert.match(en, /href="\/en\/docs"/);
  assert.doesNotMatch(pt, /class="docs-shell"/);
  assert.match(pt, /Sua privacidade importa\./);
  assert.doesNotMatch(pt, /class="flow section"/);
  assert.doesNotMatch(pt, /class="compatibility section"/);
  assert.doesNotMatch(pt, /class="detect section/);
  assert.doesNotMatch(pt, /Sinais que importam/);
  assert.match(pt, /href="\/favicon\.png\?v=6"/);
  assert.match(pt, /href="\/apple-touch-icon\.png\?v=6"/);
});

test('respeita a preferência por movimento reduzido', async () => {
  const assets = new URL('../dist/_astro/', import.meta.url);
  const files = (await readdir(assets)).filter((file) => file.endsWith('.css'));
  const styles = (await Promise.all(files.map((file) => readFile(new URL(file, assets), 'utf8')))).join('\n');
  assert.match(styles, /prefers-reduced-motion:reduce/);
  assert.match(styles, /\.carousel-track\{position:relative;display:block/);
  assert.match(styles, /\.floating-dock\{width:max-content/);
  assert.match(styles, /\.floating-dock\{position:fixed/);
  assert.match(styles, /\.floating-dock\{[^}]*top:18px;bottom:auto/);
  assert.match(styles, /@media\(max-width:760px\)[^{]*\{[^}]*body\{padding-top:0;padding-bottom:76px\}/);
  assert.match(styles, /\.dock-link span\{display:none\}/);
});

test('sincroniza os prints com o tema escolhido', () => {
  assert.match(pt, /class="theme-screenshot"[^>]+data-light-src="\/screenshots\/overview-light\.png"[^>]+data-dark-src="\/screenshots\/overview-dark\.png"/);
  assert.match(pt, /class="theme-screenshot"[^>]+data-light-src="\/screenshots\/cameras-light\.png"[^>]+data-dark-src="\/screenshots\/cameras-dark\.png"/);
  assert.match(landingSource, /syncThemeScreenshots/);
  assert.match(landingSource, /systemTheme\.addEventListener\('change'/);
});

test('publica metadados sociais e SEO internacional', async () => {
  assert.match(pt, /<meta name="robots" content="index, follow, max-image-preview:large/);
  assert.match(pt, /<meta property="og:image" content="https:\/\/valkyris\.vercel\.app\/og-image\.png"/);
  assert.match(pt, /<meta name="twitter:card" content="summary_large_image"/);
  assert.match(pt, /<link rel="alternate" hreflang="x-default"/);
  assert.match(pt, /application\/ld\+json/);
  assert.match(pt, /SoftwareApplication/);
  assert.match(pt, /monitoramento residencial self-hosted/);
  assert.match(en, /self-hosted home monitoring/);
  assert.match(root, /name="robots" content="noindex, follow"/);
  const robots = await readFile(new URL('../dist/robots.txt', import.meta.url), 'utf8');
  const sitemap = await readFile(new URL('../dist/sitemap-0.xml', import.meta.url), 'utf8');
  assert.match(robots, /Sitemap: https:\/\/valkyris\.vercel\.app\/sitemap-index\.xml/);
  assert.match(sitemap, /https:\/\/valkyris\.vercel\.app\/pt-BR/);
  assert.match(sitemap, /https:\/\/valkyris\.vercel\.app\/en/);
  assert.doesNotMatch(sitemap, /<loc>https:\/\/valkyris\.vercel\.app\/<\/loc>/);
});

test('carrossel avança automaticamente sem controle visual redundante', () => {
  assert.doesNotMatch(pt, /class="carousel-auto"/);
  assert.doesNotMatch(pt, /aria-label="Pausar carrossel"/);
  assert.match(landingSource, /setTimeout\(\(\)=>\{show\(slide\+1\);schedule\(\)\},4800\)/);
  assert.match(landingSource, /prefers-reduced-motion: reduce/);
  assert.match(landingSource, /selected===slide\?slide\+1:selected/);
  assert.match(landingSource, /<button class="app-capture carousel-slide/);
  assert.match(landingSource, /carousel-hit-prev/);
  assert.doesNotMatch(landingSource, /role="button" tabindex="0"/);
  assert.match(stylesSource, /\.carousel-hit\s*\{[^}]*cursor:\s*pointer/s);
  assert.doesNotMatch(stylesSource, /\.carousel-hit(?:-next)?:hover/);
});

test('documentação é uma rota separada, localizada e completa', async () => {
  assert.match(ptDocs, /<html lang="pt-BR"/);
  assert.match(enDocs, /<html lang="en"/);
  assert.match(ptDocs, /Documentação do Valkyris/);
  assert.match(enDocs, /Valkyris documentation/);
  assert.match(ptDocs, /\/api\/v1\/cameras/);
  assert.match(ptDocs, /\/api\/v1\/realtime/);
  assert.match(ptDocs, /href="\/openapi.yaml"/);
  assert.match(ptDocs, /href="\/pt-BR"/);
  assert.match(ptDocs, /href="\/pt-BR#app"/);
  assert.match(ptDocs, /href="\/pt-BR#install"/);
  assert.match(ptDocs, /href="\/pt-BR\/docs" aria-current="page"/);
  assert.match(ptDocs, /© 2026 Fernando Forastieri · MIT/);
  assert.match(ptDocs, /hreflang="en" href="https:\/\/valkyris\.vercel\.app\/en\/docs"/);
  assert.match(enDocs, /hreflang="pt-BR" href="https:\/\/valkyris\.vercel\.app\/pt-BR\/docs"/);
  const sitemap = await readFile(new URL('../dist/sitemap-0.xml', import.meta.url), 'utf8');
  assert.match(sitemap, /https:\/\/valkyris\.vercel\.app\/pt-BR\/docs/);
  assert.match(sitemap, /https:\/\/valkyris\.vercel\.app\/en\/docs/);
  assert.match(stylesSource, /@media \(max-width: 620px\)[\s\S]*\.endpoint-list article\s*\{[\s\S]*grid-template-columns: minmax\(0, auto\) minmax\(0, 1fr\)/);
});

test('artefatos públicos acompanham a documentação', async () => {
  const installer = await readFile(new URL('../dist/install.sh', import.meta.url), 'utf8');
  const openapi = await readFile(new URL('../dist/openapi.yaml', import.meta.url), 'utf8');
  const compose = await readFile(new URL('../../compose.yaml', import.meta.url), 'utf8');
  const favicon = await readFile(new URL('../dist/favicon.png', import.meta.url));
  assert.match(installer, /^#!\/bin\/sh/);
  assert.doesNotMatch(installer, /directory-backup/);
  assert.doesNotMatch(installer, /--force-recreate mediamtx/);
  assert.match(installer, /corrija-o manualmente/);
  assert.match(compose, /create_host_path: false/);
  assert.match(openapi, /^openapi: 3\.1\.0/);
  assert.ok(favicon.length > 1_000);
});
