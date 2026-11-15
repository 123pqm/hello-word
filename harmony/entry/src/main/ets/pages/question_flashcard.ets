import { QuizProgress, QuizProgressStore } from '../storage/QuizProgressStore';
import { ReviewService } from '../service/ReviewService';
import { FlashcardSettingsStore } from '../storage/FlashcardSettingsStore';
import { router } from '@kit.ArkUI';
import { MovieWord } from '../models/FlashcardModels';
import { QuestionFlashcard } from '../components/common/question_flashcard';
import { QuizService } from '../service/QuizService';
import { QuestionOption, QuizQuestion } from '../models/QuizModels';
import { ApiError } from '../common/HttpClient';

interface QuizParams {
  movie_id: number;
  review?: boolean;
  movie_name?: string;
}


@Entry
@Component
struct QuestionFlashcardPage {
  @State private reviewMode: boolean = false;
  @State private reviewSaveError: string = '';
  @State private correctCount: number = 0;
  @State private completedCount: number = 0;
  @State private skippedCount: number = 0;
  private progressKey: string = '';
  private runId: string = '';
  private questionAt: number = 0;
  private pendingAnswer: boolean = false;
  private autoAddWrong: boolean = false;
  @State private movieId: number = 0;
  @State private movieName: string = '';
  @State private words: MovieWord[] = [];
  @State private currentIndex: number = 0;
  @State private loading: boolean = false;
  @State private error: string = '';
  @State private loginRequired: boolean = false;
  @State private currentOptions: QuestionOption[] = [];
  @State private currentCorrectIndex: number = 0;
  @StorageLink('access_token') @Watch('sessionChanged') token: string = '';
  @State private questionId: string = '';
  @State private answered: boolean = false;
  @State private submitting: boolean = false;
  @State private selectedIndex: number = -1;
  @State private answerError: string = '';
  @State private finished: boolean = false;
  @State private total: number = 0;
  private generation: number = 0;
  private pageRequest: Promise<void> | undefined = undefined;
  private pending: Map<number, Promise<QuizQuestion>> = new Map();
  private cached: Map<number, QuizQuestion> = new Map();
  private cachedAt: Map<number, number> = new Map();
  private warming: boolean = false;
  private version: number = 0;
  private mounted: boolean = false;

  aboutToAppear(): void {
    this.mounted = true;
    const params = router.getParams() as QuizParams;
    if (params) {
      this.reviewMode = params.review ?? false;
      this.movieId = params.movie_id ?? 0;
      this.movieName = params.movie_name ?? '';
    }
    this.loadWords();
  }

  aboutToDisappear(): void {
    this.mounted = false;
    this.generation++;
    this.version++;
  }
  onPageHide(): void {
    this.mounted = false;
    this.generation++;
    this.version++;
    this.loading = true;
  }
  onPageShow(): void {
    if (!this.mounted) { this.mounted = true; this.loadWords(); }
  }

  sessionChanged(): void {
    if (this.mounted) this.loadWords();
  }

  private async loadWords(): Promise<void> {
    const version = ++this.version;
    const token = this.token;
    this.generation++;
    this.pageRequest = undefined;
    this.pending.clear();
    this.cached.clear();
    this.cachedAt.clear();
    this.warming = false;
    this.words = [];
    this.total = 0;
    this.loginRequired = false;
    this.finished = false;
    this.runId = `${Date.now()}_${Math.random()}`;
    this.correctCount = 0;
    this.completedCount = 0;
    this.skippedCount = 0;
    this.currentIndex = 0;
    this.submitting = false;
    this.pendingAnswer = false;
    if (!this.token) {
      this.loginRequired = true;
      this.error = '请先登录后查看闪卡';
      return;
    }
    if (!this.reviewMode && (!Number.isInteger(this.movieId) || this.movieId <= 0)) {
      this.error = '电影编号无效，请返回重新进入';
      return;
    }
    this.loading = true;
    this.error = '';
    try {
      const context = this.getUIContext().getHostContext();
      this.autoAddWrong = context ? (await FlashcardSettingsStore.load(context)).autoAddWrongToReview : false;
      if (!this.mounted || version !== this.version || token !== this.token) return;
      if (!context) throw new Error('无法打开本地进度存储');
      const userId = AppStorage.get<number>('user_id') ?? 0;
      if (userId <= 0) throw new Error('请重新登录后继续答题');
      this.progressKey = QuizProgressStore.key(userId, this.reviewMode, this.movieId);
      const saved = await QuizProgressStore.load(context, this.progressKey);
      if (!this.mounted || version !== this.version || token !== this.token) return;
      if (saved) {
        this.restoreProgress(saved);
        if (this.finished) return;
        if (this.answered) { this.prefetch(); return; }
        if (saved.pending && saved.questionId) {
          await this.selectOption(saved.selected);
          return;
        }
        if (saved.questionId && Date.now() - saved.questionAt < 25 * 60 * 1000 && saved.options.length === 4) {
          this.prefetch(); return;
        }
        await this.prepareQuestion();
        return;
      }
      await this.loadPage(this.generation);
      if (!this.mounted || version !== this.version || token !== this.token) return;
      this.currentIndex = 0;
      await this.prepareQuestion();
    } catch (error) {
      if (!this.mounted || version !== this.version || token !== this.token) return;
      const failure = error as Error;
      if (failure instanceof ApiError && failure.status === 401) {
        this.loginRequired = true;
        this.error = '登录已过期，请重新登录';
      } else {
        this.error = failure.message || '加载失败，请重试';
      }
    } finally {
      if (this.mounted && version === this.version) this.loading = false;
    }
  }

