import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { router, promptAction } from '@kit.ArkUI';
import { VideoService } from './VideoService';
import { ApiError } from '../common/HttpClient';

interface PendingMovie { id: number; name: string; userId: number; }

// 应用级监听，不依赖上传页面是否仍在显示。后台通知需另接 Push Kit。
export class MovieCompletionMonitor {
  private static pref: preferences.Preferences | undefined = undefined;
  private static jobs: PendingMovie[] = [];
  private static foreground: boolean = false;
  private static ready: boolean = false;
  private static timer: number = -1;
  private static checking: boolean = false;
  private static generation: number = 0;

  static init(context: common.Context): void {
    try {
      MovieCompletionMonitor.pref = preferences.getPreferencesSync(context, { name: 'pending_movies' });
      const saved = JSON.parse(MovieCompletionMonitor.pref.getSync('jobs', '[]') as string) as PendingMovie[];
      if (Array.isArray(saved)) MovieCompletionMonitor.jobs = saved.filter((job: PendingMovie) =>
        job && Number.isInteger(job.id) && job.id > 0 && Number.isInteger(job.userId) &&
        job.userId > 0 && typeof job.name === 'string');
    } catch { MovieCompletionMonitor.jobs = []; }
  }
  static track(id: number, name: string, userId: number): void {
    if (userId <= 0 || MovieCompletionMonitor.jobs.some((job: PendingMovie) => job.id === id && job.userId === userId)) return;
    MovieCompletionMonitor.jobs.push({ id, name, userId });
    MovieCompletionMonitor.save();
    MovieCompletionMonitor.schedule();
  }
  static setReady(): void { MovieCompletionMonitor.ready = true; MovieCompletionMonitor.schedule(); }
  static setForeground(value: boolean): void {
    MovieCompletionMonitor.foreground = value;
    MovieCompletionMonitor.generation++;
    if (MovieCompletionMonitor.timer !== -1) clearTimeout(MovieCompletionMonitor.timer);
    MovieCompletionMonitor.timer = -1;
    if (value) MovieCompletionMonitor.schedule();
  }
  private static save(): void {
    try {
      MovieCompletionMonitor.pref?.putSync('jobs', JSON.stringify(MovieCompletionMonitor.jobs));
      MovieCompletionMonitor.pref?.flush().catch(() => console.error('Pending movie persistence failed'));
    } catch { console.error('Pending movie persistence failed'); }
  }
  private static remove(job: PendingMovie): void {
    MovieCompletionMonitor.jobs = MovieCompletionMonitor.jobs.filter((item: PendingMovie) =>
      item.id !== job.id || item.userId !== job.userId);
    MovieCompletionMonitor.save();
  }
  private static schedule(): void {
    if (!MovieCompletionMonitor.foreground || MovieCompletionMonitor.timer !== -1) return;
    MovieCompletionMonitor.timer = setTimeout(() => {
      MovieCompletionMonitor.timer = -1;
      MovieCompletionMonitor.check();
    }, 3000);
  }
  private static async check(): Promise<void> {
    if (MovieCompletionMonitor.checking) { MovieCompletionMonitor.schedule(); return; }
    MovieCompletionMonitor.checking = true;
    const token = AppStorage.get<string>('access_token') ?? '';
    const userId = AppStorage.get<number>('user_id') ?? 0;
    const generation = MovieCompletionMonitor.generation;
    try {
      if (!token || !MovieCompletionMonitor.ready || !MovieCompletionMonitor.foreground) return;
      for (const job of [...MovieCompletionMonitor.jobs]) {
        if (job.userId !== userId) continue;
        try {
          const status = await VideoService.status(job.id, token);
          if (!MovieCompletionMonitor.isCurrent(generation, token, userId)) return;
          if (status.status === 'completed') {
            const params = router.getParams() as Record<string, Object>;
            const state = router.getState();
            if (!(state.path?.includes('word_flashcard') && params?.movie_id === job.id)) {
              await router.pushUrl({ url: 'pages/word_flashcard', params: { movie_id: job.id, movie_name: job.name } });
            }
            MovieCompletionMonitor.remove(job);
            // 每轮只跳转一个任务，避免同时完成时连续叠加页面。
            return;
          }
          if (status.status === 'failed') {
            promptAction.showToast({ message: `“${job.name}”处理失败，可在观影记录查看` });
            MovieCompletionMonitor.remove(job);
          }
        } catch (error) {
          if (!MovieCompletionMonitor.isCurrent(generation, token, userId)) return;
          const failure = error as Error;
          if (failure instanceof ApiError && failure.status === 404) MovieCompletionMonitor.remove(job);
          // 断网、过期及页面跳转失败均保留任务，恢复后重试。
        }
      }
    } finally {
      MovieCompletionMonitor.checking = false;
      MovieCompletionMonitor.schedule();
    }
  }
  private static isCurrent(generation: number, token: string, userId: number): boolean {
    return MovieCompletionMonitor.foreground && generation === MovieCompletionMonitor.generation &&
      token === AppStorage.get<string>('access_token') && userId === AppStorage.get<number>('user_id');
  }
}
