import { AIChatMessage, FlashcardAIService } from '../../service/FlashcardAIService';

@Component
export struct FlashcardAISheet {
  @Prop word: string = '';
  @Prop sentence: string = '';
  onClose: () => void = () => {};
  @StorageLink('access_token') @Watch('resetSession') token: string = '';
  @State private draft: string = '';
  @State private messages: AIChatMessage[] = [];
  @State private busy: boolean = false;
  @State private error: string = '';
  private service: FlashcardAIService = new FlashcardAIService();
  private generation: number = 0;
  private conversation: AIChatMessage[] = [];
  private scroller: Scroller = new Scroller();
  private scrollPending: boolean = false;

  aboutToDisappear(): void { this.generation++; this.service.cancel(); }
  resetSession(): void {
    this.generation++;
    this.service.cancel();
    this.messages = [];
    this.conversation = [];
    this.busy = false;
    this.error = '';
  }
  private choose(mode: number): void {
    if (this.busy) return;
    if ((mode === 1 || mode === 2) && !this.sentence.trim()) {
      this.error = '当前闪卡没有例句，请在下方输入想翻译或解析的句子。';
      return;
    }
    const prompts: string[] = [
      `请用中文讲解单词 ${this.word} 的含义、常用搭配和用法，并给一个简短例句。`,
      '请将当前例句翻译成自然的中文，并解释其中的关键表达。',
      '请解析当前电影台词的语法、语气和潜台词；不确定的场景信息请明确说明。',
      `请用单词 ${this.word} 设计一个简短英语对话场景。先向我提一个问题，等我回答后纠正表达。`
    ];
    this.draft = prompts[mode];
    this.send();
  }
  private async send(): Promise<void> {
    const prompt = this.draft.trim();
    if (!prompt || this.busy) return;
    const version = ++this.generation;
    const history: AIChatMessage[] = [...this.conversation.slice(-6), { role: 'user', content: prompt }];
    this.messages = history;
    this.draft = '';
    this.error = '';
    this.busy = true;
    this.scrollPending = true;
    try {
      const answer = await this.service.ask(history, this.word, this.sentence, this.token, (partial: string) => {
        if (version !== this.generation) return;
        this.messages = [...history, { role: 'assistant', content: partial }];
        this.scrollPending = true;
      });
      if (version !== this.generation) return;
      this.messages = [...history, { role: 'assistant', content: answer }];
      this.conversation = this.messages;
      this.scrollPending = true;
    } catch (error) {
      if (version !== this.generation) return;
      // 保留已经收到的文字；失败的回答不加入下轮上下文。
      this.draft = prompt;
      this.error = (error as Error).message || '网络异常，请重试';
    } finally {
      if (version === this.generation) this.busy = false;
    }
  }
  @Builder
  Feature(icon: Resource, title: string, detail: string, mode: number) {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 10 }) {
        Image(icon).width(24).height(24).fillColor('#3478F6')
        Column({ space: 5 }) {
          Text(title).fontSize(15).fontWeight(FontWeight.Bold).fontColor('#202A38')
          Text(detail).fontSize(11).fontColor('#8C98AC')
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      }.width('100%').padding(14)
    }.layoutWeight(1).height(76).borderRadius(18).backgroundColor(Color.White)
      .enabled(!this.busy).onClick(() => this.choose(mode))
  }
  build() {
    Column({ space: 0 }) {
      Row({ space: 10 }) {
        Image($r('app.media.android_vh_robot')).width(54).height(54)
        Column({ space: 4 }) {
          Text('AI 学习搭子').fontSize(21).fontWeight(FontWeight.Bold).fontColor('#202A38')
          Text(this.word ? `一起学 · ${this.word}` : '从一个单词，到听懂一句台词')
            .fontSize(12).fontColor('#8090A8').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Button('×', { type: ButtonType.Circle }).width(32).height(32).fontSize(23)
          .fontColor('#8090A8').backgroundColor('#E8EEF7').accessibilityText('关闭 AI 问答')
          .onClick(() => this.onClose())
      }.width('100%').padding({ left: 18, right: 18, top: 6, bottom: 12 })
      Scroll(this.scroller) {
        Column({ space: 12 }) {
          Row({ space: 10 }) {
            this.Feature($r('app.media.android_ic_words'), '单词讲解', '用法与搭配', 0)
            this.Feature($r('app.media.android_ic_ai_translation'), '例句翻译', '读懂句子含义', 1)
          }.width('100%')
          Row({ space: 10 }) {
            this.Feature($r('app.media.android_ic_video'), '台词解析', '拆解语法与表达', 2)
            this.Feature($r('app.media.android_ic_ai_practice'), '场景实战', '试着用英语回答', 3)
          }.width('100%')
          if (this.messages.length === 0 && !this.busy) {
            Text('选择一种学习方式，或直接问我吧').fontSize(13).fontColor('#8C98AC').padding(16)
          }
          ForEach(this.messages, (message: AIChatMessage, index: number) => {
            Row() {
              Text(message.content).fontSize(15).lineHeight(24).copyOption(CopyOptions.InApp)
                .fontColor(message.role === 'user' ? Color.White : '#34445C')
                .padding(14).constraintSize({ maxWidth: '94%' }).borderRadius(16)
                .backgroundColor(message.role === 'user' ? '#3478F6' : '#FFFFFF')
            }.width('100%').justifyContent(message.role === 'user' ? FlexAlign.End : FlexAlign.Start)
          }, (message: AIChatMessage, index: number) => `${index}:${message.role}:${message.content}`)
          if (this.busy) {
            Row({ space: 8 }) {
              LoadingProgress().width(22).height(22).color('#3478F6')
              Text(this.messages[this.messages.length - 1]?.role === 'assistant' ? '正在回答…' : '正在思考，请稍候…').fontSize(13).fontColor('#8090A8')
            }.width('100%').padding(10)
          }
          if (this.error) { Text(this.error).fontSize(13).fontColor('#C65D64').width('100%') }
        }.width('100%').padding({ left: 18, right: 18, bottom: 12 })
          .onAreaChange(() => {
            if (this.scrollPending) { this.scroller.scrollEdge(Edge.Bottom); this.scrollPending = false; }
          })
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
      Column({ space: 6 }) {
        Row({ space: 6 }) {
          TextInput({ text: this.draft, placeholder: '问问单词、例句或电影台词…' })
            .layoutWeight(1).height(48).backgroundColor(Color.Transparent).fontSize(14).maxLength(2000)
            .onChange((value: string) => this.draft = value).enterKeyType(EnterKeyType.Send).onSubmit(() => this.send())
          Button('➤', { type: ButtonType.Circle }).width(42).height(42).fontSize(22)
            .backgroundColor('#3478F6').fontColor(Color.White).enabled(!this.busy && this.draft.trim().length > 0)
            .accessibilityText('发送').onClick(() => this.send())
        }.width('100%').padding(5).borderRadius(28).backgroundColor(Color.White)
        Text('AI 回答仅供学习参考').fontSize(10).fontColor('#9AA5B5')
      }.width('100%').padding({ left: 16, right: 16, top: 10, bottom: 12 })
    }.width('100%').height('100%').backgroundColor('#F5F7FA')
  }
}
