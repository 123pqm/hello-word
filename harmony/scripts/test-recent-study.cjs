// Runs the real ViewModel logic with fake transport/timers, without accessing accounts or a database.
// Does not replace ArkTS compilation or device UI verification.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const sdk = process.env.DEVECO_SDK_HOME;
if (!sdk) throw new Error('Set DEVECO_SDK_HOME to the DevEco SDK directory.');
const ts = require(path.join(sdk, 'default/openharmony/ets/build-tools/ets-loader/node_modules/typescript/lib/typescript.js'));
const source = fs.readFileSync(path.join(__dirname, '../entry/src/main/ets/viewmodels/RecentStudyViewModel.ets'), 'utf8');
const js = ts.transpileModule(source.replace('@Observed', ''), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText;
class ApiError extends Error { constructor(status, message) { super(message); this.status = status; } }
const movie = (id = 1, status = 'completed') => ({ id, status, file_name: `movie-${id}.mp4`, created_at: '2026-10-16T12:00:00' });
function setup() {
  let token = 'session-a', seq = 0;
  const timers = new Map();
  const calls = { latest: 0, status: 0, cover: 0 };
  const service = {
    latest: async () => { calls.latest++; return movie(); },
    status: async id => { calls.status++; return { movie_id: id, status: 'completed', error_message: null, matched_word_count: 3 }; },
    cover: async () => { calls.cover++; return { release() {} }; }
  };
  const context = { exports: {}, Error, Number, Array, AppStorage: { get: () => token },
    require: name => name.includes('HttpClient') ? { ApiError } : { VideoService: service },
    setTimeout: (fn, delay) => { const id = ++seq; timers.set(id, { fn, delay }); return id; },
    clearTimeout: id => timers.delete(id) };
  vm.runInNewContext(js, context);
  return { model: new context.exports.RecentStudyViewModel(), service, calls, timers, token: value => token = value };
}
const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
const tests = [];
const test = (name, run) => tests.push({ name, run });
test('latest record, completed count, cover cached and 30s refresh', async () => {
  const t = setup(); t.model.start(); await settle();
  assert.equal(t.model.recent.id, 1); assert.equal(t.model.matchedCount, 3);
  assert.equal([...t.timers.values()][0].delay, 30000);
  await t.model.refresh(); assert.equal(t.calls.cover, 1); t.model.dispose(); assert.equal(t.timers.size, 0);
});
test('processing refreshes every 5s then transitions to complete', async () => {
  const t = setup(); t.service.status = async id => ({ movie_id: id, status: 'processing', matched_word_count: 0 });
  t.model.start(); await settle(); assert.equal([...t.timers.values()][0].delay, 5000); assert.equal(t.model.matchedCount, -1);
  t.service.status = async id => ({ movie_id: id, status: 'completed', matched_word_count: 0 });
  await t.model.refresh(); assert.equal(t.model.matchedCount, 0); assert.equal([...t.timers.values()][0].delay, 30000);
});
test('empty history renders empty and does not request nonexistent status/cover', async () => {
  const t = setup(); t.service.latest = async () => undefined; t.model.start(); await settle();
  assert.equal(t.model.recent, undefined); assert.equal(t.calls.status, 0); assert.equal(t.calls.cover, 0); assert.equal(t.model.loading, false);
});
test('no session and 401 stop requests and prompt login', async () => {
  const t = setup(); t.token(''); t.model.start(); await settle(); assert.equal(t.calls.latest, 0); assert.equal(t.model.loginRequired, true);
  t.token('expired'); t.service.latest = async () => { throw new ApiError(401, 'expired'); }; t.model.start(); await settle();
  assert.equal(t.model.loginRequired, true); assert.equal(t.timers.size, 0); assert.equal(t.model.recent, undefined);
});
test('leaving while request in flight discards result and does not restart polling', async () => {
  const t = setup(); let resolve; t.service.latest = () => new Promise(r => resolve = r);
  t.model.start(); t.model.stop(); resolve(movie()); await settle();
  assert.equal(t.model.recent, undefined); assert.equal(t.timers.size, 0); assert.equal(t.model.loading, false);
});
test('old user response cannot overwrite new user records', async () => {
  const t = setup(); let resolve; t.service.latest = () => new Promise(r => resolve = r); t.model.start();
  t.token('session-b'); t.service.latest = async () => movie(2); t.model.start(); await settle(); resolve(movie(1)); await settle();
  assert.equal(t.model.recent.id, 2);
});
test('network error stops automatic retry and manual refresh recovers', async () => {
  const t = setup(); t.service.latest = async () => { throw new Error('offline'); }; t.model.start(); await settle();
  assert.equal(t.model.error, 'offline'); assert.equal(t.timers.size, 0); assert.equal(t.model.loading, false);
  t.service.latest = async () => movie(); await t.model.refresh(); assert.equal(t.model.error, ''); assert.equal(t.model.recent.id, 1);
});
test('missing cover does not discard valid history', async () => {
  const t = setup(); t.service.cover = async () => { throw new ApiError(404, 'no cover'); }; t.model.start(); await settle();
  assert.equal(t.model.recent.id, 1); assert.equal(t.model.error, ''); assert.equal(t.model.cover, undefined);
});
(async () => { for (const t of tests) { await t.run(); console.log('PASS', t.name); } console.log(`${tests.length} passed`); })().catch(error => { console.error(error); process.exitCode = 1; });
