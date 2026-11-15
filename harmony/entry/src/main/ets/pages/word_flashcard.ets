import { FlashcardAISheet } from '../components/common/FlashcardAISheet';
import { router } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { ApiError } from '../common/HttpClient';
import { FlashcardService } from '../service/FlashcardService';
import { VideoService } from '../service/VideoService';
import { FavoriteService } from '../service/FavoriteService';
import { FlashcardSettingsStore } from '../storage/FlashcardSettingsStore';
import { MovieWord } from '../models/FlashcardModels';

interface FlashcardParams { movie_id: number; movie_name?: string; }

@Entry
@Component
struct WordFlashcard {
  @StorageLink('access_token') @Watch('sessionChanged') token: string = '';
  @State private movieName: string = '';
  @State private aiVisible: boolean = false;
  @State private correctionVisible: boolean = false;
  @State private correctionStart: string = '';
  @State private correctionEnd: string = '';
  @State private correctionSentence: string = '';
  @State private correctionError: string = '';
  @State private correctionSaving: boolean = false;
  private correctionId: number = 0;
  @State private words: MovieWord[] = [];
  @State private index: number = 0;
  @State private loading: boolean = false;
  @State private error: string = '';
  @State private waiting: boolean = false;
  @State private loginRequired: boolean = false;
  @State private revealed: boolean = false;
  @State private sentenceRevealed: boolean = false;
  @State private videoUrl: string = '';
  @State private videoMessage: string = '';
  @State private videoLoading: boolean = false;
  @State private videoReady: boolean = false;
  private movieId: number = 0;
  private active: boolean = false;
  private version: number = 0;
  private videoVersion: number = 0;
  private timer: number = -1;
  private clipEnd: number = -1;
  private clipCompleted: boolean = false;
  private seekTarget: number = -1;
  private seekTimer: number = -1;
  private videoTimer: number = -1;
  private pendingPlay: boolean = false;
  @State private duration: number = 0;
  @State private controlsVisible: boolean = false;
  @State private playing: boolean = false;
  @State private playbackPosition: number = 0;
  private dragging: boolean = false;
  private controlsTimer: number = -1;
  private scroller: Scroller = new Scroller();
  private controller: VideoController = new VideoController();
  @State private isFavorite: boolean = false;
  @State private favoriteLoading: boolean = false;
  private favoriteVersion: number = 0;
  private defaultRevealed: boolean = false;
  private defaultSentenceRevealed: boolean = true;

