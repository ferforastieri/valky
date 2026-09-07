import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

const output = resolve('dist-viewer');
const html = readFileSync(resolve(output, 'index.html'), 'utf8');
test('painel tem build separado da landing e assets servidos pelo backend', () => {
  assert.match(html, /Valkyris · Consulta/);
  assert.match(html, /noindex/);
  assert.doesNotMatch(readFileSync('dist/index.html', 'utf8'), /id="login-form"/);
  const assets = [...html.matchAll(/(?:src|href)="(\/app\/[^"#?]+)"/g)];
  assert.ok(assets.length > 2);
  for (const [, url] of assets) assert.ok(existsSync(resolve(output, url.slice('/app/'.length))), url);
  assert.doesNotMatch(html, /<script(?![^>]*src=)[^>]*>[\s\S]*?\S[\s\S]*?<\/script>/);
});
