import { FlashcardService } from '../../service/FlashcardService';
import { ApiError } from '../../common/HttpClient';

class QuizClip {
  id: number;
  start: number;
  end: number;
  controller: VideoController = new VideoController();
  constructor(id: number, start: number, end: number) {
    this.id = id; this.start = start; this.end = end;
  }
}

@Component
export struct QuizVideo {
  @Prop @Watch('suspensionChanged') suspended: boolean = false;
  @Prop @Watch('reload') movieId: number = 0;
  @Prop @Watch('switchClip') cardId: number = 0;
  @Prop @Watch('switchClip') startTime: number = 0;
  @Prop @Watch('switchClip') endTime: number = 0;
  @Prop answered: boolean = false;
  @StorageLink('access_token') @Watch('reload') token: string = '';
  @State private url: string = '';
  @State private activeClip: QuizClip = new QuizClip(0, 0, 0);
  @State private ready: boolean = false;
  @State private playing: boolean = false;
  @State private message: string = '正在连接视频…';
  private active: boolean = false;
  private generation: number = 0;
  private clipVersion: number = 0;
  private duration: number = 0;
  private target: number = -1;
  private stopAt: number = -1;
  private timer: number = -1;
  private retryTimer: number = -1;
  private switchTimer: number = -1;
  private queued: boolean = false;

