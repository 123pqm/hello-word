// Tests real service and page state logic with mocked transport; no account or database writes.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require(path.join(process.env.DEVECO_SDK_HOME, 'default/openharmony/ets/build-tools/ets-loader/node_modules/typescript/lib/typescript.js'));
const root = path.join(__dirname, '../entry/src/main/ets');
class ApiError extends Error { constructor(status, message) { super(message); this.status = status; } }
function compile(source, deps, extra = {}) {
  const context = { exports: {}, Error, Number, Array, Date, encodeURIComponent,
    require: name => deps[name], ...extra };
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020
  }}).outputText, context);
  return context.exports;
}
const word = (start = 1) => ({ word: 'example', meaning: '例子', start_time: start, end_time: start + 2, sentence_text: null });
const tests = [];
const test = (name, fn) => tests.push({ name, fn });
const settle = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
function service() {
  const calls = [];
  const http = { getJson: async (url, token) => { calls.push([url, token]); return JSON.stringify(http.response); },
    postJson: async (url, body, token) => { calls.push([url, body, token]); return JSON.stringify(http.response); }, response: { data: { movie_id: 7, words: [word(), word(5)] } } };
  const { FlashcardService } = compile(fs.readFileSync(path.join(root, 'service/FlashcardService.ets'), 'utf8'), {
    '../common/HttpClient': { HttpClient: http, ApiError }, '../common/AppConfig': { AppConfig: { BASE_URL: 'http://test' } }
  });
  return { api: FlashcardService, http, calls };
}
test('correct movie and token; repeated words and null sentences preserved', async () => {
  const t = service(); const words = await t.api.words(7, 'session');
  assert.deepEqual(t.calls, [['/video/7/words', 'session']]); assert.equal(words.length, 2); assert.equal(words[0].sentence_text, null);
});
test('rejects different movie response and malformed timestamps', async () => {
  const t = service(); await assert.rejects(t.api.words(8, 'session'), /响应格式/);
  t.http.response.data.words[0].start_time = -1; await assert.rejects(t.api.words(7, 'session'), /词条格式/);
});
test('empty words valid; invalid route makes no request', async () => {
  const t = service(); t.http.response.data.words = []; assert.equal((await t.api.words(7, 's')).length, 0);
  await assert.rejects(t.api.words(0, 's')); assert.equal(t.calls.length, 1);
});
test('playback ticket is encoded and expired tickets rejected', async () => {
  const t = service(); t.http.response = { data: { ticket: 'a+b/c', expires_at: Date.now() / 1000 + 60 } };
  assert.equal(await t.api.playback(7, 's'), 'http://test/video/7/stream?ticket=a%2Bb%2Fc');
  t.http.response.data.expires_at = 1; await assert.rejects(t.api.playback(7, 's'));
});
function page() {
  let source = fs.readFileSync(path.join(root, 'pages/word_flashcard.ets'), 'utf8').split('  @Builder')[0] + '\n}';
  source = source.replace('@Entry', '').replace('@Component', '').replace('struct WordFlashcard', 'export class WordFlashcard')
    .replace(/@State /g, '').replace(/@StorageLink\('access_token'\) @Watch\('sessionChanged'\) /g, '');
  const timers = new Map(); let seq = 0;
  const api = { words: async () => [word(), word(5)], playback: async () => 'http://video' };
  const status = { status: async () => ({ status: 'processing' }) };
  const { WordFlashcard } = compile(source, {
    '@kit.ArkUI': { router: { getParams: () => ({ movie_id: 7, movie_name: 'movie' }) } },
    '../common/HttpClient': { ApiError }, '../service/FlashcardService': { FlashcardService: api },
    '../service/VideoService': { VideoService: status },
    '../service/FavoriteService': { FavoriteService: { getStatus: async () => false } }
  }, { VideoController: class { pause() {} start() { this.started = true; } setCurrentTime(time) { this.target = time; } }, Scroller: class { scrollEdge() {} }, Edge: { Top: 0 }, SeekMode: { Accurate: 0 }, setTimeout: fn => { timers.set(++seq, fn); return seq; }, clearTimeout: id => timers.delete(id) });
  const model = new WordFlashcard(); model.getUIContext = () => ({ getHostContext: () => undefined }); model.token = 'session';
  return { model, api, status, timers };
}
test('route loads cards; navigation boundaries and reveal reset', async () => {
  const t = page(); t.model.aboutToAppear(); await settle(); assert.equal(t.model.words.length, 2);
  t.model.move(-1); assert.equal(t.model.index, 0); t.model.revealed = true; t.model.move(1);
  assert.equal(t.model.index, 1); assert.equal(t.model.revealed, false); t.model.move(1); assert.equal(t.model.index, 1);
});
test('409 polls until ready; leaving stops timers', async () => {
  const t = page(); t.api.words = async () => { throw new ApiError(409, 'pending'); };
  t.model.aboutToAppear(); await settle(); assert.equal(t.model.waiting, true); assert.equal(t.timers.size, 1);
  t.api.words = async () => [word()]; await t.model.load(); assert.equal(t.model.words.length, 1); t.model.prepared(100); assert.equal(t.timers.size, 0);
  t.model.onPageHide(); assert.equal(t.model.videoUrl, '');
});
test('late result after leaving is ignored', async () => {
  const t = page(); let resolve; t.api.words = () => new Promise(r => resolve = r);
  t.model.aboutToAppear(); t.model.onPageHide(); resolve([word()]); await settle(); assert.equal(t.model.words.length, 0);
});
test('401, 404, failed analysis, empty data and retry have explicit states', async () => {
  for (const code of [401, 404, 500]) {
    const t = page(); t.api.words = async () => { throw new ApiError(code, 'network'); };
    t.model.aboutToAppear(); await settle(); assert.ok(t.model.error); assert.equal(t.model.loginRequired, code === 401);
    t.api.words = async () => []; await t.model.load(); assert.equal(t.model.error, ''); assert.equal(t.model.words.length, 0);
  }
  const t = page(); t.api.words = async () => { throw new ApiError(409, 'pending'); };
  t.status.status = async () => ({ status: 'failed', error_message: '分析失败' });
  t.model.aboutToAppear(); await settle(); assert.equal(t.model.error, '分析失败'); assert.equal(t.timers.size, 0);
});
test('seek waits for matching completion; stale progress cannot prematurely stop clip', async () => {
  const t = page(); t.model.aboutToAppear(); await settle(); t.model.prepared(100);
  t.model.playSentence(); assert.equal(t.model.controller.target, 1); assert.equal(t.model.controller.started, undefined);
  t.model.updateTime(90); assert.equal(t.model.clipEnd, 3);
  t.model.seeked(90); assert.equal(t.model.controller.started, undefined);
  t.model.seeked(1); assert.equal(t.model.controller.started, true);
  t.model.updateTime(3); assert.equal(t.model.clipEnd, -1);
});
test('click before prepared queues playback; changing card cancels queued jump', async () => {
  const t = page(); t.model.aboutToAppear(); await settle(); t.model.playSentence();
  assert.equal(t.model.pendingPlay, true); t.model.prepared(100); assert.equal(t.model.controller.target, 1);
  t.model.videoReady = false; t.model.playSentence(); t.model.move(1); assert.equal(t.model.pendingPlay, false);
});
test('compatibility conversion retries and stops on leave', async () => {
  const t = page(); t.api.playback = async () => { throw new ApiError(202, 'preparing'); };
  t.model.aboutToAppear(); await settle(); assert.equal(t.timers.size, 1);
  assert.equal(t.model.videoUrl, ''); t.model.onPageHide(); assert.equal(t.timers.size, 0);
});
test('preparing response is not mistaken for an invalid playback credential', async () => {
  const t = service(); t.http.response = { data: { status: 'preparing' } };
  await assert.rejects(t.api.playback(7, 's'), e => e.status === 202);
});
test('both directions work even when player pause throws', async () => {
  const t = page(); t.model.aboutToAppear(); await settle();
  t.model.videoReady = true; t.model.controller.pause = () => { throw new Error('player unavailable'); };
  t.model.move(1); assert.equal(t.model.index, 1);
  t.model.revealed = true; t.model.sentenceRevealed = true; t.model.move(-1);
  assert.equal(t.model.index, 0); assert.equal(t.model.revealed, false); assert.equal(t.model.sentenceRevealed, t.model.defaultSentenceRevealed);
});
test('manual pause preserves sentence end; completed playback replays from start', async () => {
  const t = page(); t.model.aboutToAppear(); await settle(); t.model.prepared(100);
  t.model.playSentence(); t.model.seeked(1); t.model.playing = true;
  t.model.togglePlayback(); assert.equal(t.model.clipEnd, 3);
  t.model.togglePlayback(); t.model.updateTime(3);
  assert.equal(t.model.clipCompleted, true); assert.equal(t.model.playing, false);
  t.model.togglePlayback(); assert.equal(t.model.controller.target, 1);
  assert.equal(t.model.clipEnd, 3); assert.equal(t.model.clipCompleted, false);
});
test('switch cancels pending seek and uses new sentence range', async () => {
  const t = page(); t.model.aboutToAppear(); await settle(); t.model.prepared(100);
  t.model.playSentence(); t.model.move(1); t.model.seeked(1);
  assert.equal(t.model.controller.started, undefined);
  t.model.playSentence(); assert.equal(t.model.controller.target, 5);
  assert.equal(t.model.clipEnd, 7); t.model.seeked(1);
  assert.equal(t.model.controller.started, undefined); t.model.seeked(5);
  t.model.updateTime(6.9); assert.equal(t.model.clipEnd, 7);
  t.model.updateTime(7); assert.equal(t.model.clipCompleted, true);
});
test('seek exception clears pending state and final video frame completes sentence', async () => {
  const t = page(); t.model.aboutToAppear(); await settle(); t.model.prepared(3);
  t.model.playSentence(); t.model.seeked(1); t.model.videoFinished();
  assert.equal(t.model.clipCompleted, true);
  t.model.controller.setCurrentTime = () => { throw new Error('seek failure'); };
  t.model.playSentence(); assert.equal(t.model.seekTarget, -1); assert.equal(t.model.clipEnd, -1);
  assert.equal(t.model.seekTimer, -1);
});
test('rounded seek completion starts fractional sentence and clears timeout', async () => {
  const t = page(); t.model.aboutToAppear(); await settle(); t.model.prepared(100);
  t.model.words = [word(12.78)]; t.model.playSentence(); t.model.seeked(12);
  assert.equal(t.model.controller.started, true); assert.equal(t.model.seekTimer, -1);
  assert.equal(t.model.clipEnd, 14.78); t.model.updateTime(15);
  assert.equal(t.model.clipCompleted, true);
});
test('progress callback can confirm seek when seeked is missing', async () => {
  const t = page(); t.model.aboutToAppear(); await settle(); t.model.prepared(100);
  t.model.words = [word(12.78)]; t.model.playSentence(); t.model.updateTime(80);
  assert.equal(t.model.controller.started, undefined); t.model.updateTime(13);
  assert.equal(t.model.controller.started, true); assert.equal(t.model.seekTimer, -1);
  assert.equal(t.model.clipEnd, 14.78);
});
test('correction validates before saving and updates only selected card', async () => {
  const t = page(); t.model.aboutToAppear(); await settle(); t.model.prepared(100);
  t.model.words = [{ ...word(), id: 11 }, { ...word(5), id: 12 }];
  let calls = 0;
  t.api.correct = async (movie, id, data) => { calls++; return { ...word(), id, ...data }; };
  t.model.openCorrection(); t.model.correctionSentence = 'New sentence.';
  for (const [start, end] of [['-1','3'],['3','2'],['3','3'],['1.001','3'],['0','101'],['','2']]) {
    t.model.correctionStart = start; t.model.correctionEnd = end;
    await t.model.saveCorrection(); assert.ok(t.model.correctionError);
  }
  assert.equal(calls, 0);
  t.model.correctionStart = '0'; t.model.correctionEnd = '2.35';
  await t.model.saveCorrection(); assert.equal(calls, 1);
  assert.equal(t.model.words[0].sentence_text, 'New sentence.');
  assert.equal(t.model.words[0].end_time, 2.35); assert.equal(t.model.words[1].start_time, 5);
  assert.equal(t.model.correctionVisible, false);
});
test('failed correction keeps draft and does not mutate card', async () => {
  const t = page(); t.model.aboutToAppear(); await settle();
  t.model.words = [{ ...word(), id: 11 }]; t.model.openCorrection();
  t.model.correctionSentence = 'Edited sentence.';
  t.api.correct = async () => { throw new Error('offline'); };
  await t.model.saveCorrection(); assert.equal(t.model.correctionError, 'offline');
  assert.equal(t.model.correctionVisible, true); assert.equal(t.model.correctionSaving, false);
  assert.equal(t.model.words[0].sentence_text, null);
});
test('correction uses compatible POST endpoint and JSON body', async () => {
  const t = service(); const data = { start_time: 7.54, end_time: 8.54, sentence_text: "Maybe he's going to propose." };
  t.http.response = { ...word(), ...data, id: 11 };
  const saved = await t.api.correct(7, 11, data, 'session');
  assert.deepEqual(t.calls, [['/video/7/words/11/correction', JSON.stringify(data), 'session']]);
  assert.equal(saved.end_time, 8.54);
});
(async () => { for (const t of tests) { await t.fn(); console.log('PASS', t.name); } })().catch(error => { console.error(error); process.exitCode = 1; });
