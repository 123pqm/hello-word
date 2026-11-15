const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const ts=require('C:/Program Files/Huawei/DevEco Studio/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
let source=fs.readFileSync('entry/src/main/ets/components/common/QuizVideo.ets','utf8').split('  build() {')[0]+'\n}';
source=source.replace('@Component','').replace('export struct QuizVideo','export class QuizVideo').replace(/@Prop|@State|@Watch\('[^']+'\)|@StorageLink\('[^']+'\)/g,'');
let jobs=new Map(),seq=0;
class Controller{constructor(){this.starts=0;this.pauses=0;this.seek=-1}pause(){this.pauses++}start(){this.starts++}setCurrentTime(t){this.seek=t}}
const scope={exports:{},VideoController:Controller,SeekMode:{Accurate:1},setTimeout:f=>{jobs.set(++seq,f);return seq},clearTimeout:id=>jobs.delete(id),require:n=>n.includes('HttpClient')?{ApiError:class extends Error{}}:{FlashcardService:{playback:async()=> 'url'}}};
vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,scope);
function switches(){const timer=page.switchTimer;const f=jobs.get(timer);jobs.delete(timer);if(f)f()}
const page=new scope.exports.QuizVideo();page.active=true;page.startTime=1.2;page.endTime=3;page.switchClip();switches();let old=page.activeClip;page.ready=true;page.duration=100;page.playClip();assert.equal(old.controller.seek,1.2);assert.equal(old.controller.starts,0);
page.startTime=10.5;page.endTime=12;page.switchClip();page.seekComplete(old,1.2);assert.equal(old.controller.starts,0);assert.ok(old.controller.pauses>0);switches();const current=page.activeClip;assert.equal(page.queued,true);assert.equal(current.start,10.5);page.ready=true;page.playClip();page.seekComplete(old,10.5);assert.equal(current.controller.starts,0);page.seekComplete(current,10.5);assert.equal(current.controller.starts,1);page.progress(old,99);assert.equal(page.stopAt,12);page.progress(current,12);assert.equal(page.stopAt,-1);
page.startTime=20;page.switchClip();page.startTime=30;page.endTime=32;page.switchClip();switches();assert.equal(page.activeClip.start,30);page.ready=true;page.playClip();let last=page.activeClip;page.suspended=true;page.suspensionChanged();page.seekComplete(last,30);assert.equal(last.controller.starts,0);page.aboutToDisappear();page.seekComplete(last,30);assert.equal(last.controller.starts,0);
console.log('PASS: seek before start, stale callbacks ignored, rapid switch uses latest range, stop at end, suspend/exit cancel');
