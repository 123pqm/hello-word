import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { MovieWord } from '../models/FlashcardModels';
import { QuestionOption } from '../models/QuizModels';

export interface QuizProgress {
  runId: string;
  words: MovieWord[];
  total: number;
  index: number;
  correct: number;
  completed: number;
  skipped: number;
  finished: boolean;
  questionId: string;
  questionAt: number;
  options: QuestionOption[];
  answered: boolean;
  selected: number;
  correctIndex: number;
  pending: boolean;
}
export class QuizProgressStore {
  private static queue: Promise<void> = Promise.resolve();
  private static async readRaw(pref: preferences.Preferences, key: string): Promise<string> {
    const marker = await pref.get(key, '') as string;
    if (!marker.startsWith('chunks:')) return marker;
    const chunks = Number(marker.slice(7));
    let raw = '';
    for (let i = 0; i < chunks; i++) raw += await pref.get(`${key}_${i}`, '') as string;
    return raw;
  }
  static key(userId: number, review: boolean, movieId: number): string {
    return `v1_user_${userId}_${review ? 'review' : 'movie_' + movieId}`;
  }
  static async load(context: common.Context, key: string): Promise<QuizProgress | undefined> {
    await QuizProgressStore.queue;
    const pref = await preferences.getPreferences(context, 'quiz_progress');
    const raw = await QuizProgressStore.readRaw(pref, key);
    if (!raw) return undefined;
    const data = JSON.parse(raw) as QuizProgress;
    if (!Array.isArray(data.words) || !Array.isArray(data.options) || !Number.isInteger(data.index) ||
      data.index < 0 || data.total < 0 || data.index > data.total || data.completed < 0) throw new Error('答题进度读取失败');
    return data;
  }
  static save(context: common.Context, key: string, data: QuizProgress): Promise<void> {
    const raw = JSON.stringify(data);
    const task = QuizProgressStore.queue.then(async () => {
      const pref = await preferences.getPreferences(context, 'quiz_progress');
      if ((await pref.get(key + '_closed', '')) === data.runId) return;
      const previousRaw = await QuizProgressStore.readRaw(pref, key);
      if (previousRaw) {
        const previous = JSON.parse(previousRaw) as QuizProgress;
        if (previous.runId === data.runId && (previous.completed > data.completed || previous.index > data.index ||
          (previous.finished && !data.finished))) return;
      }
      const chunks = Math.ceil(raw.length / 1000);
      for (let i = 0; i < chunks; i++) await pref.put(`${key}_${i}`, raw.slice(i * 1000, (i + 1) * 1000));
      await pref.put(key, `chunks:${chunks}`);
      await pref.flush();
    });
    QuizProgressStore.queue = task.catch(() => {});
    return task;
  }
  static clear(context: common.Context, key: string): Promise<void> {
    const task = QuizProgressStore.queue.then(async () => {
      const pref = await preferences.getPreferences(context, 'quiz_progress');
      const raw = await QuizProgressStore.readRaw(pref, key);
      if (raw) await pref.put(key + '_closed', (JSON.parse(raw) as QuizProgress).runId);
      const marker = await pref.get(key, '') as string;
      const chunks = marker.startsWith('chunks:') ? Number(marker.slice(7)) : 0;
      for (let i = 0; i < chunks; i++) await pref.delete(`${key}_${i}`);
      await pref.delete(key);
      await pref.flush();
    });
    QuizProgressStore.queue = task.catch(() => {});
    return task;
  }
}