  aboutToAppear(): void {
    const params = router.getParams() as FlashcardParams;
    this.movieId = params?.movie_id ?? 0;
    this.movieName = params?.movie_name ?? '电影单词';
    this.active = true;
    this.load();
  }
  aboutToDisappear(): void { this.stop(); }
  onPageHide(): void { this.stop(); }
  onPageShow(): void {
    if (!this.active) { this.active = true; this.load(); }
  }
  private stop(): void {
    this.correctionVisible = false;
    this.correctionSaving = false;
    this.aiVisible = false;
    this.active = false;
    this.version++;
    this.videoVersion++;
    this.favoriteVersion++;
    this.clearVideoTimer();
    this.hideControls();
    this.pendingPlay = false;
    this.clearTimer();
    this.loading = false;
    this.videoLoading = false;
    this.favoriteLoading = false;
    this.isFavorite = false;
    this.pause();
    this.videoUrl = '';
    this.videoReady = false;
  }
  private clearTimer(): void {
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
  }
  private clearVideoTimer(): void {
    if (this.videoTimer !== -1) clearTimeout(this.videoTimer);
    this.videoTimer = -1;
  }
  private pause(resetClip: boolean = true): void {
    if (this.seekTimer !== -1) clearTimeout(this.seekTimer);
    this.seekTimer = -1;
    this.seekTarget = -1;
    if (resetClip) {
      this.clipEnd = -1;
      this.clipCompleted = false;
    }
    this.playing = false;
    if (this.videoReady) {
      try { this.controller.pause(); } catch {
        // 播放器异常不能阻断单词切换。
        this.videoReady = false;
      }
    }
  }
  sessionChanged(): void {
    this.correctionVisible = false;
    this.correctionSaving = false;
    this.aiVisible = false;
    this.version++;
    this.videoVersion++;
    this.favoriteVersion++;
    this.clearVideoTimer();
    this.hideControls();
    this.pendingPlay = false;
    this.clearTimer();
    this.pause();
    this.words = [];
    this.videoUrl = '';
    this.videoReady = false;
    this.loading = false;
    this.videoLoading = false;
    this.isFavorite = false;
    this.favoriteLoading = false;
    if (this.active) this.load();
  }
  private current(version: number, token: string): boolean {
    return this.active && version === this.version && token === this.token;
  }
  private async load(): Promise<void> {
    if (!this.active || this.loading) return;
    this.clearTimer();
    this.error = '';
    this.waiting = false;
    this.loginRequired = !this.token;
    if (!this.token) { this.error = '请先登录后查看闪卡'; return; }
    if (!Number.isInteger(this.movieId) || this.movieId <= 0) {
      this.error = '电影编号无效，请返回历史记录重新进入'; return;
    }
    const version = ++this.version;
    const token = this.token;
    this.loading = true;
    try {
      const words = await FlashcardService.words(this.movieId, token);
      if (!this.current(version, token)) return;
      this.words = words;
      this.index = 0;
      const context = this.getUIContext().getHostContext();
      if (context) {
        try {
          const settings = await FlashcardSettingsStore.load(context as common.Context);
          if (!this.current(version, token)) return;
          this.defaultRevealed = settings.showMeaning;
          this.defaultSentenceRevealed = settings.showExample;
        } catch {
          // 配置读取失败时使用默认值
        }
      }
      this.revealed = this.defaultRevealed;
      this.sentenceRevealed = this.defaultSentenceRevealed;
      if (words.length > 0) {
        this.connectVideo();
        this.loadFavoriteStatus();
      }
    } catch (error) {
      if (!this.current(version, token)) return;
      const failure = error as Error;
      if (failure instanceof ApiError && failure.status === 409) {
        try {
          const status = await VideoService.status(this.movieId, token);
          if (!this.current(version, token)) return;
          this.waiting = status.status === 'pending' || status.status === 'processing';
          this.error = this.waiting ? '电影正在分析，完成后自动加载闪卡…' :
            (status.error_message || '暂时无法读取单词，请稍后重试');
          if (this.waiting) {
            this.timer = setTimeout(() => { this.timer = -1; this.load(); }, 3000);
          }
        } catch (statusError) {
          if (this.current(version, token)) this.showError(statusError as Error);
        }
      } else this.showError(failure);
    } finally {
      if (this.current(version, token)) this.loading = false;
    }
  }
  private showError(error: Error): void {
    if (error instanceof ApiError && error.status === 401) {
      this.loginRequired = true;
      this.error = '登录已过期，请重新登录';
    } else if (error instanceof ApiError && error.status === 404) {
      this.error = '该电影已删除或当前账号无权访问';
    } else this.error = error.message || '加载失败，请检查网络后重试';
  }
  private async connectVideo(): Promise<void> {
    if (!this.active || this.videoLoading || !this.token) return;
    this.clearVideoTimer();
    this.hideControls();
    const version = ++this.videoVersion;
    const token = this.token;
    this.pause();
    this.videoReady = false;
    this.videoUrl = '';
    this.videoLoading = true;
    this.videoMessage = '正在连接视频…';
    try {
      const url = await FlashcardService.playback(this.movieId, token);
      if (this.active && version === this.videoVersion && token === this.token) {
        this.videoUrl = url;
        this.videoMessage = '兼容视频已连接，正在加载画面…';
        this.videoTimer = setTimeout(() => {
          this.videoTimer = -1;
          if (this.active && this.videoUrl === url && !this.videoReady) {
            this.videoUrl = '';
            this.videoMessage = '视频加载超时，请重新连接';
          }
        }, 30000);
      }
    } catch (error) {
      if (this.active && version === this.videoVersion && token === this.token) {
        const failure = error as Error;
        this.videoMessage = failure.message || '视频连接失败，仍可学习单词';
        if (failure instanceof ApiError && failure.status === 202) {
          this.videoTimer = setTimeout(() => {
            this.videoTimer = -1;
            if (this.active && version === this.videoVersion && token === this.token) this.connectVideo();
          }, 3000);
        } else {
          this.pendingPlay = false;
        }
      }
    } finally {
      if (this.active && version === this.videoVersion && token === this.token) this.videoLoading = false;
    }
  }
  private move(offset: number): void {
    const next = this.index + offset;
    if (next < 0 || next >= this.words.length) return;
    this.pause();
    this.pendingPlay = false;
    this.index = next;
    this.videoMessage = '已切换单词，点击回看播放当前例句';
    this.revealed = this.defaultRevealed;
    this.sentenceRevealed = this.defaultSentenceRevealed;
    this.loadFavoriteStatus();
  }
  private playSentence(): void {
    if (!this.active || this.words.length === 0) return;
    this.scroller.scrollEdge(Edge.Top);
    if (!this.videoReady) {
      this.pendingPlay = true;
      if (!this.videoLoading && !this.videoUrl && this.videoTimer === -1) this.connectVideo();
      return;
    }
    const word = this.words[this.index];
    if (!Number.isFinite(word.start_time) || !Number.isFinite(word.end_time) ||
      word.start_time < 0 || word.end_time <= word.start_time || word.start_time >= this.duration) {
      this.pause();
      this.videoMessage = '该词条时间超出视频范围，无法定位片段';
      return;
    }
    this.pendingPlay = false;
    this.pause();
    this.clipEnd = Math.min(word.end_time, this.duration);
    this.seekTarget = word.start_time;
    this.videoMessage = `正在定位到 ${word.start_time.toFixed(1)} 秒…`;
    // 先设置超时，避免同步定位回调完成后又残留一个超时任务。
    this.seekTimer = setTimeout(() => {
      if (this.seekTarget >= 0) {
        this.pause();
        this.videoMessage = '片段定位超时，请重试或重新连接视频';
      }
    }, 10000);
    try {
      this.controller.setCurrentTime(word.start_time, SeekMode.Accurate);
    } catch {
      this.pause();
      this.videoMessage = '片段定位失败，请重试或重新连接视频';
      return;
    }
  }
  private prepared(duration: number): void {
    this.clearVideoTimer();
    this.hideControls();
    this.duration = duration;
    this.playbackPosition = 0;
    this.playing = false;
    this.videoReady = true;
    this.videoMessage = '视频已就绪，点击下方按钮回看当前单词片段';
    if (this.pendingPlay) this.playSentence();
  }
  private seeked(time: number): void {
    if (!this.active || this.seekTarget < 0 || !Number.isFinite(time) ||
      !this.matchesSeekTarget(time)) return;
    if (this.seekTimer !== -1) clearTimeout(this.seekTimer);
    this.seekTimer = -1;
    this.playbackPosition = this.seekTarget;
    this.seekTarget = -1;
    this.videoMessage = '正在播放当前单词片段';
    try { this.controller.start(); } catch {
      this.pause();
      this.videoMessage = '片段播放失败，请重试';
    }
  }
  private matchesSeekTarget(time: number): boolean {
    // 部分播放器将秒数取整，不能用小数级阈值拒绝已完成的跳转。
    return Math.abs(time - this.seekTarget) <= 0.25 ||
      (Number.isInteger(time) && (time === Math.floor(this.seekTarget) || time === Math.round(this.seekTarget)));
  }
  private updateTime(time: number): void {
    if (!this.active || !Number.isFinite(time)) return;
    if (this.seekTarget >= 0) {
      // 兼容只报告进度、不报告 onSeeked 的情况；旧位置仍不能触发播放。
      this.seeked(time);
      return;
    }
    if (!this.dragging) this.playbackPosition = time;
    if (this.seekTarget < 0 && this.clipEnd >= 0 && time >= this.clipEnd) {
      this.finishSentence();
    }
  }
  private finishSentence(): void {
    this.pause();
    this.clipCompleted = true;
    this.videoMessage = '例句播放结束，点击回看可从句首重播';
  }
  private videoFinished(): void {
    this.playing = false;
    if (this.clipEnd >= 0 && this.seekTarget < 0) this.finishSentence();
  }
  private hideControls(): void {
    if (this.controlsTimer !== -1) clearTimeout(this.controlsTimer);
    this.controlsTimer = -1;
    this.controlsVisible = false;
    this.dragging = false;
  }
  private showControls(): void {
    if (this.controlsTimer !== -1) clearTimeout(this.controlsTimer);
    this.controlsVisible = true;
    this.controlsTimer = setTimeout(() => this.hideControls(), 3000);
  }
  private togglePlayback(): void {
    if (!this.videoReady) return;
    if (this.seekTarget >= 0) return;
    try {
      if (this.playing) this.pause(false);
      else if (this.clipCompleted) this.playSentence();
      else this.controller.start();
    } catch {
      this.videoMessage = '播放状态异常，请重新连接视频';
    }
    this.showControls();
  }
  private formatTime(value: number): string {
    const seconds = Math.max(0, Math.floor(value));
    return `${Math.floor(seconds / 60)}:${(seconds % 60).toString().padStart(2, '0')}`;
  }
  private async loadFavoriteStatus(): Promise<void> {
    if (!this.active || !this.token || this.words.length === 0) return;
    const word = this.words[this.index].word;
    const version = ++this.favoriteVersion;
    const token = this.token;
    const index = this.index;
    this.favoriteLoading = true;
    try {
      const favorite = await FavoriteService.getStatus(word, token);
      if (!this.active || version !== this.favoriteVersion || token !== this.token || index !== this.index) return;
      this.isFavorite = favorite;
    } catch {
      if (!this.active || version !== this.favoriteVersion || token !== this.token || index !== this.index) return;
      this.isFavorite = false;
    } finally {
      if (this.active && version === this.favoriteVersion && token === this.token && index === this.index) {
        this.favoriteLoading = false;
      }
    }
  }
  private async toggleFavorite(): Promise<void> {
    if (!this.active || !this.token || this.words.length === 0 || this.favoriteLoading) return;
    const word = this.words[this.index].word;
    const token = this.token;
    const index = this.index;
    this.favoriteLoading = true;
    try {
      if (this.isFavorite) {
        await FavoriteService.remove(word, token);
        if (token === this.token && index === this.index) this.isFavorite = false;
      } else {
        await FavoriteService.add(this.movieId, word, token);
        if (token === this.token && index === this.index) this.isFavorite = true;
      }
    } catch (error) {
      if (token === this.token && index === this.index) {
        const failure = error as Error;
        this.videoMessage = failure.message || '收藏操作失败，请重试';
      }
    } finally {
      if (token === this.token && index === this.index) this.favoriteLoading = false;
    }
  }
  private openCorrection(): void {
    if (this.correctionSaving || this.words.length === 0) return;
    this.pause();
    this.pendingPlay = false;
    const word = this.words[this.index];
    this.correctionId = word.id ?? 0;
    this.correctionStart = word.start_time.toFixed(2);
    this.correctionEnd = word.end_time.toFixed(2);
    this.correctionSentence = word.sentence_text ?? '';
    this.correctionError = this.correctionId > 0 ? '' : '请重启新版后端并重新进入闪卡后再修改';
    this.correctionVisible = true;
  }