  aboutToAppear(): void { this.active = true; this.reload(); }
  aboutToDisappear(): void {
    this.active = false; this.generation++; this.clipVersion++; this.resetClip();
    if (this.retryTimer !== -1) clearTimeout(this.retryTimer);
    if (this.switchTimer !== -1) clearTimeout(this.switchTimer);
  }
  private current(clip: QuizClip): boolean {
    return this.active && clip.id === this.clipVersion && clip === this.activeClip;
  }
  // Invalidate immediately; defer reading props until all fields for the new card have updated.
  switchClip(): void {
    this.clipVersion++;
    this.resetClip(); this.ready = false;
    if (this.switchTimer !== -1) clearTimeout(this.switchTimer);
    if (!this.active) return;
    this.switchTimer = setTimeout(() => {
      this.switchTimer = -1;
      if (!this.active) return;
      this.activeClip = new QuizClip(this.clipVersion, this.startTime, this.endTime);
      this.queued = !this.answered && !this.suspended;
      this.message = this.queued ? '正在准备本题音频…' : '点击回看当前例句';
    }, 0);
  }
  suspensionChanged(): void { if (this.suspended) this.resetClip(); }
  resetClip(): void {
    this.queued = false; this.target = -1; this.stopAt = -1;
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
    try { this.activeClip.controller.pause(); } catch {}
    this.playing = false;
  }
  private async reload(): Promise<void> {
    if (!this.active) return;
    const version = ++this.generation;
    this.url = ''; this.switchClip();
    if (this.retryTimer !== -1) clearTimeout(this.retryTimer);
    this.retryTimer = -1;
    const token = this.token;
    this.message = '正在连接视频…';
    try {
      const url = await FlashcardService.playback(this.movieId, token);
      if (this.active && version === this.generation && token === this.token) this.url = url;
    } catch (error) {
      if (!this.active || version !== this.generation) return;
      const failure = error as Error;
      this.message = failure.message || '视频暂不可用，可继续答题';
      if (failure instanceof ApiError && failure.status === 202) {
        this.retryTimer = setTimeout(() => {
          this.retryTimer = -1;
          if (this.active && version === this.generation) this.reload();
        }, 3000);
      }
    }
  }
  private playClip(): void {
    const clip = this.activeClip;
    if (this.suspended || !this.current(clip)) return;
    if (!this.ready) { this.queued = true; return; }
    this.resetClip();
    if (!Number.isFinite(clip.start) || !Number.isFinite(clip.end) || clip.start < 0 ||
      clip.end <= clip.start || clip.start >= this.duration) {
      this.message = '片段时间无效，请在单词闪卡中校正'; return;
    }
    this.target = clip.start; this.stopAt = Math.min(clip.end, this.duration);
    this.message = '正在定位本题片段…';
    this.timer = setTimeout(() => {
      if (!this.current(clip)) return;
      this.resetClip(); this.message = '定位超时，请重试';
    }, 10000);
    try { clip.controller.setCurrentTime(clip.start, SeekMode.Accurate); }
    catch { this.resetClip(); this.message = '定位失败，请重新连接'; }
  }
  private seekComplete(clip: QuizClip, time: number): void {
    if (!this.current(clip) || this.suspended || this.target < 0 || !Number.isFinite(time)) return;
    if (Math.abs(time - this.target) > 0.25 &&
      !(Number.isInteger(time) && (time === Math.floor(this.target) || time === Math.round(this.target)))) return;
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1; this.target = -1;
    try { clip.controller.start(); this.message = this.answered ? '正在播放例句' : '正在播放本题音频'; }
    catch { this.resetClip(); this.message = '播放失败，请重新连接'; }
  }
  private progress(clip: QuizClip, time: number): void {
    if (!this.current(clip)) return;
    if (this.target >= 0) { this.seekComplete(clip, time); return; }
    if (this.stopAt >= 0 && time >= this.stopAt) {
      this.resetClip(); this.message = '片段结束，可再次播放';
    }
  }
  build() {
    Column({ space: 8 }) {
      Stack() {
        if (this.url) {
          ForEach([this.activeClip], (clip: QuizClip) => {
            Video({ src: this.url, controller: clip.controller }).width('100%').height('100%')
              .controls(false).autoPlay(false).objectFit(ImageFit.Cover).backgroundColor('#101827')
              .onPrepared((event) => {
                if (!this.current(clip)) return;
                this.ready = true; this.duration = event.duration;
                if (this.queued) this.playClip();
              })
              .onSeeked((event) => this.seekComplete(clip, event.time))
              .onUpdate((event) => this.progress(clip, event.time))
              .onStart(() => {
                if (!this.current(clip) || this.suspended || this.stopAt < 0) {
                  try { clip.controller.pause(); } catch {}
                  return;
                }
                this.playing = true;
              })
              .onPause(() => { if (this.current(clip)) this.playing = false; })
              .onFinish(() => { if (this.current(clip)) this.resetClip(); })
              .onError(() => {
                if (!this.current(clip)) return;
                this.resetClip(); this.ready = false; this.message = '播放失败，请点击重连';
              })
          }, (clip: QuizClip) => `${clip.id}`)
        }
        if (!this.answered) {
          Column({ space: 12 }) {
            Image($r('app.media.quiz_eye_off')).width(32).height(32)
            Text('答题模式').fontSize(18).fontWeight(FontWeight.Bold).fontColor(Color.White)
            Text('视频画面已遮挡').fontSize(14).fontColor('#252936').padding({ left: 16, right: 16, top: 8, bottom: 8 })
              .backgroundColor(Color.White).borderRadius(20)
          }.width('100%').height('100%').justifyContent(FlexAlign.Center)
            .backgroundColor('#252936').zIndex(10).hitTestBehavior(HitTestMode.Block)
        }
      }.width('100%').aspectRatio(16 / 9).clip(true).backgroundColor('#252936')
      Row({ space: 8 }) {
        Button(this.playing ? (this.answered ? '暂停片段' : '暂停音频') : (this.answered ? '▶ 回看例句' : '▶ 播放音频'))
          .layoutWeight(1).height(36).backgroundColor('#EAF2FF').fontColor('#3478F6')
          .enabled(this.url.length > 0 && !this.suspended)
          .onClick(() => { if (this.playing || this.target >= 0) this.resetClip(); else this.playClip(); })
        Button('重连').height(36).backgroundColor('#EAF2FF').fontColor('#64748B').onClick(() => this.reload())
      }.width('100%')
      Text(this.message).fontSize(12).fontColor('#64748B').width('100%')
    }.width('100%')
  }
}
