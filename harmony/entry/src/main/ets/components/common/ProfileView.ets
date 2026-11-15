import { router } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { AppColors } from '../../common/AppTheme';
import { FlashcardSettingsStore, FlashcardSettings } from '../../storage/FlashcardSettingsStore';

@Component
export struct ProfileView {
  @State showMeaning: boolean = false;
  @State showExample: boolean = true;
  @State lemmatization: boolean = true;
  @State modelSelection: string = 'tiny';
  @State quizMode: boolean = false;
  @State autoAddWrongToReview: boolean = false;

  aboutToAppear(): void {
    const context = this.getUIContext().getHostContext();
    if (!context) return;
    FlashcardSettingsStore.load(context as common.Context).then((settings: FlashcardSettings) => {
      this.showMeaning = settings.showMeaning;
      this.showExample = settings.showExample;
      this.lemmatization = settings.lemmatization;
      this.modelSelection = settings.modelSelection;
      this.quizMode = settings.quizMode;
      this.autoAddWrongToReview = settings.autoAddWrongToReview;
    });
  }

  private showModelDownloadDialog(modelName: string): void {
    this.getUIContext().showAlertDialog({
      title: `${modelName} 模型未下载`,
      message: `下载使用该模型可能导致手机发热，是否继续下载？`,
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: { value: '下载', action: () => {} }
    });
  }

  private onModelSelect(model: string): void {
    if (model === 'small' || model === 'medium') {
      this.showModelDownloadDialog(model);
      return;
    }
    this.modelSelection = model;
    const context = this.getUIContext().getHostContext();
    if (context) FlashcardSettingsStore.saveModelSelection(context as common.Context, model);
  }

  @Builder
  VipBadge() {
    Row({ space: 4 }) {
      Image($r('app.media.vip_badge_crown')).width(13).height(13)
      Text('VIP').fontSize(10).fontWeight(FontWeight.Bold).fontColor('#8A5B20')
    }
    .height(22).padding({ left: 8, right: 8 }).borderRadius(7)
    .linearGradient({ angle: 90, colors: [['#FFF4D8', 0], ['#F5DEAC', 1]] })
    .border({ width: 0.5, color: '#E8CD95' })
    .accessibilityText('VIP 权益')
  }

  @Builder
  ModelMenu() {
    Menu() {
      MenuItem({ content: 'tiny · 快速识别' }).onClick(() => this.onModelSelect('tiny'))
      MenuItem({ content: 'base · 更高精度' }).onClick(() => this.onModelSelect('base'))
      MenuItem({ content: 'small · 待下载' }).onClick(() => this.onModelSelect('small'))
      MenuItem({ content: 'medium · 待下载' }).onClick(() => this.onModelSelect('medium'))
      MenuItem({ content: '华为云 · VIP' }).onClick(() => this.onModelSelect('华为云'))
    }
  }

  @Builder
  CardContainer() {
    Column() {
      Row() {
        Column() {
          Image($r('app.media.android_ic_profile'))
            .width(28)
            .height(28)
            .fillColor('#4A90E2')
        }
        .width(56)
        .height(56)
        .borderRadius(28)
        .backgroundColor('#EBF2FF')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)

        Column() {
          Text('学习者')
            .fontSize(17)
            .fontWeight(FontWeight.Bold)
            .fontColor(AppColors.TitleColor)
          Text('让每一次观影，都成为积累')
            .fontSize(13)
            .fontColor(AppColors.SubTextColor)
            .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 14 })

        Image($r('app.media.ic_chevron_gray'))
          .width(20)
          .height(20)
      }
      .width('100%')
      .padding(16)
      .borderRadius(16)
      .backgroundColor(AppColors.CardBg)
      .alignItems(VerticalAlign.Center)
      .onClick(() => router.pushUrl({ url: 'pages/membership' }))

      Row() {
        Column() {
          Image($r('app.media.android_ic_profile_bookmark'))
            .width(24)
            .height(24)
            .fillColor('#4A90E2')
        }
        .width(48)
        .height(48)
        .borderRadius(12)
        .backgroundColor('#EBF2FF')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)

