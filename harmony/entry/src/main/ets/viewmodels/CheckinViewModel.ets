import { ApiError } from '../common/HttpClient';
import { UserService } from '../service/UserService';
import { CheckinData, CheckinDay } from '../models/UserModels';

@Observed
export class CheckinViewModel {
  data: CheckinData | null = null;
  loading: boolean = false;
  error: string = '';
  loginRequired: boolean = false;
  private active: boolean = false;
  private version: number = 0;

  load(): Promise<void> {
    if (this.loading || !this.active) return Promise.resolve();
    const version = ++this.version;
    const token = AppStorage.get<string>('access_token') ?? '';
    if (token.length === 0) {
      this.loginRequired = true;
      this.error = '请先登录后查看打卡';
      return Promise.resolve();
    }
    this.loginRequired = false;
    this.loading = true;
    this.error = '';
    return UserService.getCheckin(token)
      .then((result: CheckinData) => {
        if (!this.current(version)) return;
        this.data = result;
      })
      .catch((error: Error) => {
        if (!this.current(version)) return;
        if (error instanceof ApiError && error.status === 401) {
          this.loginRequired = true;
          this.error = '登录已过期，请重新登录';
        } else {
          this.error = error.message || '加载打卡数据失败，请重试';
        }
      })
      .finally(() => {
        if (this.current(version)) this.loading = false;
      });
  }

  start(): void {
    this.active = true;
    this.load();
  }

  stop(): void {
    this.active = false;
    this.version++;
    this.loading = false;
  }

  dispose(): void {
    this.stop();
  }

  private current(version: number): boolean {
    return this.active && version === this.version;
  }
}