  private snapshot(): QuizProgress {
    return { runId: this.runId, words: this.words, total: this.total, index: this.currentIndex,
      correct: this.correctCount, completed: this.completedCount, skipped: this.skippedCount,
      finished: this.finished, questionId: this.questionId, questionAt: this.questionAt,
      options: this.currentOptions, answered: this.answered, selected: this.selectedIndex,
      correctIndex: this.currentCorrectIndex, pending: this.pendingAnswer };
  }

  private restoreProgress(saved: QuizProgress): void {
    this.runId = saved.runId;
    this.words = saved.words; this.total = saved.total; this.currentIndex = saved.index;
    this.correctCount = saved.correct; this.completedCount = saved.completed; this.skippedCount = saved.skipped;
    this.finished = saved.finished; this.questionId = saved.questionId; this.questionAt = saved.questionAt;
    this.currentOptions = saved.options; this.answered = saved.answered; this.selectedIndex = saved.selected;
    this.currentCorrectIndex = saved.correctIndex; this.pendingAnswer = saved.pending;
  }

  private async persist(): Promise<void> {
    const context = this.getUIContext().getHostContext();
    if (!context || !this.progressKey) throw new Error('无法保存答题进度');
    await QuizProgressStore.save(context, this.progressKey, this.snapshot());
  }

  private async confirmResult(): Promise<void> {
    if (this.submitting) return;
    this.submitting = true;
    try {
      const context = this.getUIContext().getHostContext();
      if (!context) throw new Error('无法清除答题进度');
      await QuizProgressStore.clear(context, this.progressKey);
      router.back();
    } catch (error) { this.answerError = (error as Error).message; }
    finally { this.submitting = false; }
  }

  private async loadPage(generation: number): Promise<void> {
    if (this.pageRequest) return this.pageRequest;
    const request = this.fetchPage(generation);
    this.pageRequest = request;
    try { await request; }
    finally { if (generation === this.generation) this.pageRequest = undefined; }
  }

  private async fetchPage(generation: number): Promise<void> {
    const afterId = this.words.length > 0 ? (this.words[this.words.length - 1].id ?? 0) : 0;
    const result = this.reviewMode
      ? await ReviewService.list(this.token, afterId, true, 5)
      : await QuizService.words(this.movieId, this.words.length, this.token);
    if (!this.mounted || generation !== this.generation) return;
    if (!this.reviewMode || this.words.length === 0) this.total = result.total;
    this.words = this.words.concat(result.words);
    if (this.reviewMode && result.words.length < 5) this.total = this.words.length;
  }