        Column() {
          Text('我的收藏')
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor(AppColors.TitleColor)
          Text('收藏的单词与电影例句')
            .fontSize(13)
            .fontColor(AppColors.SubTextColor)
            .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 14 })

        Image($r('app.media.ic_chevron_gray'))
          .width(20)
          .height(20)
      }
      .width('100%')
      .padding(16)
      .borderRadius(16)
      .backgroundColor(AppColors.CardBg)
      .margin({ top: 12 })
      .alignItems(VerticalAlign.Center)
      .onClick(() => {
        router.pushUrl({ url: 'pages/wordbook/book_detail', params: { from: 'favorites' } });
      })

      Row() {
        Column() {
          Image($r('app.media.android_ic_profile_bookmark'))
            .width(24)
            .height(24)
            .fillColor('#F59E0B')
        }
        .width(48)
        .height(48)
        .borderRadius(12)
        .backgroundColor('#FFF8E1')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)

        Column() {
          Text('复习计划')
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor(AppColors.TitleColor)
          Text('查看错题与今日复习任务')
            .fontSize(13)
            .fontColor(AppColors.SubTextColor)
            .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 14 })

        Image($r('app.media.ic_chevron_gray'))
          .width(20)
          .height(20)
      }
      .width('100%')
      .padding(16)
      .borderRadius(16)
      .backgroundColor(AppColors.CardBg)
      .margin({ top: 12 })
      .alignItems(VerticalAlign.Center)
      .onClick(() => router.pushUrl({ url: 'pages/wordbook/book_detail', params: { from: 'review' } }))

      Column() {
        Text('单词闪卡初始化设置')
          .fontSize(16)
          .fontWeight(FontWeight.Bold)
          .fontColor(AppColors.TitleColor)
          .width('100%')
        Text('进入每张闪卡时，默认显示的内容')
          .fontSize(13)
          .fontColor(AppColors.SubTextColor)
          .margin({ top: 4 })

        Row() {
          Text('显示单词释义')
            .fontSize(15)
            .fontColor(AppColors.TitleColor)
            .layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: this.showMeaning })
            .selectedColor('#3B7FFF')
            .onChange((isOn: boolean) => {
              this.showMeaning = isOn;
              const context = this.getUIContext().getHostContext();
              if (context) FlashcardSettingsStore.saveShowMeaning(context as common.Context, isOn);
            })
        }
        .width('100%')
        .height(48)
        .alignItems(VerticalAlign.Center)
        .margin({ top: 16 })

        Row() {
          Text('显示例句')
            .fontSize(15)
            .fontColor(AppColors.TitleColor)
            .layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: this.showExample })
            .selectedColor('#3B7FFF')
            .onChange((isOn: boolean) => {
              this.showExample = isOn;
              const context = this.getUIContext().getHostContext();
              if (context) FlashcardSettingsStore.saveShowExample(context as common.Context, isOn);
            })
        }
        .width('100%')
        .height(48)
        .alignItems(VerticalAlign.Center)

        Row() {
          Text('答题模式')
            .fontSize(15)
            .fontColor(AppColors.TitleColor)
            .layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: this.quizMode })
            .selectedColor('#3B7FFF')
            .onChange((isOn: boolean) => {
              this.quizMode = isOn;
              const context = this.getUIContext().getHostContext();
              if (context) FlashcardSettingsStore.saveQuizMode(context as common.Context, isOn);
            })
        }
        .width('100%')
        .height(48)
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .padding(16)
      .borderRadius(16)
      .backgroundColor(AppColors.CardBg)
      .margin({ top: 12 })
      .alignItems(HorizontalAlign.Start)

      Column() {
        Row() {
          Text('云端存储')
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor(AppColors.TitleColor)
          Blank()
          this.VipBadge()
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)

        Row() {
          Text('已用 2.4 GB / 10 GB')
            .fontSize(13)
            .fontColor(AppColors.SubTextColor)
            .layoutWeight(1)
          Text('24%')
            .fontSize(13)
            .fontWeight(FontWeight.Bold)
            .fontColor('#3B7FFF')
        }
        .width('100%')
        .margin({ top: 12 })
        .alignItems(VerticalAlign.Center)

        Progress({ value: 24, total: 100, type: ProgressType.Linear })
          .width('100%')
          .height(6)
          .color('#3B7FFF')
          .backgroundColor('#E8E8ED')
          .margin({ top: 8 })
      }
      .width('100%')
      .padding(16)
      .borderRadius(16)
      .backgroundColor(AppColors.CardBg)
      .margin({ top: 12 })
      .alignItems(HorizontalAlign.Start)

      Column() {
        Row() {
          Text('华为云语音解析')
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor(AppColors.TitleColor)
          Blank()
          this.VipBadge()
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)

        Row() {
          Text('已用 20 分钟 / 120 分钟')
            .fontSize(13)
            .fontColor(AppColors.SubTextColor)
            .layoutWeight(1)
          Text('17%')
            .fontSize(13)
            .fontWeight(FontWeight.Bold)
            .fontColor('#3B7FFF')
        }
        .width('100%')
        .margin({ top: 12 })
        .alignItems(VerticalAlign.Center)

        Progress({ value: 17, total: 100, type: ProgressType.Linear })
          .width('100%')
          .height(6)
          .color('#3B7FFF')
          .backgroundColor('#E8E8ED')
          .margin({ top: 8 })
      }
      .width('100%')
      .padding(16)
      .borderRadius(16)
      .backgroundColor(AppColors.CardBg)
      .margin({ top: 12 })
      .alignItems(HorizontalAlign.Start)

      Column() {
        Row() {
          Text('词形还原（新上传视频生效）')
            .fontSize(15)
            .fontColor(AppColors.TitleColor)
            .layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: this.lemmatization })
            .selectedColor('#3B7FFF')
            .onChange((isOn: boolean) => {
              this.lemmatization = isOn;
              const context = this.getUIContext().getHostContext();
              if (context) FlashcardSettingsStore.saveLemmatization(context as common.Context, isOn);
            })
        }
        .width('100%')
        .height(48)
        .alignItems(VerticalAlign.Center)

        Row() {
          Text('错题自动收入复习计划')
            .fontSize(15)
            .fontColor(AppColors.TitleColor)
            .layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: this.autoAddWrongToReview })
            .selectedColor('#3B7FFF')
            .onChange((isOn: boolean) => {
              this.autoAddWrongToReview = isOn;
              const context = this.getUIContext().getHostContext();
              if (context) FlashcardSettingsStore.saveAutoAddWrongToReview(context as common.Context, isOn);
            })
        }
        .width('100%')
        .height(48)
        .alignItems(VerticalAlign.Center)

        Row({ space: 12 }) {
          Column({ space: 4 }) {
            Text('识别模型').fontSize(15).fontColor(AppColors.TitleColor)
            Text('新上传视频生效').fontSize(12).fontColor(AppColors.SubTextColor)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Button({ type: ButtonType.Normal }) {
            Row({ space: 8 }) {
              Text(this.modelSelection).fontSize(14).fontWeight(FontWeight.Medium).fontColor('#315DAB')
              if (this.modelSelection === '华为云') { this.VipBadge() }
              Image($r('app.media.ic_chevron_gray')).width(16).height(16).rotate({ angle: 90 })
            }.justifyContent(FlexAlign.Center)
          }
          .height(40).padding({ left: 14, right: 12 }).borderRadius(12)
          .backgroundColor('#EEF4FF').border({ width: 1, color: '#DFE9FA' })
          .accessibilityText('选择识别模型，当前为' + this.modelSelection)
          .bindMenu(this.ModelMenu())
        }
        .width('100%').constraintSize({ minHeight: 68 }).alignItems(VerticalAlign.Center)

        Row() {
          Text('关于')
            .fontSize(15)
            .fontColor(AppColors.TitleColor)
            .layoutWeight(1)
          Image($r('app.media.ic_chevron_gray'))
            .width(20)
            .height(20)
        }
        .width('100%')
        .height(48)
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 4, bottom: 4 })
      .borderRadius(16)
      .backgroundColor(AppColors.CardBg)
      .margin({ top: 12 })
      .alignItems(HorizontalAlign.Start)


    }
    .width('100%')
  }

  build() {
    Scroll() {
      Column() {
        Text('我的')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(AppColors.TitleColor)
          .width('100%')
          .textAlign(TextAlign.Center)
          .padding({ top: 20, bottom: 16 })

        this.CardContainer()
      }
      .width('100%')
      .padding({ left: 16, right: 16 })
    }
    .width('100%')
    .height('100%')
    .scrollBar(BarState.Off)
    .backgroundColor(AppColors.PageBg)
  }
}
