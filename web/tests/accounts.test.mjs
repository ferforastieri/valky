import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
const compiled = ts.transpileModule(readFileSync('viewer/lib/api.ts', 'utf8'), { compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 } }).outputText;
const { API } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));

test('personal login sends username and loads permissions from the server', async () => {
  const original = globalThis.fetch;
  let body;
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/login')) {
      body = JSON.parse(options.body);
      return Response.json({ success: true, data: { admin: false, readOnly: true } });
    }
    assert.equal(url, '/api/v1/viewer-session');
    return Response.json({ success: true, data: { admin: false, viewRules: true, editRules: false } });
  };
  try {
    const api = new API();
    await api.login('miriam', 'member password 123');
    assert.equal(body.username, 'miriam');
    assert.equal(body.password, 'member password 123');
    assert.equal(api.admin, false);
    assert.equal(api.viewRules, true);
    assert.equal(api.editRules, false);
  } finally { globalThis.fetch = original; }
});

test('failed logout retains local session until server revocation succeeds', async () => {
  const original = globalThis.fetch;
  try {
    const api = new API(); api.token = 'cookie-session';
    globalThis.fetch = async () => new Response(null, { status: 403 });
    await assert.rejects(api.logout());
    assert.equal(api.token, 'cookie-session');
    globalThis.fetch = async () => new Response(null, { status: 204 });
    await api.logout();
    assert.equal(api.token, '');
  } finally { globalThis.fetch = original; }
});
