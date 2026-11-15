import { ApiError } from '../common/HttpClient';
import { WordbookService } from '../service/WordbookService';
import { WordSuggestionResponse } from '../models/WordbookModels';
import { WordbookWord, WordbookWordListResponse, WordSearchResponse, FavoritePage, FavoriteWord } from '../models/WordbookModels';

@Observed
export class WordListViewModel {
  words: WordbookWord[] = [];
  total: number = 0;
  loading: boolean = false;
  error: string = '';
  loginRequired: boolean = false;
  loadingMore: boolean = false;
  hasMore: boolean = true;
  loadMoreError: string = '';
  isSearchMode: boolean = false;
  searchWords: WordbookWord[] = [];
  isSearching: boolean = false;
  searchError: string = '';
  searchHasMore: boolean = false;
  suggestions: string[] = [];
  suggesting: boolean = false;
  suggestionError: string = '';
  isFavoritesMode: boolean = false;
  private searchOffset: number = 0;
  private searchKeyword: string = '';
  private searchVersion: number = 0;
  private timer: number = -1;
  private pageSize: number = 16;
  private nextStartIndex: number = 1;
  private apiBookId: number = 1;
  private active: boolean = false;
  private generation: number = 0;
  private nextBeforeId: number | null = null;

  private static mapBookId(pageBookId: number): number {
    if (pageBookId === 0) return 1;
    if (pageBookId === 1) return 3;
    if (pageBookId === 2) return 4;
    return 1;
  }

  loadInitial(pageBookId: number): Promise<void> {
    this.stop();
    this.exitSearch();
    this.active = true;
    const version = ++this.generation;
    const token = AppStorage.get<string>('access_token') ?? '';
    if (token.length === 0) {
      this.loginRequired = true;
      this.error = '请先登录后查看词条';
      return Promise.resolve();
    }
    this.loginRequired = false;
    this.loading = true;
    this.error = '';
    this.words = [];
    this.total = 0;
    this.nextStartIndex = 1;
    this.hasMore = true;
    this.loadingMore = false;
    this.loadMoreError = '';
    this.apiBookId = WordListViewModel.mapBookId(pageBookId);
    return this.fetchPage(token, version, true);
  }

  loadMore(): Promise<void> {
    if (this.isFavoritesMode) return this.loadMoreFavorites();
    if (this.loadingMore || !this.hasMore || !this.active || this.loading || this.loginRequired || this.isSearchMode) {
      return Promise.resolve();
    }
    const token = AppStorage.get<string>('access_token') ?? '';
    if (token.length === 0) {
      this.loginRequired = true;
      return Promise.resolve();
    }
    this.loadingMore = true;
    this.loadMoreError = '';
    const version = this.generation;
    return this.fetchPage(token, version, false);
  }

  private fetchPage(token: string, version: number, isInitial: boolean): Promise<void> {
    const endIndex: number = this.total > 0
      ? Math.min(this.nextStartIndex + this.pageSize - 1, this.total)
      : this.nextStartIndex + this.pageSize - 1;
    return WordbookService.getWords(this.apiBookId, this.nextStartIndex, endIndex, token)
      .then((result: WordbookWordListResponse) => {
        if (!this.current(version)) return;
        if (result.total > 0) this.total = result.total;
        const newWords: WordbookWord[] = this.deduplicate(result.data);
        if (isInitial) {
          this.words = newWords;
        } else {
          this.words = [...this.words, ...newWords];
        }
        this.nextStartIndex = this.nextStartIndex + result.data.length;
        if (this.total > 0) {
          this.hasMore = this.words.length < this.total;
        } else {
          this.hasMore = result.data.length >= this.pageSize;
        }
      })
      .catch((error: Error) => {
        if (!this.current(version)) return;
        if (error instanceof ApiError && error.status === 401) {
          this.loginRequired = true;
          if (isInitial) this.error = '登录已过期，请重新登录';
        } else {
          if (isInitial) {
            this.error = error.message || '暂时无法加载词条，请重试';
          } else {
            this.loadMoreError = error.message || '加载更多失败，请重试';
          }
        }
      })
      .finally(() => {
        if (!this.current(version)) return;
        if (isInitial) this.loading = false;
        else this.loadingMore = false;
      });
  }

  private deduplicate(newItems: WordbookWord[]): WordbookWord[] {
    const result: WordbookWord[] = [];
    for (let i = 0; i < newItems.length; i++) {
      let found: boolean = false;
      for (let j = 0; j < this.words.length; j++) {
        if (this.words[j].id === newItems[i].id) {
          found = true;
          break;
        }
      }
      if (!found) result.push(newItems[i]);
    }
    return result;
  }

  onSearchInput(value: string): void {
    this.exitSearch();
    const keyword: string = value.trim();
    if (!keyword || !this.active) return;
    this.searchKeyword = keyword;
    this.isSearchMode = true;
    this.isSearching = true;
    this.timer = setTimeout(() => {
      this.timer = -1;
      this.executeSearch();
    }, 400);
  }

  exitSearch(): void {
    this.suggestions = [];
    this.suggesting = false;
    this.suggestionError = '';
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
    this.searchVersion++;
    this.searchWords = [];
    this.searchOffset = 0;
    this.searchKeyword = '';
    this.isSearchMode = false;
    this.isSearching = false;
    this.searchHasMore = false;
    this.searchError = '';
  }

  retrySearch(): void { if (!this.isSearching) this.executeSearch(); }
  loadMoreSearch(): void {
    if (!this.isSearching && this.searchHasMore && !this.searchError) this.executeSearch();
  }

