import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
const source = ts.transpileModule(readFileSync('viewer/lib/live.ts', 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
function harness(respond) {
  const peers = [], requests = [], messages = [], timers = new Map();
  let timerId = 0;
  class Peer {
    constructor() { this.iceGatheringState = 'complete'; this.connectionState = 'new'; peers.push(this); }
    addTransceiver() {}
    async createOffer() { return { type: 'offer', sdp: 'v=0\r\n' }; }
    async setLocalDescription(offer) { this.localDescription = offer; }
    async setRemoteDescription(answer) { this.answer = answer; }
    close() { this.connectionState = 'closed'; }
  }
  const exports = {};
  vm.runInNewContext(source, {
    exports, require: () => ({ key: encodeURIComponent }),
    window: { isSecureContext: true, location: { origin: 'https://home.test' } },
    RTCPeerConnection: Peer, MediaStream: class { addTrack() {} getTracks() { return []; } }, AbortController, URL,
    setTimeout: (fn, ms) => { const id = ++timerId; timers.set(id, { fn, ms }); return id; }, clearTimeout: id => timers.delete(id),
    fetch: async (url, options) => { requests.push({ url, ...options }); return respond(url, options); },
  });
  const listeners = new Map();
  const video = { videoWidth: 0, srcObject: null, addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name), play: () => Promise.resolve() };
  const stop = exports.live('cam', video, value => messages.push(value));
  return { peers, requests, messages, timers, video, stop, listeners };
}
const answer = (profile = '') => new Response('answer', { status: 201, headers: { Location: `/api/v1/cameras/cam/live/webrtc/whep/session${profile}` } });
test('hidden local ICE candidates still negotiate; closing removes the session', async () => {
  const h = harness(() => answer()); await flush();
  assert.equal(h.requests[0].method, 'POST'); assert.equal(h.peers[0].answer.sdp, 'answer');
  h.stop(); assert.equal(h.requests[1].method, 'DELETE'); assert.equal(h.peers[0].connectionState, 'closed'); assert.equal(h.timers.size, 0);
});
test('unsupported original format retries once using browser profile', async () => {
  const h = harness((url, options) => options.method === 'DELETE' ? new Response(null, { status: 204 }) : url.includes('profile=browser') ? answer('?profile=browser') : new Response('unsupported codec', { status: 400 }));
  await flush(); assert.equal(h.peers.length, 2); assert.equal(h.peers[0].connectionState, 'closed');
  assert.equal(h.requests.filter(r => r.method === 'POST').length, 2); assert.ok(h.requests[1].url.endsWith('?profile=browser'));
  h.stop(); assert.ok(h.requests.at(-1).url.endsWith('?profile=browser'));
});
test('audio alone is not live video; undecodable video retries', async () => {
  const h = harness(url => answer(url.includes('?') ? '?profile=browser' : '')); await flush();
  h.listeners.get('playing')(); assert.ok(!h.messages.at(-1).startsWith('Ao vivo'));
  h.peers[0].connectionState = 'connected'; [...h.timers.values()].find(t => t.ms === 20000).fn(); await flush();
  assert.equal(h.peers.length, 2); h.video.videoWidth = 1280; h.listeners.get('resize')(); assert.ok(h.messages.at(-1).startsWith('Ao vivo')); h.stop();
});
test('HTTP error stays visible and stops timers and peer', async () => {
  const h = harness(() => new Response(null, { status: 401 })); await flush();
  assert.match(h.messages.at(-1), /sessão expirou/); assert.equal(h.timers.size, 0); assert.equal(h.peers[0].connectionState, 'closed'); h.stop();
});
test('network failure does not start an unnecessary transcoder', async () => {
  const h = harness(() => answer()); await flush(); h.peers[0].connectionState = 'failed'; h.peers[0].onconnectionstatechange();
  assert.equal(h.peers.length, 1); assert.match(h.messages.at(-1), /8189/); assert.equal(h.requests.at(-1).method, 'DELETE'); h.stop();
});
test('late response after closing releases its server session', async () => {
  let resolve; const pending = new Promise(r => { resolve = r; });
  const h = harness((_url, options) => options.method === 'DELETE' ? new Response(null, { status: 204 }) : pending); await flush();
  h.stop(); resolve(answer()); await flush(); assert.equal(h.requests.at(-1).method, 'DELETE'); assert.equal(h.messages.length, 1); assert.equal(h.timers.size, 0);
});
