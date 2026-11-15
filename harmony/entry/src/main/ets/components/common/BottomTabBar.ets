@Component
export struct BottomTabBar {
  @Prop currentTab: number = 0;
  onTabChange: (index: number) => void = (index: number): void => {};

  private readonly PrimaryColor: string = '#5B6BF0';
  private readonly InactiveColor: string = '#999999';
  private readonly ActiveTextColor: string = '#5B6BF0';
  private readonly InactiveTextColor: string = '#666666';
  private readonly BgColor: string = '#FFFFFF';

  build() {
    Column() {
      Divider()
        .color('#E5E8EF')
        .strokeWidth(1)

      Row() {
        Column() {
          Image($r('app.media.android_ic_home'))
            .width(24)
            .height(24)
            .fillColor(this.currentTab === 0 ? this.PrimaryColor : this.InactiveColor)
          Text('首页')
            .fontSize(11)
            .fontColor(this.currentTab === 0 ? this.ActiveTextColor : this.InactiveTextColor)
            .margin({ top: 4 })
        }
        .layoutWeight(1)
        .onClick(() => {
          this.onTabChange(0);
        })

        Column() {
          Image($r('app.media.android_ic_words'))
            .width(24)
            .height(24)
            .fillColor(this.currentTab === 1 ? this.PrimaryColor : this.InactiveColor)
          Text('我的词书')
            .fontSize(11)
            .fontColor(this.currentTab === 1 ? this.ActiveTextColor : this.InactiveTextColor)
            .margin({ top: 4 })
        }
        .layoutWeight(1)
        .onClick(() => {
          this.onTabChange(1);
        })

        Column() {
          Stack({ alignContent: Alignment.Center }) {
            Image($r('app.media.android_vh_robot'))
              .width(56)
              .height(56)
              .objectFit(ImageFit.Contain)
          }
          .width(64)
          .height(64)
          .borderRadius(32)
          .backgroundColor(this.currentTab === 2 ? '#DFEBFF' : '#EAF2FF')
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)
        .accessibilityText('AI助手')
        .onClick(() => {
          this.onTabChange(2);
        })

        Column() {
          Image($r('app.media.android_ic_video'))
            .width(24)
            .height(24)
            .fillColor(this.currentTab === 3 ? this.PrimaryColor : this.InactiveColor)
          Text('我的观影')
            .fontSize(11)
            .fontColor(this.currentTab === 3 ? this.ActiveTextColor : this.InactiveTextColor)
            .margin({ top: 4 })
        }
        .layoutWeight(1)
        .onClick(() => {
          this.onTabChange(3);
        })

        Column() {
          Image($r('app.media.android_ic_profile'))
            .width(24)
            .height(24)
            .fillColor(this.currentTab === 4 ? this.PrimaryColor : this.InactiveColor)
          Text('我的')
            .fontSize(11)
            .fontColor(this.currentTab === 4 ? this.ActiveTextColor : this.InactiveTextColor)
            .margin({ top: 4 })
        }
        .layoutWeight(1)
        .onClick(() => {
          this.onTabChange(4);
        })
      }
      .width('100%')
      .height(76)
      .justifyContent(FlexAlign.SpaceEvenly)
      .alignItems(VerticalAlign.Center)
      .backgroundColor(this.BgColor)
    }
  }
}
