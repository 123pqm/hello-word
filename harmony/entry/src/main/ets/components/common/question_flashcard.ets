import { FlashcardAISheet } from './FlashcardAISheet';
import { QuestionOption } from '../../models/QuizModels';
import { QuizVideo } from './QuizVideo';

@Component
export struct QuestionFlashcard {
  @Prop reviewSaveError: string = '';
  onRetrySave: () => void = () => {};
  @Prop word: string = '';
  @Prop @Watch('closeAI') questionId: string = '';
  @Prop sentence: string = '';
  @State private aiVisible: boolean = false;
  @Prop options: QuestionOption[] = [];
  @Prop correctIndex: number = -1;
  @Prop selectedIndex: number = -1;
  @Prop answered: boolean = false;
  @Prop submitting: boolean = false;
  @Prop answerError: string = '';
  @Prop movieId: number = 0;
  @Prop cardId: number = 0;
  @Prop startTime: number = 0;
  @Prop endTime: number = 0;
  @Prop lastQuestion: boolean = false;
  onSelect: (index: number) => void = () => {};
  onNext: () => void = () => {};

  closeAI(): void { this.aiVisible = false; }
  aboutToDisappear(): void { this.closeAI(); }

  @Builder
  AISheet() {
    FlashcardAISheet({ word: this.word, sentence: this.sentence, onClose: () => this.closeAI() })
  }

  private optionBorderColor(index: number): string {
    if (!this.answered) return this.submitting && index === this.selectedIndex ? '#3478F6' : '#E2E8F0';
    if (index === this.correctIndex) return '#36A678';
    return index === this.selectedIndex ? '#E16868' : '#E2E8F0';
  }
  @Builder
  OptionCard(index: number) {
    Column({ space: 10 }) {
      Row() {
        Text(['A', 'B', 'C', 'D'][index]).fontSize(13).fontWeight(FontWeight.Bold).fontColor('#64748B')
        Blank()
        if (this.answered && index === this.correctIndex) {
          Text('✓').fontColor('#23875D').fontWeight(FontWeight.Bold)
        } else if (this.answered && index === this.selectedIndex) {
          Text('✗').fontColor('#C44B43').fontWeight(FontWeight.Bold)
        }
      }.width('100%')
      if (this.answered) {
        Text(this.options[index]?.word ?? '').fontSize(20).fontWeight(FontWeight.Bold)
          .fontColor(index === this.correctIndex ? '#23875D' : '#1F2937')
          .width('100%').textAlign(TextAlign.Center).wordBreak(WordBreak.BREAK_WORD)
      }
      Text(this.options[index]?.text ?? '').fontSize(14).fontColor('#475569')
        .width('100%').textAlign(TextAlign.Center).wordBreak(WordBreak.BREAK_WORD)
    }.layoutWeight(1).constraintSize({ minHeight: 120 }).padding(14)
      .backgroundColor(this.answered && index === this.correctIndex ? '#ECF8F0' : '#FFFFFF')
      .borderRadius(16).border({ width: 1.5, color: this.optionBorderColor(index) })
      .onClick(() => { if (!this.answered && !this.submitting) this.onSelect(index); })
  }
  build() {
    Scroll() {
      Column({ space: 16 }) {
        Row() {
          Text('AI 学习搭子').fontSize(17).fontWeight(FontWeight.Bold).fontColor('#253858').layoutWeight(1)
          Button({ type: ButtonType.Normal }) {
            Image($r('app.media.android_vh_robot')).width(64).height(64).objectFit(ImageFit.Contain)
          }.width(68).height(68).padding(0).backgroundColor(Color.Transparent)
            .accessibilityText('打开 AI 单词问答').enabled(this.word.length > 0)
            .onClick(() => { this.aiVisible = true; })
            .bindSheet($$this.aiVisible, this.AISheet(), {
              height: '72%', dragBar: true, showClose: false, backgroundColor: '#F5F7FA'
            })
        }.width('100%')
        if (this.movieId > 0) { QuizVideo({ answered: this.answered, suspended: this.aiVisible, movieId: this.movieId, cardId: this.cardId, startTime: this.startTime, endTime: this.endTime }) }
        Column({ space: 10 }) {
          Text('词义挑战').fontSize(12).fontColor('#3478F6')
          Text(this.word).fontSize(34).fontWeight(FontWeight.Bold).fontColor('#1F2937')
            .width('100%').textAlign(TextAlign.Center).wordBreak(WordBreak.BREAK_WORD)
          Text(this.answered ? '对照英文，记住容易混淆的词' : '请选择这个单词的正确释义')
            .fontSize(13).fontColor('#64748B')
        }.width('100%').padding(24).backgroundColor('#FFFFFF').borderRadius(20)
        ForEach([0, 2], (start: number) => {
          Row({ space: 12 }) {
            this.OptionCard(start)
            this.OptionCard(start + 1)
          }.width('100%').alignItems(VerticalAlign.Top)
        }, (start: number) => `${this.questionId}-${start}`)
        if (this.submitting) { Text('正在提交答案…').fontSize(13).fontColor('#64748B') }
        if (this.answerError) {
          Text(this.answerError).fontSize(13).fontColor('#C44B43').width('100%')
          Text('点击选项重新提交').fontSize(12).fontColor('#64748B')
        }
        if (this.reviewSaveError) {
          Text(this.reviewSaveError).fontSize(13).fontColor('#C44B43')
          Button('重试保存错题').onClick(() => this.onRetrySave())
        }
        if (this.answered) {
          Text(this.selectedIndex === this.correctIndex ? '✓ 回答正确' :
            `正确答案：${['A', 'B', 'C', 'D'][this.correctIndex]} · ${this.options[this.correctIndex]?.word ?? ''}`)
            .fontSize(16).fontWeight(FontWeight.Bold).fontColor(this.selectedIndex === this.correctIndex ? '#23875D' : '#C44B43')
          Button(this.lastQuestion ? '完成练习' : '下一题').width('100%').height(48)
            .backgroundColor('#3478F6').fontColor('#FFFFFF').onClick(() => this.onNext())
        }
      }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 28 })
    }.width('100%').height('100%').scrollBar(BarState.Off).backgroundColor('#F5F7FA')
  }
}
