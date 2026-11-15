const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const ts=require('C:/Program Files/Huawei/DevEco Studio/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
let req;
const http={RequestMethod:{POST:1},createHttp:()=> req={events:{},on(n,f){this.events[n]=f},off(n){delete this.events[n]},destroy(){this.dead=true},requestInStream(){return new Promise((r,j)=>{this.status=r;this.fail=j})}}};
class Decoder { constructor(){this.d=new TextDecoder()} decodeToString(b,o){return this.d.decode(b,o)} }
const ctx={exports:{},setTimeout,clearTimeout,Uint8Array,require:n=>n==='@kit.NetworkKit'?{http}:n==='@kit.ArkTS'?{util:{TextDecoder:Decoder}}:{AppConfig:{BASE_URL:'http://test'}}};
vm.runInNewContext(ts.transpileModule(fs.readFileSync('entry/src/main/ets/service/FlashcardAIService.ets','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,ctx);
const Service=ctx.exports.FlashcardAIService;
const send=s=>{for(const byte of Buffer.from(s))req.events.dataReceive(Uint8Array.of(byte).buffer)};
(async()=>{
 let updates=[];const s=new Service();let p=s.ask([],'a','','',v=>updates.push(v));
 send(': ping\r\n\r\ndata: {"type":"delta","content":"保险😀"}\r\n\r\n');
 assert.equal(updates.at(-1),'保险😀'); // Visible BEFORE response completes, with UTF-8 and CRLF split at every byte.
 send('data: {"type":"delta","content":"解释"}\n\ndata: {"type":"done"}\n\n');req.events.dataEnd();req.status(200);
 assert.equal(await p,'保险😀解释');assert.equal(req.dead,true);
 p=s.ask([],'a','','');send('data: {"type":"delta","content":"部分"}\n\n');req.status(200);req.events.dataEnd();await assert.rejects(p,/未完成/);
 p=s.ask([],'a','','');send('data: {"type":"error","message":"上游错误"}\n\n');await assert.rejects(p,/上游错误/);
 p=s.ask([],'a','','');s.cancel();await assert.rejects(p,/取消/);assert.equal(req.dead,true);
 p=s.ask([],'a','','');req.status(401);await assert.rejects(p,/401/);
 console.log('PASS: incremental display, UTF-8 boundaries, SSE framing, done, truncated stream, server error, cancel, HTTP error');
})().catch(e=>{console.error(e);process.exitCode=1});
