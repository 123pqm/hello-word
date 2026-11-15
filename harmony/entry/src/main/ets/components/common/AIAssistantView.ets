class AssistantMessage {
  id: number;
  content: string;

  constructor(id: number, content: string) {
    this.id = id;
    this.content = content;
  }
}

@Component
export struct AIAssistantView {
  @State private messages: AssistantMessage[] = [];
  @State private draft: string = '';
  private nextMessageId: number = 1;
  private chatScroller: Scroller = new Scroller();
  private scrollAfterSend: boolean = false;
  private readonly pageBg: string = '#F5F7FA';
  private readonly primary: string = '#3478F6';
  private readonly titleColor: string = '#202A38';
  private readonly mutedColor: string = '#8C98AC';

  private sendMessage(): void {
    const content: string = this.draft.trim();
    if (content.length === 0) {
      return;
    }
    this.scrollAfterSend = true;
    this.messages = [...this.messages, new AssistantMessage(this.nextMessageId++, content)];
    this.draft = '';
  }

  private newConversation(): void {
    if (this.messages.length === 0 && this.draft.length === 0) {
      return;
    }
    this.getUIContext().getPromptAction().showDialog({
      title: '开始新对话',
      message: '将清空当前页面的消息和输入内容，是否继续？',
      buttons: [
        { text: '取消', color: this.mutedColor },
        { text: '新对话', color: this.primary }
      ]
    }).then((result) => {
      if (result.index === 1) {
        this.messages = [];
        this.draft = '';
        this.scrollAfterSend = false;
      }
    }).catch(() => {});
  }

  @Builder
  FeatureCard(icon: Resource, title: string, subtitle: string, prompt: string) {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 12 }) {
        Image(icon)
          .width(25)
          .height(25)
          .objectFit(ImageFit.Contain)
          .fillColor(this.primary)
        Column({ space: 6 }) {
          Text(title)
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.titleColor)
          Text(subtitle)
            .fontSize(12)
            .fontColor(this.mutedColor)
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
      }
      .width('100%')
      .padding({ left: 14, right: 12, top: 20, bottom: 20 })
    }
    .layoutWeight(1)
    .constraintSize({ minHeight: 82 })
    .borderRadius(18)
    .backgroundColor('#FFFFFF')
    .onClick(() => {
      this.draft = prompt;
    })
  }

  @Builder
  WelcomeContent() {
    Column() {
      Image($r('app.media.android_vh_robot'))
        .width(88)
        .height(88)
        .objectFit(ImageFit.Contain)
        .margin({ top: 20, bottom: 14 })
      Text('今天想学点什么？')
        .fontSize(26)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.titleColor)
        .textAlign(TextAlign.Center)
      Text('从一个单词，到听懂一句台词')
        .fontSize(15)
        .fontColor(this.mutedColor)
        .margin({ top: 10, bottom: 24 })
        .textAlign(TextAlign.Center)
      Row({ space: 10 }) {
        this.FeatureCard($r('app.media.android_ic_words'), '单词讲解', '用法与搭配', '请讲解这个单词的含义、用法与搭配：')
        this.FeatureCard($r('app.media.android_ic_ai_translation'), '例句翻译', '读懂句子含义', '请翻译这句话，并说明关键表达：')
      }
      .width('100%')
      .alignItems(VerticalAlign.Top)
      Row({ space: 10 }) {
        this.FeatureCard($r('app.media.android_ic_video'), '台词解析', '拆解语法与表达', '请分析这句电影台词的语法和表达：')
        this.FeatureCard($r('app.media.android_ic_ai_practice'), '场景实战', '试着用英语回答', '我想练习英语场景对话，场景是：')
      }
      .width('100%')
      .alignItems(VerticalAlign.Top)
      .margin({ top: 10 })
    }
    .width('100%')
  }

  build() {
    Column() {
      Row() {
        Blank().width(44)
        Text('AI 学习助手')
          .fontSize(21)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.titleColor)
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
        Button({ type: ButtonType.Normal }) {
          Image($r('app.media.ai_new_chat'))
            .width(24)
            .height(24)
        }
        .width(44)
        .height(44)
        .borderRadius(22)
        .backgroundColor(Color.Transparent)
        .accessibilityText('开始新对话')
        .onClick(() => this.newConversation())
      }
      .height(56)
      .width('100%')
      .padding({ left: 12, right: 12 })

      Scroll(this.chatScroller) {
        Column() {
          if (this.messages.length === 0) {
            this.WelcomeContent()
          } else {
            ForEach(this.messages, (message: AssistantMessage) => {
              Row() {
                Text(message.content)
                  .fontSize(16)
                  .lineHeight(24)
                  .fontColor('#FFFFFF')
                  .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                  .constraintSize({ maxWidth: '86%' })
                  .borderRadius({ topLeft: 18, topRight: 6, bottomLeft: 18, bottomRight: 18 })
                  .backgroundColor(this.primary)
                  .wordBreak(WordBreak.BREAK_ALL)
                  .copyOption(CopyOptions.InApp)
              }
              .width('100%')
              .justifyContent(FlexAlign.End)
              .margin({ bottom: 14 })
            }, (message: AssistantMessage) => message.id.toString())
          }
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 12, bottom: 16 })
        .onAreaChange(() => {
          if (this.scrollAfterSend) {
            this.chatScroller.scrollEdge(Edge.Bottom);
            this.scrollAfterSend = false;
          }
        })
      }
      .width('100%')
      .layoutWeight(1)
      .align(Alignment.TopStart)
      .scrollBar(BarState.Off)

      Column({ space: 8 }) {
        Row({ space: 4 }) {
          TextInput({ text: this.draft, placeholder: '问问单词、例句或电影台词...' })
            .layoutWeight(1)
            .height(48)
            .fontSize(14)
            .placeholderColor('#9AA5B5')
            .backgroundColor(Color.Transparent)
            .padding({ left: 14, right: 4 })
            .maxLength(8000)
            .enterKeyType(EnterKeyType.Send)
            .onChange((value: string) => this.draft = value)
            .onSubmit(() => this.sendMessage())
          Button({ type: ButtonType.Circle }) {
            Image($r('app.media.android_ic_ai_send'))
              .width(25)
              .height(25)
              .objectFit(ImageFit.Contain)
          }
          .width(46)
          .height(46)
          .backgroundColor(this.primary)
          .accessibilityText('发送消息')
          .onClick(() => this.sendMessage())
        }
        .width('100%')
        .padding(5)
        .borderRadius(30)
        .backgroundColor('#FFFFFF')
        Text('AI 回答仅供学习参考')
          .fontSize(11)
          .fontColor('#9AA5B5')
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 10, bottom: 8 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pageBg)
  }
}
