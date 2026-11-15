import { QuizProgressStore } from '../../storage/QuizProgressStore';
import { ReviewService, ReviewWord } from '../../service/ReviewService';
import { router } from '@kit.ArkUI';
import { WordListViewModel } from '../../viewmodels/WordListViewModel';
import { WordbookWord } from '../../models/WordbookModels';
import { FavoriteService } from '../../service/FavoriteService';

@Entry
@Component
struct BookDetail {
  @State book_id: number = 0;
  @State from: string = '';
  @State selectedWords: string[] = [];
  @State private model: WordListViewModel = new WordListViewModel();
  @State private removingFavorite: boolean = false;
  @State private suggestionDismissed: boolean = false;
  @State private searchValue: string = '';
  @StorageLink('access_token') @Watch('sessionChanged') token: string = '';
  @State private reviews: ReviewWord[] = [];
  @State private hasReviewProgress: boolean = false;
  @State private reviewTotal: number = 0;
  @State private reviewDue: number = 0;
  @State private reviewLoading: boolean = false;
  @State private reviewError: string = '';
  private reviewVersion: number = 0;
  private mounted: boolean = false;

  aboutToAppear() {
    this.mounted = true;
    const params = router.getParams() as Record<string, Object>;
    if (params) {
      this.book_id = params.book_id as number;
      this.from = params.from as string;
    }
    this.loadWords();
  }

  aboutToDisappear() {
    this.mounted = false;
    this.reviewVersion++;
    this.model.dispose();
  }

  sessionChanged(): void {
    if (this.mounted) this.loadWords();
  }

  onPageShow(): void {
    if (this.mounted && this.from === 'review') this.loadReviews(true);
  }

  private loadWords(): void {
    if (this.from === 'review') { this.loadReviews(true); return; }
    if (this.from === 'favorites') {
      this.model.loadFavorites().then(() => {
        if (this.mounted) {
          this.selectedWords = [];
        }
      });
    } else {
      this.model.loadInitial(this.book_id).then(() => {
        if (this.mounted) {
          this.selectedWords = this.model.words.map((w: WordbookWord) => w.word);
        }
      });
    }
  }

  private loadMoreWords(): void {
    const oldCount = this.model.words.length;
    this.model.loadMore().then(() => {
      if (this.mounted) {
        const words = this.model.words;
        for (let i = oldCount; i < words.length; i++) {
          this.selectedWords = [...this.selectedWords, words[i].word];
        }
      }
    });
  }

  setWordSelected(word: string, selected: boolean): void {
    if (selected && !this.selectedWords.includes(word)) {
      this.selectedWords = [...this.selectedWords, word];
    } else if (!selected) {
      this.selectedWords = this.selectedWords.filter((value: string) => value !== word);
    }
  }

  currentWords(): WordbookWord[] {
    return this.model.isSearchMode ? this.model.searchWords : this.model.words;
  }

  allSelected(): boolean {
    return this.currentWords().length > 0 &&
      this.currentWords().every((item: WordbookWord) => this.selectedWords.includes(item.word));
  }

  toggleAll(): void {
    const words: WordbookWord[] = this.currentWords();
    const visible: string[] = words.map((item: WordbookWord) => item.word);
    this.selectedWords = this.allSelected()
      ? this.selectedWords.filter((word: string) => !visible.includes(word))
      : [...this.selectedWords, ...visible.filter((word: string) => !this.selectedWords.includes(word))];
  }