  private async getQuestion(index: number, generation: number): Promise<QuizQuestion> {
    if (index >= this.words.length) await this.loadPage(generation);
    if (!this.mounted || generation !== this.generation) throw new Error('Page closed');
    const id = this.words[index]?.id;
    if (!id) throw new Error('单词数据无效，请重新进入答题页');
    const cached = this.cached.get(id);
    if (cached && Date.now() - (this.cachedAt.get(id) ?? 0) < 25 * 60 * 1000) return cached;
    const existing = this.pending.get(id);
    if (existing) return existing;
    const request = this.reviewMode ? ReviewService.question(id, this.token) : QuizService.question(this.movieId, id, this.token);
    this.pending.set(id, request);
    try {
      const question = await request;
      if (this.mounted && generation === this.generation && index >= this.currentIndex) {
        this.cached.set(id, question);
        this.cachedAt.set(id, Date.now());
      }
      return question;
    } finally {
      if (generation === this.generation) this.pending.delete(id);
    }
  }

  private async prefetch(): Promise<void> {
    if (this.warming) return;
    this.warming = true;
    const generation = this.generation;
    try {
      let index = this.currentIndex + 1;
      while (this.mounted && generation === this.generation &&
        index < this.total && index <= this.currentIndex + 4) {
        try { await this.getQuestion(index, generation); } catch (_) { /* Retry on opening. */ }
        index = Math.max(index + 1, this.currentIndex + 1);
      }
    } finally {
      if (generation === this.generation) this.warming = false;
    }
  }

  private async prepareQuestion(): Promise<void> {
    if (!this.mounted || this.total === 0) return;
    const version = ++this.version;
    const token = this.token;
    const currentId = this.words[this.currentIndex]?.id ?? 0;
    this.loading = !this.cached.has(currentId) || Date.now() - (this.cachedAt.get(currentId) ?? 0) >= 25 * 60 * 1000;
    this.error = '';
    this.answered = false;
    this.submitting = false;
    this.selectedIndex = -1;
    this.currentCorrectIndex = -1;
    this.answerError = '';
    this.reviewSaveError = '';
    this.currentOptions = [];
    this.questionId = '';
    this.pendingAnswer = false;
    try {
      const question = await this.getQuestion(this.currentIndex, this.generation);
      if (!this.mounted || version !== this.version || token !== this.token) return;
      this.questionId = question.id;
      this.currentOptions = question.options;
      this.questionAt = Date.now();
      await this.persist();
      this.prefetch();
    } catch (error) {
      if (this.mounted && version === this.version) this.error = (error as Error).message || '出题失败';
    } finally {
      if (this.mounted && version === this.version) this.loading = false;
    }
  }

  private async selectOption(index: number): Promise<void> {
    if (this.answered || this.submitting || index < 0 || index > 3) return;
    const version = this.version;
    const token = this.token;
    this.selectedIndex = index;
    this.submitting = true;
    this.answerError = '';
    try {
      this.pendingAnswer = true;
      const context = this.getUIContext().getHostContext();
      if (!context) throw new Error('无法保存答题进度');
      const key = this.progressKey;
      const progress = this.snapshot();
      await QuizProgressStore.save(context, key, progress);
      const result = this.reviewMode ? await ReviewService.answer(progress.questionId, index, token) : await QuizService.answer(progress.questionId, index, token);
      progress.options = result.options;
      progress.correctIndex = result.correct_index;
      progress.selected = result.selected_index;
      progress.answered = true;
      progress.pending = false;
      progress.completed++;
      if (result.selected_index === result.correct_index) progress.correct++;
      // Save receipts even if the user left while the request was in flight.
      await QuizProgressStore.save(context, key, progress);
      if (!this.mounted || version !== this.version || token !== this.token) return;
      this.restoreProgress(progress);
      if (!this.reviewMode && this.autoAddWrong && !result.correct) await this.saveWrong();
    } catch (error) {
      if (this.mounted && version === this.version) {
        const failure = error as Error;
        if (failure instanceof ApiError && failure.status === 410) { this.pendingAnswer = false; this.cached.delete(this.words[this.currentIndex].id ?? 0); this.error = '题目已过期，请重新加载本题'; }
        else this.answerError = failure.message || '提交失败，请重试';
      }
    } finally {
      if (this.mounted && version === this.version) this.submitting = false;
    }
  }

  private async saveWrong(): Promise<void> {
    const version = this.version;
    const token = this.token;
    this.reviewSaveError = '';
    try { await ReviewService.add(this.questionId, token); }
    catch (error) {
      if (this.mounted && version === this.version && token === this.token) this.reviewSaveError = '错题保存失败：' + (error as Error).message;
    }
  }

