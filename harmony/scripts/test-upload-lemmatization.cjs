const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const ts = require(path.join(process.env.DEVECO_SDK_HOME,'default/openharmony/ets/build-tools/ets-loader/node_modules/typescript/lib/typescript.js'));
const root = path.join(__dirname,'../entry/src/main/ets');
function compile(source,deps,extra={}) {
 const scope={exports:{},require:n=>deps[n], ...extra};
 vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,scope);
 return scope.exports;
}
(async()=>{
 for(const enabled of [false,true]) for(const modelSelection of ["tiny","base"]) {
  let options;
  const request={request:async(url,o)=>{options=o;return {responseCode:200,result:JSON.stringify({data:{movie_id:1,tracking_token:'t',status:'pending'}})}},destroy(){}};
  const {VideoUploadService}=compile(fs.readFileSync(path.join(root,'service/VideoUploadService.ets'),'utf8'),{
   '@kit.NetworkKit':{http:{createHttp:()=>request,RequestMethod:{POST:'POST'},HttpDataType:{STRING:0}}},
   '@kit.CoreFileKit':{fileIo:{OpenMode:{READ_ONLY:0},open:async()=>({fd:1}),copyFile:async()=>{},close:async()=>{},unlink:async()=>{}}},
   '../common/AppConfig':{AppConfig:{BASE_URL:'http://test',REQUEST_TIMEOUT:15000}},'../common/HttpClient':{ApiError:Error}
  });
  await VideoUploadService.upload('file://video','video.mp4','cache',1,'token',enabled,modelSelection);
  assert.equal(options.multiFormDataList.find(f=>f.name==='enable_lemmatization').data,String(enabled));
  assert.equal(options.multiFormDataList.find(f=>f.name==='whisper_model').data,modelSelection+'.en');
  let captured;
  let source=fs.readFileSync(path.join(root,'pages/upload_movie.ets'),'utf8');
  source=source.slice(0,source.indexOf('  private async uploadMovie()'))+source.slice(source.indexOf('  private async uploadMovie()'),source.indexOf('\n  }',source.indexOf('  private async uploadMovie()'))+4)+'\n}';
  source=source.replace('@Entry','').replace('@Component','').replace('struct UploadMovie','export class UploadMovie').replace(/@State /g,'');
  const {UploadMovie}=compile(source,{
   '../storage/FlashcardSettingsStore':{FlashcardSettingsStore:{load:async()=>({lemmatization:enabled,modelSelection})}},
   '../service/VideoUploadService':{VideoUploadService:{upload:async(...args)=>{captured=args;return {movie_id:1}}}},
   '../service/MovieCompletionMonitor':{MovieCompletionMonitor:{track(){}}}
  },{AppStorage:{get:key=>key==='access_token'?'token':1}});
  const page=new UploadMovie(); page.selectedFileUri='file://video'; page.getUIContext=()=>({getHostContext:()=>({cacheDir:'cache'})});
  await page.uploadMovie(); assert.equal(captured[5],enabled); assert.equal(captured[6],modelSelection);
 }
 console.log('PASS: stored setting true/false reaches actual multipart upload');
})().catch(e=>{console.error(e);process.exitCode=1});
