import { image } from '@kit.ImageKit';
import { ApiError } from '../common/HttpClient';
import { VideoService } from '../service/VideoService';
import { MovieHistoryItem } from '../models/VideoModels';

@Observed
export class RecentStudyViewModel {
  recent: MovieHistoryItem | undefined = undefined;
  cover: image.PixelMap | undefined = undefined;
  loading: boolean = false;
  error: string = '';
  loginRequired: boolean = false;
  matchedCount: number = -1;
  failureReason: string = '';
  private active: boolean = false;
  private generation: number = 0;
  private timer: number = -1;
  private session: string = '';

  start(): void {
    this.stop();
    this.active = true;
    const token = AppStorage.get<string>('access_token') ?? '';
    if (token !== this.session) {
      this.recent = undefined;
      this.clearCover();
      this.matchedCount = -1;
    }
    this.session = token;
    this.refresh();
  }

  stop(): void {
    this.active = false;
    this.generation++;
    this.cancelTimer();
    this.loading = false;
  }

  dispose(): void {
    this.stop();
    this.clearCover();
  }

  private clearCover(): void {
    const previous = this.cover;
    this.cover = undefined;
    if (previous) previous.release();
  }

  private cancelTimer(): void {
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
  }

  private current(version: number): boolean {
    return this.active && version === this.generation &&
      this.session === (AppStorage.get<string>('access_token') ?? '');
  }

  private handleError(error: Error): void {
    if (error instanceof ApiError && error.status === 401) {
      this.loginRequired = true;
      this.cancelTimer();
      this.recent = undefined;
      this.clearCover();
      this.error = '登录已过期，请重新登录';
    } else {
      this.error = error.message || '暂时无法读取学习记录，请重试';
    }
  }

  async refresh(): Promise<void> {
    if (!this.active || this.loading) return;
    this.cancelTimer();
    const version = ++this.generation;
    this.loginRequired = this.session.length === 0;
    if (this.loginRequired) {
      this.error = '请先登录后查看学习记录';
      return;
    }
    this.loading = true;
    this.error = '';
    try {
      const item = await VideoService.latest(this.session);
      if (!this.current(version)) return;
      const changed = item?.id !== this.recent?.id;
      if (changed) {
        this.clearCover();
        this.matchedCount = -1;
        this.failureReason = '';
      }
      this.recent = item;
      if (item) {
        const status = await VideoService.status(item.id, this.session);
        if (!this.current(version)) return;
        this.recent = { id: item.id, file_name: item.file_name, created_at: item.created_at, status: status.status };
        this.matchedCount = status.status === 'completed' ? status.matched_word_count : -1;
        this.failureReason = status.error_message ?? '';
        if (!this.cover) {
          try {
            const cover = await VideoService.cover(item.id, this.session);
            if (!this.current(version)) { cover.release(); return; }
            this.cover = cover;
          } catch (error) {
            const failure = error as Error;
            if (failure instanceof ApiError && failure.status === 401) throw failure;
            // 封面缺失不影响记录与状态显示，下次刷新仍可重试。
          }
        }
      }
    } catch (error) {
      if (this.current(version)) this.handleError(error as Error);
    } finally {
      if (this.current(version)) {
        this.loading = false;
        if (!this.error && !this.loginRequired) {
          const delay = this.recent?.status === 'pending' || this.recent?.status === 'processing' ? 5000 : 30000;
          this.timer = setTimeout(() => { this.refresh(); }, delay);
        }
      }
    }
  }

  statusText(): string {
    switch (this.recent?.status) {
      case 'pending': return '等待分析';
      case 'processing': return '正在分析';
      case 'completed': return '分析完成';
      case 'failed': return '处理失败';
      default: return '状态待确认';
    }
  }

}