  private async nextWord(): Promise<void> {
    this.error = '';
    if (this.loading || this.submitting) return;
    if (this.pendingAnswer) { this.answerError = '请先重试提交当前答案，确认后再继续'; return; }
    this.submitting = true;
    if (!this.answered) this.skippedCount++;
    const previousId = this.words[this.currentIndex]?.id ?? 0;
    this.cached.delete(previousId);
    this.cachedAt.delete(previousId);
    try {
      if (this.currentIndex < this.total - 1) {
        this.currentIndex++;
        this.questionId = ''; this.currentOptions = []; this.answered = false; this.selectedIndex = -1;
        await this.persist();
        await this.prepareQuestion();
      } else {
        this.finished = true;
        await this.persist();
      }
    } catch (error) { this.error = (error as Error).message; }
    finally { this.submitting = false; }
  }

  build() {
    Column() {
      Row() {
        Image($r('app.media.ic_chevron_left_gray'))
          .width(24)
          .height(24)
          .onClick(() => router.back())
        Text(this.reviewMode ? '错题复习' : (this.movieName || '答题学习'))
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor('#1A1A1A')
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
        Text(`${this.words.length > 0 ? this.currentIndex + 1 : 0}/${this.total}`)
          .fontSize(13)
          .fontColor('#8E9AA0')
      }
      .width('100%')
      .height(56)
      .padding({ left: 16, right: 16 })
      .alignItems(VerticalAlign.Center)
      .backgroundColor('#F5F7FA')

      if (this.loginRequired) {
        Column({ space: 12 }) {
          Text(this.error || '请先登录').fontColor('#8E9AA0')
          Button('重新登录').onClick(() => router.replaceUrl({ url: 'pages/Index' }))
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else if (this.loading) {
        Column({ space: 12 }) {
          LoadingProgress().width(40).height(40)
          Text('正在加载单词…').fontSize(14).fontColor('#8E9AA0')
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else if (this.error) {
        Column({ space: 12 }) {
          Text(this.error).fontSize(14).fontColor('#C62828')
          Button('重试').onClick(() => { if (this.words.length > 0) this.prepareQuestion(); else this.loadWords(); })
          if (this.words.length > 0) { Button('跳过本题').onClick(() => this.nextWord()) }
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else if (this.finished) {
        Column({ space: 16 }) {
          Text('✓').fontSize(56).fontColor('#36A678')
          Text('本轮练习已完成').fontSize(24).fontWeight(FontWeight.Bold).fontColor('#253858')
          Text(`${this.correctCount} / ${this.completedCount}`).fontSize(48).fontWeight(FontWeight.Bold).fontColor('#3478F6')
          Text('答对题数 / 已答题数').fontSize(14).fontColor('#64748B')
          Text(`答错 ${this.completedCount - this.correctCount} 题 · 跳过 ${this.skippedCount} 题`).fontSize(16).fontColor('#64748B')
          Text('进度已保存，点击确定结束本轮').fontSize(13).fontColor('#94A3B8')
          if (this.answerError) { Text(this.answerError).fontColor('#C44B43') }
          Button('确定').width('75%').height(48).backgroundColor('#3478F6')
            .enabled(!this.submitting).onClick(() => this.confirmResult())
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else if (this.words.length > 0) {
        QuestionFlashcard({
          word: this.words[this.currentIndex].word,
          sentence: this.words[this.currentIndex].sentence_text ?? '',
          questionId: this.questionId,
          movieId: this.reviewMode ? 0 : this.movieId,
          reviewSaveError: this.reviewSaveError,
          onRetrySave: () => this.saveWrong(),
          cardId: this.words[this.currentIndex].id ?? 0,
          startTime: this.words[this.currentIndex].start_time,
          endTime: this.words[this.currentIndex].end_time,
          answered: this.answered,
          selectedIndex: this.selectedIndex,
          submitting: this.submitting,
          answerError: this.answerError,
          lastQuestion: this.currentIndex === this.total - 1,
          onSelect: (index: number) => this.selectOption(index),
          options: this.currentOptions,
          correctIndex: this.currentCorrectIndex,
          onNext: () => this.nextWord()
        }).layoutWeight(1)
      } else {
        Column() {
          Text(this.reviewMode ? '今天的复习已完成，明天再来看看' : '暂无单词数据').fontSize(14).fontColor('#8E9AA0')
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      }
    }
    .width('100%')
    .height('100%')
  }
}
