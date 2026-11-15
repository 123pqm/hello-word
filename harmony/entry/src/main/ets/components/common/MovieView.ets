import { router } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { ApiError } from '../../common/HttpClient';
import { VideoService } from '../../service/VideoService';
import { MovieHistoryItem } from '../../models/VideoModels';
import { FlashcardSettingsStore } from '../../storage/FlashcardSettingsStore';

@Component
struct MovieCover {
  @Prop movieId: number = 0;
  @Prop @Watch('reload') token: string = '';
  @State cover: image.PixelMap | undefined = undefined;
  private version: number = 0;
  private mounted: boolean = false;

  aboutToAppear(): void { this.mounted = true; this.reload(); }
  aboutToDisappear(): void {
    this.mounted = false;
    this.version++;
    this.cover?.release();
    this.cover = undefined;
  }
  reload(): void {
    const version = ++this.version;
    this.cover?.release();
    this.cover = undefined;
    if (!this.mounted || !this.token) return;
    VideoService.cover(this.movieId, this.token).then((cover: image.PixelMap) => {
      if (!this.mounted || version !== this.version) { cover.release(); return; }
      this.cover = cover;
    }).catch(() => {}); // Missing covers use the placeholder; the movie remains readable.
  }
  build() {
    Stack() {
      if (this.cover) {
        Image(this.cover).width('100%').height('100%').objectFit(ImageFit.Cover)
      } else {
        Image($r('app.media.android_ic_video')).width(30).height(30).fillColor('#8BA7D4')
      }
    }.width(76).height(98).backgroundColor('#EAF0F8').borderRadius(8).clip(true)
  }
}

@Component
export struct MovieView {
  @Prop @Watch('visibilityChanged') active: boolean = true;
  onBack: () => void = () => {};
  @StorageLink('access_token') @Watch('sessionChanged') token: string = '';
  @State items: MovieHistoryItem[] = [];
  @State loading: boolean = false;
  @State refreshing: boolean = false;
  @State error: string = '';
  @State loginRequired: boolean = false;
  @State nextBeforeId: number | null = null;
  @State deletingId: number = 0;
  @State quizMode: boolean = false;
  private mounted: boolean = false;
  private version: number = 0;
  private failedAppend: boolean = false;