  private async saveCorrection(): Promise<void> {
    if (this.correctionSaving || this.correctionId <= 0) return;
    const startText = this.correctionStart.trim();
    const endText = this.correctionEnd.trim();
    const pattern = /^\d{1,8}(\.\d{1,2})?$/;
    if (!pattern.test(startText) || !pattern.test(endText)) {
      this.correctionError = '时间请输入非负秒数，最多保留两位小数'; return;
    }
    const start = Number(startText);
    const end = Number(endText);
    if (end <= start) { this.correctionError = '结束时间必须大于开始时间'; return; }
    if (this.videoReady && this.duration > 0 && end > this.duration) {
      this.correctionError = `结束时间不能超过视频总时长 ${this.duration.toFixed(2)} 秒`; return;
    }
    const sentence = this.correctionSentence.trim();
    if (!sentence || sentence.length > 4000) {
      this.correctionError = '例句不能为空，且不能超过 4000 字'; return;
    }
    this.correctionSaving = true;
    this.correctionError = '';
    const version = this.version;
    const token = this.token;
    const id = this.correctionId;
    try {
      const updated = await FlashcardService.correct(this.movieId, id,
        { start_time: start, end_time: end, sentence_text: sentence }, token);
      if (!this.current(version, token)) return;
      this.words = this.words.map((word: MovieWord) => word.id === id ? updated : word);
      this.pause();
      this.correctionVisible = false;
      this.videoMessage = '校正已保存，点击回看使用新的例句时间';
    } catch (error) {
      if (this.current(version, token)) this.correctionError = (error as Error).message || '保存失败，请重试';
    } finally {
      if (this.current(version, token)) this.correctionSaving = false;
    }
  }

