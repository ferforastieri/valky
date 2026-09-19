import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
const output = ts.transpileModule(readFileSync('viewer/lib/location-history.ts','utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const module = { exports: {} };
vm.runInNewContext(output, { exports: module.exports });
const { historySegments, validHistoryPoint } = module.exports;
const point = (id, at, last = '') => ({ id, latitude: -23.55, longitude: -46.63, occurredAt: `2026-09-19T${at}:00Z`, lastSeenAt: last ? `2026-09-19T${last}:00Z` : '', accuracy: 10 });
test('route is chronological and respects dwell time and gaps without mutating timeline order', () => {
 const points = [point('d','16:10'),point('c','16:00'),point('b','12:10'),point('a','10:00','12:00')];
 assert.equal(JSON.stringify(historySegments(points).map(s => s.map(p => p.id))), JSON.stringify([['a','b'],['c','d']]));
 assert.equal(points[0].id, 'd');
});
test('invalid coordinates and singleton histories produce no invented route', () => {
 assert.equal(validHistoryPoint({...point('a','10:00'),latitude:91}),false);
 assert.equal(validHistoryPoint({...point('a','10:00'),longitude:NaN}),false);
 assert.equal(historySegments([point('a','10:00')]).length,0);
});