  aboutToAppear(): void {
    this.mounted = true;
    this.sessionChanged();
    const context = this.getUIContext().getHostContext();
    if (context) {
      FlashcardSettingsStore.load(context as common.Context).then((settings) => {
        if (this.mounted) this.quizMode = settings.quizMode;
      });
    }
  }
  aboutToDisappear(): void { this.mounted = false; this.version++; }
  visibilityChanged(): void {
    if (!this.mounted) return;
    if (this.active) this.load(false);
    else { this.version++; this.loading = false; this.refreshing = false; this.deletingId = 0; }
  }
  sessionChanged(): void {
    this.version++;
    this.items = [];
    this.nextBeforeId = null;
    this.loading = false;
    this.refreshing = false;
    this.deletingId = 0;
    this.error = '';
    this.loginRequired = !this.token;
    if (this.mounted && this.active && this.token) this.load(false);
  }
  private current(version: number, token: string): boolean {
    return this.mounted && this.active && version === this.version && token === this.token;
  }
  private showError(error: Error): void {
    if (error instanceof ApiError && error.status === 401) {
      this.loginRequired = true;
      this.items = [];
      this.nextBeforeId = null;
      this.error = '登录已过期，请重新登录';
    } else { this.error = error.message || '请求失败，请重试'; }
  }
  private async load(append: boolean): Promise<void> {
    if (this.loading || this.deletingId !== 0 || !this.active || this.loginRequired || !this.token ||
      (append && this.nextBeforeId === null)) { this.refreshing = false; return; }
    const version = ++this.version;
    const token = this.token;
    this.loading = true;
    this.error = '';
    this.failedAppend = append;
    try {
      const page = await VideoService.history(token, append ? this.nextBeforeId : null);
      if (!this.current(version, token)) return;
      const incoming: MovieHistoryItem[] = [];
      for (const item of page.items) {
        if (!incoming.some((row: MovieHistoryItem) => row.id === item.id) &&
          (!append || !this.items.some((row: MovieHistoryItem) => row.id === item.id))) incoming.push(item);
      }
      this.items = append ? [...this.items, ...incoming] : incoming;
      this.nextBeforeId = page.next_before_id;
    } catch (error) {
      if (this.current(version, token)) this.showError(error as Error);
    } finally {
      if (this.current(version, token)) { this.loading = false; this.refreshing = false; }
    }
  }
  private confirmDelete(item: MovieHistoryItem): void {
    const token = this.token;
    const version = this.version;
    this.getUIContext().showAlertDialog({
      title: '删除电影',
      message: `删除“${item.file_name || '未命名视频'}”及其学习记录？不会删除手机原视频。`,
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: { value: '删除', action: () => {
        if (this.current(version, token)) this.remove(item.id);
      } }
    });
  }
  private async remove(movieId: number): Promise<void> {
    if (this.deletingId !== 0 || this.loading || this.loginRequired) return;
    const version = ++this.version;
    const token = this.token;
    this.deletingId = movieId;
    this.error = '';
    try {
      await VideoService.deleteMovie(movieId, token);
      if (this.current(version, token)) this.items = this.items.filter((item: MovieHistoryItem) => item.id !== movieId);
    } catch (error) {
      if (!this.current(version, token)) return;
      const failure = error as Error;
      if (failure instanceof ApiError && failure.status === 404) {
        this.items = this.items.filter((item: MovieHistoryItem) => item.id !== movieId);
      } else {
        this.showError(failure);
        this.getUIContext().getPromptAction().showToast({ message: this.error });
      }
    } finally {
      if (this.current(version, token)) this.deletingId = 0;
    }
  }
  private statusText(status: string): string {
    if (status === 'completed') return '分析完成';
    if (status === 'pending') return '等待分析';
    if (status === 'processing') return '正在分析…';
    if (status === 'failed') return '分析失败';
    return '状态未知，请下拉刷新';
  }
  @Builder
  MovieCard(item: MovieHistoryItem) {
    Row({ space: 12 }) {
      Row({ space: 12 }) {
      MovieCover({ movieId: item.id, token: this.token })
      Column({ space: 8 }) {
        Text(item.file_name || '未命名视频').fontSize(16).fontWeight(FontWeight.Bold)
          .fontColor('#242933').maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text((item.created_at || '').replace('T', ' ').slice(0, 19) || '上传时间未知')
          .fontSize(12).fontColor('#969CA6')
        Text(this.statusText(item.status)).fontSize(13)
          .fontColor(item.status === 'failed' ? '#C65D64' : '#4779CE')
        if (item.status === 'completed') {
          Text('点击学习单词闪卡').fontSize(11).fontColor('#969CA6')
        }
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      }.layoutWeight(1)
      .onClick(() => {
        if (this.deletingId !== 0 || this.loginRequired) return;
        const url: string = this.quizMode ? 'pages/question_flashcard' : 'pages/word_flashcard';
        router.pushUrl({ url: url, params: { movie_id: item.id, movie_name: item.file_name } });
      })
      Button(this.deletingId === item.id ? '删除中' : '删除', { type: ButtonType.Normal })
        .fontSize(13).fontColor('#C56A75').backgroundColor(Color.Transparent).padding(4)
        .enabled(!this.loading && this.deletingId === 0)
        .onClick(() => this.confirmDelete(item))
    }.width('100%').padding(14).borderRadius(14).backgroundColor(Color.White)
      .border({ width: 1, color: '#E9ECF1' })
      .shadow({ radius: 5, color: '#09000000', offsetY: 2 })

  }
  build() {
    Column() {
      Row() {
        Button('‹', { type: ButtonType.Normal }).width(44).fontSize(30)
          .backgroundColor(Color.Transparent).fontColor('#252A32').onClick(() => this.onBack())
        Text('我的观影').fontSize(20).fontWeight(FontWeight.Bold).fontColor('#1A1A1A')
          .layoutWeight(1).textAlign(TextAlign.Center)
        Column().width(44)
      }.width('100%').height(56)
      Column({ space: 6 }) {
        Text('已上传的电影').fontSize(20).fontWeight(FontWeight.Bold).fontColor('#252A32')
        Text(`已加载 ${this.items.length} 部 · 在顶部下拉刷新`).fontSize(13).fontColor('#969CA6')
      }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: 20, right: 20, top: 20, bottom: 18 })
      if (this.loginRequired) {
        Column({ space: 12 }) {
          Text(this.error || '请先登录后查看观影记录').fontColor('#8A8F99')
          Button('重新登录').onClick(() => router.replaceUrl({ url: 'pages/Index' }))
        }.layoutWeight(1).justifyContent(FlexAlign.Center)
      } else {
        Refresh({ refreshing: $$this.refreshing }) {
          List({ space: 14 }) {
            if (this.items.length === 0) {
              ListItem() {
                Column({ space: 12 }) {
                  if (this.loading) {
                    LoadingProgress().width(30).height(30)
                    Text('正在加载电影…').fontColor('#8A8F99')
                  } else if (!this.error) {
                    Text('还没有上传电影').fontSize(16).fontColor('#687385')
                    Text('上传后可在这里查看处理状态').fontSize(13).fontColor('#969CA6')
                  }
                }.width('100%').padding({ top: 60, bottom: 40 })
              }
            }
            ForEach(this.items, (item: MovieHistoryItem) => {
              ListItem() { this.MovieCard(item) }
            }, (item: MovieHistoryItem) => `${item.id}`)
            if (this.error) {
              ListItem() {
                Column({ space: 10 }) {
                  Text(this.error).fontColor('#C65D64').fontSize(13)
                  Button('重新加载').enabled(!this.loading && this.deletingId === 0)
                    .onClick(() => this.load(this.failedAppend))
                }.width('100%').padding(16)
              }
            }
            if (this.nextBeforeId !== null && this.items.length > 0 && !this.error) {
              ListItem() {
                Button(this.loading ? '加载中…' : '加载更多').width('100%')
                  .fontColor('#4779CE').backgroundColor('#EAF0FA')
                  .enabled(!this.loading && this.deletingId === 0).onClick(() => this.load(true))
              }
            }
          }.width('100%').height('100%').padding({ left: 18, right: 18, bottom: 24 })
            .edgeEffect(EdgeEffect.Spring, { alwaysEnabled: true })
        }.layoutWeight(1).onRefreshing(() => this.load(false))
      }
    }.width('100%').height('100%').backgroundColor('#F5F7FA')
  }
}
