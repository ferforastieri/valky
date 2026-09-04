import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import test from 'node:test';

const pt = await readFile(new URL('../dist/pt-BR/index.html', import.meta.url), 'utf8');
const en = await readFile(new URL('../dist/en/index.html', import.meta.url), 'utf8');
const root = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8');
const landingSource = await readFile(new URL('../src/components/Landing.astro', import.meta.url), 'utf8');

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

test('carrossel avança automaticamente sem controle visual redundante', () => {
  assert.doesNotMatch(pt, /class="carousel-auto"/);
  assert.doesNotMatch(pt, /aria-label="Pausar carrossel"/);
  assert.match(landingSource, /setTimeout\(\(\)=>\{show\(slide\+1\);schedule\(\)\},4800\)/);
  assert.match(landingSource, /prefers-reduced-motion: reduce/);
});

test('artefatos públicos acompanham a documentação', async () => {
  const installer = await readFile(new URL('../dist/install.sh', import.meta.url), 'utf8');
  const openapi = await readFile(new URL('../dist/openapi.yaml', import.meta.url), 'utf8');
  const compose = await readFile(new URL('../../compose.yaml', import.meta.url), 'utf8');
  const favicon = await readFile(new URL('../dist/favicon.png', import.meta.url));
  assert.match(installer, /^#!\/bin\/sh/);
  assert.match(installer, /directory-backup/);
  assert.match(installer, /--force-recreate mediamtx/);
  assert.match(compose, /create_host_path: false/);
  assert.match(openapi, /^openapi: 3\.1\.0/);
  assert.ok(favicon.length > 1_000);
});
