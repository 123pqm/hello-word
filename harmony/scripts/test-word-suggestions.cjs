const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require(path.join(process.env.DEVECO_SDK_HOME,
  'default/openharmony/ets/build-tools/ets-loader/node_modules/typescript/lib/typescript.js'));
const source = fs.readFileSync(path.join(__dirname,
  '../entry/src/main/ets/viewmodels/WordListViewModel.ets'), 'utf8');
const js = ts.transpileModule(source.replace('@Observed', ''), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText;
const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
async function main() {
  let timer, calls = 0, resolveSuggestion;
  const service = {
    getWords: async () => ({ data: [], total: 0 }),
    searchWords: async () => ({ data: [], has_more: false, next_offset: null }),
    suggestWords: () => { calls++; return new Promise(r => resolveSuggestion = r); }
  };
  const context = { exports: {}, Error, AppStorage: { get: () => 'token' },
    require: name => name.includes('HttpClient') ? { ApiError: class extends Error {} } : { WordbookService: service },
    setTimeout: fn => { timer = fn; return 1; }, clearTimeout: () => { timer = undefined; } };
  vm.runInNewContext(js, context);
  const model = new context.exports.WordListViewModel();
  await model.loadInitial(0);
  model.onSearchInput('abandn'); timer(); await settle();
  assert.equal(calls, 1); assert.equal(model.suggesting, true);
  model.onSearchInput('environment');
  resolveSuggestion({ data: [{ word: 'abandon' }] }); await settle();
  assert.equal(model.suggestions.length, 0); // 旧请求不能覆盖新输入
  service.searchWords = async () => ({ data: [{ id: 1, word: 'environment' }], has_more: false });
  timer(); await settle(); assert.equal(calls, 1); // 有结果不调用模型
  service.searchWords = async () => { throw new Error('network failed'); };
  model.onSearchInput('abandn'); timer(); await settle();
  assert.equal(calls, 1); assert.equal(model.searchError, 'network failed');
  service.searchWords = async () => ({ data: [], has_more: false });
  model.onSearchInput('abandn'); timer(); await settle();
  resolveSuggestion({ data: [{ word: 'abandon' }] }); await settle();
  assert.equal(model.suggestions[0], 'abandon'); assert.equal(model.suggesting, false);
  model.onSearchInput('insurence'); timer(); await settle(); model.dispose();
  resolveSuggestion({ data: [{ word: 'insurance' }] }); await settle();
  assert.equal(model.suggestions.length, 0);
  console.log('PASS: empty search, successful search, network error, stale response, disposal');
}
main().catch(e => { console.error(e); process.exitCode = 1; });