  private confirmRemoveFavorite(word: string): void {
    if (this.removingFavorite) return;
    this.getUIContext().showAlertDialog({
      title: '取消收藏',
      message: `你真的要取消「${word}」的收藏吗？`,
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '确定',
        action: () => { this.removeFavoriteWord(word); }
      }
    });
  }

  private async removeFavoriteWord(word: string): Promise<void> {
    if (this.removingFavorite || !this.token) return;
    this.removingFavorite = true;
    try {
      await FavoriteService.remove(word, this.token);
      this.model.words = this.model.words.filter((item: WordbookWord) => item.word !== word);
      this.getUIContext().getPromptAction().showToast({ message: `已取消收藏「${word}」` });
    } catch (error) {
      const failure = error as Error;
      this.getUIContext().getPromptAction().showToast({
        message: failure.message || '取消收藏失败，请重试'
      });
    } finally {
      this.removingFavorite = false;
    }
  }

  private onSearchInputChange(value: string): void {
    this.suggestionDismissed = false;
    this.model.onSearchInput(value);
  }

  private searchSuggestion(word: string): void {
    this.searchValue = word;
    this.onSearchInputChange(word);
  }

  private closeSuggestion(): void {
    this.suggestionDismissed = true;
  }

  private async loadReviews(reset: boolean): Promise<void> {
    if (this.reviewLoading && !reset) return;
    const version = ++this.reviewVersion;
    const token = this.token;
    if (reset) this.reviews = [];
    this.reviewLoading = true;
    this.reviewError = '';
    try {
      if (!token) throw new Error('请先登录后查看复习计划');
      const lastId = this.reviews.length > 0 ? this.reviews[this.reviews.length - 1].id : 0;
      const context = this.getUIContext().getHostContext();
      const userId = AppStorage.get<number>('user_id') ?? 0;
      const saved = context && userId > 0 ? await QuizProgressStore.load(context, QuizProgressStore.key(userId, true, 0)) : undefined;
      const result = await ReviewService.list(token, lastId);
      if (!this.mounted || version !== this.reviewVersion || token !== this.token) return;
      this.reviews = this.reviews.concat(result.words);
      this.hasReviewProgress = saved !== undefined;
      this.reviewTotal = result.total;
      this.reviewDue = result.due;
    } catch (error) {
      if (this.mounted && version === this.reviewVersion) this.reviewError = (error as Error).message;
    } finally {
      if (version === this.reviewVersion) this.reviewLoading = false;
    }
  }

  @Builder
  ReviewContent() {
    Column() {
      Row() {
        Image($r('app.media.ic_chevron_left_gray')).width(24).height(24).onClick(() => router.back())
        Text('复习计划').fontSize(20).fontWeight(FontWeight.Bold).layoutWeight(1).margin({ left: 12 })
        Button(this.hasReviewProgress ? '继续复习' : '开始复习').fontSize(14).backgroundColor('#3478F6')
          .enabled((this.reviewDue > 0 || this.hasReviewProgress) && !this.reviewLoading && !this.reviewError)
          .onClick(() => router.pushUrl({ url: 'pages/question_flashcard', params: { review: true } }))
      }.width('100%').padding(16)
      Column({ space: 8 }) {
        Text(`今日待复习 ${this.reviewDue} 词`).fontSize(24).fontWeight(FontWeight.Bold).fontColor('#253858')
        Text(`计划中共 ${this.reviewTotal} 词 · 答对三次完成复习`).fontSize(13).fontColor('#64748B')
      }.width('100%').alignItems(HorizontalAlign.Start).padding(20).backgroundColor('#EAF1FF')
      if (this.reviewError) {
        Text(this.reviewError).fontColor('#C44B43').padding(12)
        Button('重试').onClick(() => this.loadReviews(this.reviews.length === 0))
      }
      List({ space: 12 }) {
        ForEach(this.reviews, (item: ReviewWord) => {
          ListItem() {
            Column({ space: 8 }) {
              Row() {
                Text(item.word).fontSize(22).fontWeight(FontWeight.Bold).layoutWeight(1)
                Text(`${item.review_count}/3 次`).fontSize(13).fontColor('#3478F6')
              }.width('100%')
              Text(item.meaning).fontSize(15).fontColor('#475569').width('100%')
              Text(`复习日期：${item.next_review_at}`).fontSize(12).fontColor('#64748B').width('100%')
            }.width('100%').padding(18).borderRadius(16).backgroundColor('#FFFFFF')
          }
        }, (item: ReviewWord) => `${item.id}`)
        ListItem() {
          if (this.reviewLoading) { LoadingProgress().width(32).height(32) }
          else if (this.reviews.length < this.reviewTotal) {
            Button('加载更多').onClick(() => this.loadReviews(false))
          } else if (this.reviews.length === 0 && !this.reviewError) {
            Text('还没有错题计划，开启自动收入后，答错的单词会出现在这里。').fontColor('#64748B').padding(20)
          }
        }
      }.layoutWeight(1).width('100%').padding(16)
    }.width('100%').height('100%')
  }

  @Builder
  TitleText() {
    if (this.from === 'favorites') {
      Text('我的收藏')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor('#1A1A1A')
        .layoutWeight(1)
        .textAlign(TextAlign.Center)
    } else if (this.book_id === 0) {
      Text('CET-4')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor('#1A1A1A')
        .layoutWeight(1)
        .textAlign(TextAlign.Center)
    } else if (this.book_id === 1) {
      Text('考研词汇全表')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor('#1A1A1A')
        .layoutWeight(1)
        .textAlign(TextAlign.Center)
    } else if (this.book_id === 2) {
      Text('考研词汇·词频优先')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor('#1A1A1A')
        .layoutWeight(1)
        .textAlign(TextAlign.Center)
    } else {
      Text('未知词书')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor('#1A1A1A')
        .layoutWeight(1)
        .textAlign(TextAlign.Center)
    }
  }

  @Builder
  WordCard(item: WordbookWord, index: number) {
    Stack({ alignContent: Alignment.TopEnd }) {
      Row() {
        Column() {
          Row() {
            Text(item.word)
              .fontSize(16)
              .fontWeight(FontWeight.Bold)
              .fontColor('#1A1A1A')
            if (item.pos) {
              Text(item.pos)
                .fontSize(13)
                .fontColor('#8A8F99')
                .margin({ left: 8 })
            }
          }
          .alignItems(VerticalAlign.Center)

          Text(item.meaning)
            .fontSize(14)
            .fontColor('#333333')
            .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        if (this.from !== 'wordbook' && this.from !== 'favorites') {
          Checkbox({ name: item.word })
            .select(this.selectedWords.includes(item.word))
            .selectedColor('#3B7FFF')
            .unselectedColor('#D1D5DB')
            .shape(CheckBoxShape.CIRCLE)
            .width(22)
            .height(22)
            .margin({ left: 8 })
            .accessibilityText(`选择单词 ${item.word}`)
            .onChange((selected: boolean) => {
              this.setWordSelected(item.word, selected);
            })
        }
      }
      .width('100%')
      .padding(16)
      .borderRadius(12)
      .backgroundColor('#FFFFFF')
      .alignItems(VerticalAlign.Center)

      if (this.from === 'favorites') {
        Image($r('app.media.android_ic_star_filled'))
          .width(24)
          .height(24)
          .margin({ top: 12, right: 12 })
          .fillColor('#F59E0B')
          .onClick(() => this.confirmRemoveFavorite(item.word))
      }
    }
    .width('100%')
    .margin({ top: index === 0 ? 0 : 10 })
  }

  @Builder
  SuggestionBar() {
    Column({ space: 6 }) {
      Row() {
        Text('猜你想搜')
          .fontSize(11)
          .fontColor('#8A8F99')
        Blank()
        Text('×')
          .fontSize(16)
          .fontColor('#8A8F99')
          .onClick(() => this.closeSuggestion())
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      if (this.model.suggesting) {
        Text('正在寻找相近单词…').fontSize(12).fontColor('#8A8F99')
      } else if (this.model.suggestionError) {
        Row({ space: 8 }) {
          Text(this.model.suggestionError).fontSize(12).fontColor('#8A8F99').layoutWeight(1)
          Text('重试').fontSize(12).fontColor('#3478F6')
            .onClick(() => this.model.retrySuggestions())
        }.width('100%')
      } else if (this.model.suggestions.length === 0) {
        Text('当前词书暂无相近单词，可检查拼写或换一本词书试试')
          .fontSize(12)
          .fontColor('#8A8F99')
          .width('100%')
      }
      Scroll() {
        Row({ space: 8 }) {
          ForEach(this.model.suggestions, (word: string, index: number) => {
            Button(word, { type: ButtonType.Normal })
              .height(28)
              .fontSize(12)
              .borderRadius(14)
              .padding({ left: 14, right: 14 })
              .backgroundColor('#EAF2FF')
              .fontColor('#3478F6')
              .onClick(() => this.searchSuggestion(word))
          }, (word: string) => word)
        }
        .padding({ left: 2, right: 2 })
      }
      .scrollable(ScrollDirection.Horizontal)
      .height(this.model.suggestions.length > 0 ? 32 : 0)
      .scrollBar(BarState.Off)
      .width('100%')
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 10 })
    .backgroundColor('#FFFFFF')
    .borderRadius({ topLeft: 16, topRight: 16 })
    .shadow({ radius: 12, color: '#1A000000', offsetY: -3 })
  }

  build() {
    Column() {
      if (this.from === 'review') {
        this.ReviewContent()
      } else {
      Row() {
        Image($r('app.media.ic_chevron_left_gray'))
          .width(24)
          .height(24)
          .onClick(() => {
            router.back();
          })

        this.TitleText()

        if (this.from !== 'wordbook' && this.from !== 'favorites') {
          Text('确认')
            .fontSize(16)
            .fontColor('#3B7FFF')
            .fontWeight(FontWeight.Bold)
            .onClick(() => {
              router.pushUrl({ url: 'pages/upload_movie', params: { book_id: this.book_id } });
            })
        }
      }
      .width('100%')
      .height(56)
      .padding({ left: 16, right: 16 })
      .alignItems(VerticalAlign.Center)

      if (this.from !== 'favorites') {
        Column() {
          Row() {
            Image($r('app.media.ic_search'))
              .width(18)
              .height(18)
              .margin({ left: 12 })

            TextInput({ text: $$this.searchValue, placeholder: '搜索单词' })
              .layoutWeight(1)
              .backgroundColor('#00000000')
              .margin({ left: 8 })
              .height(40)
              .onChange((value: string) => {
                this.onSearchInputChange(value);
              })
          }
          .width('100%')
          .height(44)
          .borderRadius(22)
          .backgroundColor('#F2F3F5')
          .alignItems(VerticalAlign.Center)
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 12 })
      }

      if (this.model.isSearchMode) {
        Text(`搜索结果 ${this.model.searchWords.length} 词 · 已选 ${this.selectedWords.length} 词`)
          .fontSize(13)
          .fontColor('#8A8F99')
          .width('100%')
          .padding({ left: 16, top: 12 })
      } else {
        Text(`共 ${this.model.words.length} 词 · 已选 ${this.selectedWords.length} 词`)
          .fontSize(13)
          .fontColor('#8A8F99')
          .width('100%')
          .padding({ left: 16, top: 12 })
      }

      if (this.model.loginRequired) {
        Column() {
          Text(this.model.error)
            .fontSize(14)
            .fontColor('#8A8F99')
          Button('重新登录')
            .margin({ top: 16 })
            .onClick(() => {
              this.model.stop();
              router.replaceUrl({ url: 'pages/Index' });
            })
        }
        .width('100%')
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)
        .justifyContent(FlexAlign.Center)
      } else if (this.model.isSearchMode && this.model.isSearching && this.model.searchWords.length === 0) {
        Column() {
          LoadingProgress()
            .width(32)
            .height(32)
          Text('搜索中…')
            .fontSize(14)
            .fontColor('#8A8F99')
            .margin({ top: 12 })
        }
        .width('100%')
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)
        .justifyContent(FlexAlign.Center)
      } else if (this.model.isSearchMode && this.model.searchError && this.model.searchWords.length === 0) {
        Column() {
          Text(this.model.searchError)
            .fontSize(14)
            .fontColor('#C44B43')
          Button('重试')
            .margin({ top: 16 })
            .onClick(() => {
              this.model.retrySearch();
            })
        }
        .width('100%')
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)
        .justifyContent(FlexAlign.Center)
      } else if (this.model.isSearchMode && this.model.searchWords.length === 0) {
        Column() {
          Text('没有找到相关单词')
            .fontSize(14)
            .fontColor('#8A8F99')
        }
        .width('100%')
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)
        .justifyContent(FlexAlign.Center)
      } else if (!this.model.isSearchMode && this.model.loading && this.model.words.length === 0) {
        Column() {
          LoadingProgress()
            .width(32)
            .height(32)
          Text('正在加载词条…')
            .fontSize(14)
            .fontColor('#8A8F99')
            .margin({ top: 12 })
        }
        .width('100%')
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)
        .justifyContent(FlexAlign.Center)
      } else if (!this.model.isSearchMode && this.model.error && this.model.words.length === 0) {
        Column() {
          Text(this.model.error)
            .fontSize(14)
            .fontColor('#C44B43')
          Button('重新加载')
            .margin({ top: 16 })
            .onClick(() => {
              this.loadWords();
            })
        }
        .width('100%')
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)
        .justifyContent(FlexAlign.Center)
      } else if (this.model.isSearchMode) {
        List() {
          ForEach(this.model.searchWords, (item: WordbookWord, index: number) => {
            ListItem() {
              this.WordCard(item, index)
            }
          }, (item: WordbookWord) => `${item.id}`)

          if (!this.model.isSearching && this.model.searchWords.length > 0) {
            ListItem() {
              Column() {
                if (this.model.searchError) {
                  Text(this.model.searchError).fontColor('#C44B43')
                  Button('重试').onClick(() => { this.model.retrySearch(); })
                } else if (this.model.searchHasMore) {
                  Button('加载更多结果').onClick(() => { this.model.loadMoreSearch(); })
                } else {
                  Text(`已显示全部 ${this.model.searchWords.length} 个搜索结果`)
                    .fontSize(13)
                    .fontColor('#8A8F99')
                }
              }
                .width('100%')
                .alignItems(HorizontalAlign.Center)
                .padding({ top: 16, bottom: 16 })
            }
          }
        }
        .width('100%')
        .layoutWeight(1)
        .padding({ left: 16, right: 16, top: 12, bottom: 20 })
        .scrollBar(BarState.Off)
        .onReachEnd(() => {
          this.model.loadMoreSearch();
        })
      } else {
        List() {
          ForEach(this.model.words, (item: WordbookWord, index: number) => {
            ListItem() {
              this.WordCard(item, index)
            }
          }, (item: WordbookWord) => `${item.id}`)

          if (this.model.loadingMore) {
            ListItem() {
              Row() {
                LoadingProgress()
                  .width(20)
                  .height(20)
                Text('正在加载更多…')
                  .fontSize(13)
                  .fontColor('#8A8F99')
                  .margin({ left: 8 })
              }
              .width('100%')
              .justifyContent(FlexAlign.Center)
              .padding({ top: 16, bottom: 16 })
            }
          }

          if (this.model.loadMoreError) {
            ListItem() {
              Column() {
                Text(this.model.loadMoreError)
                  .fontSize(13)
                  .fontColor('#C44B43')
                Button('重试')
                  .fontSize(13)
                  .margin({ top: 8 })
                  .onClick(() => {
                    this.loadMoreWords();
                  })
              }
              .width('100%')
              .alignItems(HorizontalAlign.Center)
              .padding({ top: 16, bottom: 16 })
            }
          }

          if (!this.model.hasMore && !this.model.loadingMore && this.model.words.length > 0) {
            ListItem() {
              Text('没有更多单词了')
                .fontSize(13)
                .fontColor('#8A8F99')
                .width('100%')
                .textAlign(TextAlign.Center)
                .padding({ top: 16, bottom: 16 })
            }
          }
        }
        .width('100%')
        .layoutWeight(1)
        .padding({ left: 16, right: 16, top: 12, bottom: 20 })
        .scrollBar(BarState.Off)
        .onReachEnd(() => {
          this.loadMoreWords();
        })
      }

      if (!this.suggestionDismissed && this.from !== 'favorites' && this.model.isSearchMode &&
        !this.model.loginRequired && !this.model.isSearching && !this.model.searchError &&
        this.model.searchWords.length === 0) {
        this.SuggestionBar()
      }

      if (this.from !== 'wordbook' && this.from !== 'favorites' && !this.model.loginRequired) {
      if (this.from === 'favorites') {
        Text(`已收藏 ${this.model.words.length} 词`)
          .fontSize(13)
          .fontColor('#8A8F99')
          .width('100%')
          .padding({ left: 16, top: 12 })
      } else if (this.model.isSearchMode) {
          if (!this.model.isSearching || this.model.searchWords.length > 0) {
            Column() {
              Button(this.allSelected() ? '取消已加载词条选择' : '选择已加载词条')
                .width('100%')
                .height(48)
                .fontSize(16)
                .fontColor('#FFFFFF')
                .backgroundColor('#3B7FFF')
                .borderRadius(24)
                .enabled(this.model.searchWords.length > 0)
                .onClick(() => {
                  this.toggleAll();
                })
            }
            .width('100%')
            .padding({ left: 20, right: 20, top: 12, bottom: 24 })
          }
        } else {
          if (!this.model.loading && !this.model.error) {
            Column() {
              Button(this.allSelected() ? '取消已加载词条选择' : '选择已加载词条')

                .width('100%').height(48).fontSize(16)
                .fontColor('#FFFFFF').backgroundColor('#3B7FFF').borderRadius(24)
                .enabled(this.model.words.length > 0)
                .onClick(() => { this.toggleAll(); })
            }
            .width('100%').padding({ left: 20, right: 20, top: 12, bottom: 24 })
          }
        }
      }
    }
      }
    .width('100%').height('100%').backgroundColor('#F5F7FA')
  }
}
