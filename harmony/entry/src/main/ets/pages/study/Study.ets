import { router } from '@kit.ArkUI';

@Entry
@Component
struct Study {
  @State selectedBook: number = 0;

  private readonly PageBg: string = '#F5F7FA';
  private readonly CardBg: string = '#FFFFFF';
  private readonly PrimaryColor: string = '#3B7FFF';
  private readonly TitleColor: string = '#1A1A1A';
  private readonly SubTextColor: string = '#8A8F99';
  private readonly CircleBorderColor: string = '#D1D5DB';
  private readonly CardBorder: string = '#EFF0F4';

  @Builder
  BookItem(index: number, iconColor: string, iconText: string, title: string, subtitle: string) {
    Row() {
      Column() {
        Text(iconText)
          .fontSize(14)
          .fontWeight(FontWeight.Bold)
          .fontColor('#FFFFFF')
      }
      .width(54)
      .height(76)
      .borderRadius(10)
      .backgroundColor(iconColor)
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)

      Column() {
        Text(title)
          .fontSize(17)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.TitleColor)
        Text(subtitle)
          .fontSize(13)
          .fontColor(this.SubTextColor)
          .margin({ top: 6 })
        Text(index === this.selectedBook ? '已选择此词书' : '点击选择')
          .fontSize(12)
          .fontColor(index === this.selectedBook ? this.PrimaryColor : this.SubTextColor)
          .margin({ top: 6 })
      }
      .alignItems(HorizontalAlign.Start)
      .justifyContent(FlexAlign.Center)
      .layoutWeight(1)
      .margin({ left: 14, right: 12 })

      if (index === this.selectedBook) {
        Image($r('app.media.android_vh_check'))
          .width(22)
          .height(22)
      } else {
        Column()
          .width(22)
          .height(22)
          .borderRadius(11)
          .border({ width: 1.5, color: this.CircleBorderColor })
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 18, bottom: 18 })
    .borderRadius(18)
    .backgroundColor(index === this.selectedBook ? '#F0F5FF' : this.CardBg)
    .border({ width: index === this.selectedBook ? 1.5 : 1, color: index === this.selectedBook ? this.PrimaryColor : this.CardBorder })
    .margin({ top: index === 0 ? 0 : 14 })
    .alignItems(VerticalAlign.Center)
    .onClick(() => {
      this.selectedBook = index;
      router.pushUrl({
        url: 'pages/wordbook/book_detail',
        params: { book_id: index, from: 'study' }
      });
    })
  }
 
  build() {
    Column() {
      Row() {
        Button({ type: ButtonType.Normal }) {
          Image($r('app.media.ic_chevron_left_gray'))
            .width(24)
            .height(24)
        }
          .width(44)
          .height(44)
          .backgroundColor(Color.Transparent)
          .borderRadius(22)
          .accessibilityText('返回')
          .onClick(() => {
            router.back();
          })

        Text('选择词书')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.TitleColor)
          .layoutWeight(1)
          .textAlign(TextAlign.Center)

        Column()
          .width(44)
      }
      .width('100%')
      .height(56)
      .padding({ left: 6, right: 6 })
      .alignItems(VerticalAlign.Center)

      Scroll() {
        Column() {
          Text('选择一本词书，开始你的电影学习之旅。')
            .fontSize(14)
            .fontColor(this.SubTextColor)
            .lineHeight(22)
            .width('100%')
            .textAlign(TextAlign.Start)
            .margin({ bottom: 20 })

          this.BookItem(0, '#4A7DFF', 'CET 4', 'CET-4', '大学英语四级词库')
          this.BookItem(1, '#F5A623', '考研', '考研词汇全表', '5530 词条 · 2024 考纲词表')
          this.BookItem(2, '#8B6DD7', '词频', '考研词汇·词频优先', '2444 词条 · 混合考试语料词频 ≥ 40')
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
        .justifyContent(FlexAlign.Start)
        .padding({ left: 16, right: 16, top: 12, bottom: 24 })
      }
      .width('100%')
      .layoutWeight(1)
      // 内容不足一屏时仍从顶部开始，避免 Scroll 默认居中留下大块空白。
      .align(Alignment.TopStart)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Start)
    .padding({ top: 8 })
    .backgroundColor(this.PageBg)
  }
}