  @Builder
  CorrectionSheet() {
    Scroll() {
      Column({ space: 14 }) {
        Text('校正当前闪卡').fontSize(22).fontWeight(FontWeight.Bold).fontColor('#1F2937')
        Text('时间单位为秒，保存后用于当前例句回放').fontSize(13).fontColor('#64748B')
        Row({ space: 12 }) {
          Column({ space: 6 }) {
            Text('开始时间（秒）').fontSize(14).fontColor('#475569')
            TextInput({ text: $$this.correctionStart, placeholder: '例如 12.50' })
              .type(InputType.Normal).enabled(!this.correctionSaving).backgroundColor('#FFFFFF')
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Column({ space: 6 }) {
            Text('结束时间（秒）').fontSize(14).fontColor('#475569')
            TextInput({ text: $$this.correctionEnd, placeholder: '例如 16.80' })
              .type(InputType.Normal).enabled(!this.correctionSaving).backgroundColor('#FFFFFF')
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        }.width('100%')
        Text('例句').fontSize(14).fontColor('#475569').width('100%')
        TextArea({ text: $$this.correctionSentence, placeholder: '输入校正后的完整例句' })
          .height(130).maxLength(4000).enabled(!this.correctionSaving).backgroundColor('#FFFFFF')
        if (this.correctionError) {
          Text(this.correctionError).fontSize(13).fontColor('#C44B43').width('100%')
        }
        Row({ space: 12 }) {
          Button('取消').layoutWeight(1).height(44).backgroundColor('#E2E8F0').fontColor('#475569')
            .enabled(!this.correctionSaving).onClick(() => { this.correctionVisible = false; })
          Button(this.correctionSaving ? '保存中…' : '保存校正').layoutWeight(1).height(44)
            .backgroundColor('#3478F6').enabled(!this.correctionSaving && this.correctionId > 0)
            .onClick(() => this.saveCorrection())
        }.width('100%')
      }.width('100%').padding({ left: 20, right: 20, top: 16, bottom: 28 })
    }.width('100%').height('100%')
  }

  @Builder
  Flashcard() {
    Stack({ alignContent: Alignment.TopEnd }) {
      Column({ space: 16 }) {
        Text(this.words[this.index].word).fontSize(34).fontWeight(FontWeight.Bold).fontColor('#111827')
          .width('100%').textAlign(TextAlign.Center)
        Button(this.revealed ? (this.words[this.index].meaning || '暂无释义') : '点击显示单词释义', { type: ButtonType.Normal })
          .width('100%').constraintSize({ minHeight: 64 }).padding(12)
          .fontSize(this.revealed ? 20 : 14).fontColor(this.revealed ? '#374151' : '#64748B')
          .backgroundColor(Color.Transparent).borderRadius(12)
          .onClick(() => { this.revealed = !this.revealed; })
        Divider().color('#E5E7EB')
        if (this.words[this.index].sentence_text) {
          Column() {
            Text(this.sentenceRevealed ? (this.words[this.index].sentence_text ?? '') : '点击显示例句')
              .width('100%').fontSize(16).lineHeight(26)
              .fontColor('#64748B').textAlign(TextAlign.Center)
              .wordBreak(WordBreak.BREAK_WORD)
          }
          .width('100%').constraintSize({ minHeight: 64 }).padding({ left: 8, right: 8, top: 16, bottom: 16 })
          .justifyContent(FlexAlign.Center).borderRadius(12)
          .onClick(() => { this.sentenceRevealed = !this.sentenceRevealed; })
        } else {
          Text('该词条暂无例句').fontSize(14).fontColor('#6B7280').padding(12)
        }
      }.width('100%').padding({ left: 24, right: 24, top: 68, bottom: 28 })
        .backgroundColor(Color.White).borderRadius(20)
        .shadow({ radius: 8, color: '#0A000000', offsetY: 3 })
      Row() {
      Button({ type: ButtonType.Normal }) {
        Row({ space: 5 }) {
          Image($r('app.media.ic_flashcard_wrench')).width(20).height(20)
          Text('校正').fontSize(13).fontColor('#3478F6')
        }
      }
        .height(36).padding({ left: 12, right: 12 }).borderRadius(18)
        .backgroundColor('#EAF2FF')
        .accessibilityText('校正例句和时间').onClick(() => this.openCorrection())
        .bindSheet($$this.correctionVisible, this.CorrectionSheet(), {
          height: '65%', dragBar: true, showClose: true, backgroundColor: '#F5F7FA'
        })
      Blank()
      if (this.isFavorite) {
        Image($r('app.media.android_ic_star_filled'))
          .width(30).height(30)
          .fillColor('#F59E0B')
          .onClick(() => this.toggleFavorite())
      } else {
        Image($r('app.media.android_ic_star_outline'))
          .width(30).height(30)
          .fillColor('#9CA3AF')
          .onClick(() => this.toggleFavorite())
      }
      }.width('100%').padding({ left: 12, right: 14, top: 12 }).align(Alignment.TopStart)
    }.width('100%')
  }

  @Builder
  AISheet() {
    FlashcardAISheet({
      word: this.words[this.index]?.word ?? '',
      sentence: this.words[this.index]?.sentence_text ?? '',
      onClose: () => { this.aiVisible = false; }
    })
  }

  build() {
    Column() {
      Row() {
        Button('‹', { type: ButtonType.Normal }).width(44).height(44).fontSize(30)
          .backgroundColor(Color.Transparent).fontColor('#1F2937').accessibilityText('返回历史记录')
          .onClick(() => router.back())
        Text('单词闪卡').fontSize(24).fontWeight(FontWeight.Bold).fontColor('#1F2937').layoutWeight(1)
        Button({ type: ButtonType.Normal }) {
          Image($r('app.media.android_vh_robot')).width(64).height(64).objectFit(ImageFit.Contain)
        }.width(68).height(68).padding(0).backgroundColor(Color.Transparent)
          .accessibilityText('打开 AI 单词问答').enabled(this.words.length > 0)
          .onClick(() => { this.pause(); this.aiVisible = true; })
          .bindSheet($$this.aiVisible, this.AISheet(), {
            height: '72%', dragBar: true, showClose: false,
            backgroundColor: '#F5F7FA'
          })
      }.width('100%').padding({ left: 12, right: 20, top: 8, bottom: 8 })
      Scroll(this.scroller) {
        Column({ space: 16 }) {
          Text(this.movieName).fontSize(14).fontColor('#6B7280').width('100%')
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          if (this.loading && this.words.length === 0) {
            LoadingProgress().width(36).height(36).margin({ top: 60 })
            Text('正在加载电影单词…').fontColor('#6B7280')
          } else if (this.error) {
            Text(this.error).fontSize(16).fontColor('#6B7280').textAlign(TextAlign.Center).padding(24)
            if (this.waiting) { LoadingProgress().width(30).height(30) }
            if (this.loginRequired) {
              Button('重新登录').onClick(() => router.replaceUrl({ url: 'pages/Index' }))
            } else {
              Button('重新加载').enabled(!this.loading).onClick(() => this.load())
            }
          } else if (this.words.length === 0) {
            Text('这部电影暂无匹配单词').fontSize(20).fontColor('#374151').margin({ top: 60 })
            Text('可以返回历史记录，选择其他电影学习').fontSize(14).fontColor('#6B7280')
          } else {
            Text(`第 ${this.index + 1} / ${this.words.length} 张`).fontSize(14).fontColor('#6B7280').width('100%')
            if (this.videoUrl) {
              ForEach([this.videoUrl], (url: string) => {
                Stack({ alignContent: Alignment.Bottom }) {
                Video({ src: url, controller: this.controller }).width('100%').height('100%')
                                    .autoPlay(false).controls(false).objectFit(ImageFit.Cover)
                  .onClick(() => {
                    if (this.controlsVisible) this.hideControls();
                    else this.showControls();
                  })
                  .onStart(() => { if (this.videoUrl === url) this.playing = true; })
                  .onPause(() => { if (this.videoUrl === url) this.playing = false; })
                  .onFinish(() => { if (this.active && this.videoUrl === url) this.videoFinished(); })
                  .onPrepared((event) => {
                    if (this.active && this.videoUrl === url) this.prepared(event.duration);
                  })
                  .onSeeked((event) => {
                    if (this.active && this.videoUrl === url) this.seeked(event.time);
                  })
                  .onUpdate((event) => {
                    if (this.active && this.videoUrl === url) this.updateTime(event.time);
                  })
                  .onError(() => {
                    if (!this.active || this.videoUrl !== url) return;
                    this.clearVideoTimer();
    this.hideControls();
                    this.pause();
                    this.videoReady = false;
                    this.pendingPlay = false;
                    this.videoUrl = '';
                    this.videoMessage = '兼容视频播放失败，请重新连接；若持续失败请检查后端视频文件';
                  })
                  if (this.controlsVisible) {
                    Row({ space: 8 }) {
                      Button(this.playing ? 'Ⅱ' : '▶', { type: ButtonType.Normal })
                        .width(40).height(40).fontSize(20).fontColor(Color.White)
                        .backgroundColor(Color.Transparent).enabled(this.videoReady)
                        .accessibilityText(this.playing ? '暂停' : '播放')
                        .onClick(() => this.togglePlayback())
                      Text(this.formatTime(this.playbackPosition)).fontSize(12).fontColor(Color.White)
                      Slider({ value: this.playbackPosition, min: 0, max: Math.max(1, this.duration), step: 0.1 })
                        .layoutWeight(1).enabled(this.videoReady).selectedColor('#FFFFFF').trackColor('#99FFFFFF')
                        .onChange((value: number, mode: SliderChangeMode) => {
                          if (mode === SliderChangeMode.Begin || mode === SliderChangeMode.Moving) {
                            if (this.controlsTimer !== -1) clearTimeout(this.controlsTimer);
                            this.controlsTimer = -1;
                            this.dragging = true;
                            this.playbackPosition = value;
                          } else {
                            this.dragging = false;
                            this.playbackPosition = value;
                            this.clipEnd = -1;
                            this.clipCompleted = false;
                            this.seekTarget = -1;
                            if (this.seekTimer !== -1) clearTimeout(this.seekTimer);
                            this.seekTimer = -1;
                            try { this.controller.setCurrentTime(value, SeekMode.Accurate); } catch {
                              this.videoMessage = '定位失败，请重试';
                            }
                            this.showControls();
                          }
                        })
                      Text(this.formatTime(this.duration)).fontSize(12).fontColor(Color.White)
                    }.width('100%').height(44).padding({ left: 4, right: 12 }).backgroundColor('#88000000')
                  }
                }.width('100%').aspectRatio(16 / 9).clip(true)
              }, (url: string) => url)
            } else {
              Column() { Text('电影片段').fontColor('#CBD5E1') }
                .width('100%').aspectRatio(16 / 9).justifyContent(FlexAlign.Center).backgroundColor('#1F2937').borderRadius(16)
            }
            Text(this.videoMessage).fontSize(12).fontColor('#667085').width('100%')
            Button(this.videoLoading ? '连接中…' : '重新连接视频').width('100%').height(48)
              .backgroundColor('#EAF2FF').fontColor('#3478F6').enabled(!this.videoLoading)
              .onClick(() => this.connectVideo())
            this.Flashcard()
            Button('记不起来，跳转试一下').width('100%').height(52)
              .backgroundColor('#EAF2FF').fontColor('#3478F6')
              .enabled(this.words[this.index].end_time > this.words[this.index].start_time)
              .onClick(() => this.playSentence())

          }
        }.width('100%').padding({ left: 20, right: 20, top: 12, bottom: 32 })
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
      if (this.words.length > 0 && !this.error && !this.loading) {
        Column({ space: 8 }) {
          Text(`第 ${this.index + 1} / ${this.words.length} 张`).fontSize(12).fontColor('#6B7280')
          Row({ space: 12 }) {
            Button('← 上一个', { type: ButtonType.Normal }).layoutWeight(1).height(48).borderRadius(14)
              .fontSize(16).backgroundColor('#EAF2FF').fontColor('#3478F6')
              .enabled(this.index > 0).opacity(this.index > 0 ? 1 : 0.4)
              .onClick(() => this.move(-1))
            Button('下一个 →', { type: ButtonType.Normal }).layoutWeight(1).height(48).borderRadius(14)
              .fontSize(16).backgroundColor('#3478F6').fontColor(Color.White)
              .enabled(this.index < this.words.length - 1).opacity(this.index < this.words.length - 1 ? 1 : 0.4)
              .onClick(() => this.move(1))
          }.width('100%')
        }.width('100%').padding({ left: 20, right: 20, top: 10, bottom: 24 }).backgroundColor('#F5F7FA')
      }
    }.width('100%').height('100%').backgroundColor('#F5F7FA')
  }
}