  private executeSearch(): void {
    if (!this.active || !this.isSearchMode) return;
    const token: string = AppStorage.get<string>('access_token') ?? '';
    if (!token) {
      this.loginRequired = true;
      this.error = '请重新登录';
      this.isSearching = false;
      return;
    }
    const version: number = ++this.searchVersion;
    this.isSearching = true;
    this.searchError = '';
    WordbookService.searchWords(this.apiBookId, this.searchKeyword, this.searchOffset, 50, token)
      .then((result: WordSearchResponse) => {
        if (!this.active || version !== this.searchVersion || token !== AppStorage.get<string>('access_token')) return;
        const incoming: WordbookWord[] = result.data.filter((item: WordbookWord) =>
          !this.searchWords.some((existing: WordbookWord) => existing.id === item.id));
        this.searchWords = [...this.searchWords, ...incoming];
        this.searchHasMore = result.has_more;
        this.searchOffset = result.next_offset ?? 0;
        if (this.searchWords.length === 0 && !this.isFavoritesMode) {
          this.fetchSuggestions(token, version);
        }
      }).catch((error: Error) => {
        if (!this.active || version !== this.searchVersion || token !== AppStorage.get<string>('access_token')) return;
        this.searchError = error.message || '搜索失败，请重试';
        if (error instanceof ApiError && error.status === 401) {
          this.loginRequired = true;
          this.error = '登录已过期，请重新登录';
        }
      }).finally(() => {
        if (version === this.searchVersion) this.isSearching = false;
      });
  }

  stop(): void {
    this.suggestions = [];
    this.suggesting = false;
    this.suggestionError = '';
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
    this.searchVersion++;
    this.isSearching = false;
    this.active = false;
    this.generation++;
    this.loading = false;
    this.loadingMore = false;
    this.isFavoritesMode = false;
  }

  loadFavorites(): Promise<void> {
    this.stop();
    this.active = true;
    this.isFavoritesMode = true;
    const version = ++this.generation;
    const token = AppStorage.get<string>('access_token') ?? '';
    if (token.length === 0) {
      this.loginRequired = true;
      this.error = '请先登录后查看收藏';
      return Promise.resolve();
    }
    this.loginRequired = false;
    this.loading = true;
    this.error = '';
    this.words = [];
    this.hasMore = true;
    this.loadingMore = false;
    this.loadMoreError = '';
    this.nextBeforeId = null;
    return this.fetchFavorites(token, version, true);
  }

  loadMoreFavorites(): Promise<void> {
    if (this.loadingMore || !this.hasMore || !this.active || this.loading || this.loginRequired || !this.isFavoritesMode) {
      return Promise.resolve();
    }
    const token = AppStorage.get<string>('access_token') ?? '';
    if (token.length === 0) {
      this.loginRequired = true;
      return Promise.resolve();
    }
    this.loadingMore = true;
    this.loadMoreError = '';
    const version = this.generation;
    return this.fetchFavorites(token, version, false);
  }

  private fetchFavorites(token: string, version: number, isInitial: boolean): Promise<void> {
    return WordbookService.getFavorites(30, isInitial ? null : this.nextBeforeId, token)
      .then((page: FavoritePage) => {
        if (!this.current(version)) return;
        const newWords: WordbookWord[] = this.convertFavorites(page.items);
        if (isInitial) {
          this.words = newWords;
        } else {
          this.words = [...this.words, ...newWords];
        }
        this.nextBeforeId = page.next_before_id;
        this.hasMore = page.next_before_id !== null;
      })
      .catch((error: Error) => {
        if (!this.current(version)) return;
        if (error instanceof ApiError && error.status === 401) {
          this.loginRequired = true;
          if (isInitial) this.error = '登录已过期，请重新登录';
        } else {
          if (isInitial) {
            this.error = error.message || '暂时无法加载收藏，请重试';
          } else {
            this.loadMoreError = error.message || '加载更多失败，请重试';
          }
        }
      })
      .finally(() => {
        if (!this.current(version)) return;
        if (isInitial) this.loading = false;
        else this.loadingMore = false;
      });
  }

  private convertFavorites(items: FavoriteWord[]): WordbookWord[] {
    const result: WordbookWord[] = [];
    for (let i = 0; i < items.length; i++) {
      result.push({
        id: items[i].id,
        word: items[i].word,
        meaning: items[i].meaning ?? '',
        pos: null,
        frequency: null
      });
    }
    return result;
  }

  dispose(): void {
    this.stop();
  }

  retrySuggestions(): void {
    const token = AppStorage.get<string>('access_token') ?? '';
    if (this.active && this.isSearchMode && !this.isSearching && !this.suggesting &&
      !this.searchError && this.searchWords.length === 0 && token) {
      this.fetchSuggestions(token, this.searchVersion);
    }
  }

  private fetchSuggestions(token: string, version: number): void {
    if (!/^[a-zA-Z][a-zA-Z'-]{1,39}$/.test(this.searchKeyword)) return;
    this.suggesting = true;
    this.suggestionError = '';
    WordbookService.suggestWords(this.apiBookId, this.searchKeyword, token)
      .then((result: WordSuggestionResponse) => {
        if (!this.active || version !== this.searchVersion || token !== AppStorage.get<string>('access_token')) return;
        this.suggestions = result.data.map((item: WordbookWord) => item.word);
      }).catch((error: Error) => {
        if (!this.active || version !== this.searchVersion || token !== AppStorage.get<string>('access_token')) return;
        this.suggestionError = error.message || '候选词加载失败';
      }).finally(() => {
        if (version === this.searchVersion) this.suggesting = false;
      });
  }

  private current(version: number): boolean {
    return this.active && version === this.generation;
  }
}
