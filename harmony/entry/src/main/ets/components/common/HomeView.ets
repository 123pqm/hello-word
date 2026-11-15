import { RecentStudyCard } from './RecentStudyCard';
import { AppColors, AppSpacing } from '../../common/AppTheme';
import { router } from '@kit.ArkUI';
import { CheckinViewModel } from '../../viewmodels/CheckinViewModel';
import { CheckinDay } from '../../models/UserModels';

@Component
export struct HomeView {
  @Prop @Watch('visibilityChanged') active: boolean = true;
  onNavigateToMovie: () => void = () => {};
  onNavigateToAI: () => void = () => {};
  @StorageLink('access_token') @Watch('sessionChanged') token: string = '';
  @State private checkinModel: CheckinViewModel = new CheckinViewModel();
  private mounted: boolean = false;

  aboutToAppear(): void {
    this.mounted = true;
    if (this.active) this.checkinModel.start();
  }

  aboutToDisappear(): void {
    this.mounted = false;
    this.checkinModel.dispose();
  }

  visibilityChanged(): void {
    if (!this.mounted) return;
    if (this.active) this.checkinModel.start();
    else this.checkinModel.stop();
  }

  sessionChanged(): void {
    if (this.mounted && this.active) this.checkinModel.start();
  }

  private weekdayLabel(weekday: number): string {
    if (weekday === 1) return '一';
    if (weekday === 2) return '二';
    if (weekday === 3) return '三';
    if (weekday === 4) return '四';
    if (weekday === 5) return '五';
    if (weekday === 6) return '六';
    return '日';
  }

  private formatDate(date: string): string {
    if (date.length >= 10) {
      return date.substring(5, 7) + '/' + date.substring(8, 10);
    }
    return date;
  }

  build() {
    Scroll() {
      Column() {
        Row() {
          Column() {
            Text('早上好！')
              .fontSize(28)
              .fontWeight(FontWeight.Bold)
              .fontColor(AppColors.TitleColor)
            Text('今天也要一起加油学习呀')
              .fontSize(14)
              .fontColor(AppColors.SubTextColor)
              .margin({ top: 6 })
          }
          .alignItems(HorizontalAlign.Start)

          Image($r('app.media.android_vh_robot'))
            .width(56)
            .height(56)
            .onClick(() => this.onNavigateToAI())
        }
        .width('100%')
        .justifyContent(FlexAlign.SpaceBetween)
        .alignItems(VerticalAlign.Center)
        .padding({ left: AppSpacing.PagePadding, right: AppSpacing.PagePadding, top: 20 })

        Column() {
          Row() {
            Image($r('app.media.android_vh_plus'))
              .width(48)
              .height(48)

            Column() {
              Text('新建学习任务')
                .fontSize(21)
                .fontWeight(FontWeight.Bold)
                .fontColor('#FFFFFF')
              Text('上传电影，AI 分析生词')
                .fontSize(14)
                .fontColor(AppColors.White80)
                .margin({ top: 4 })
            }
            .alignItems(HorizontalAlign.Start)
            .margin({ left: 12 })
            .layoutWeight(1)

            Image($r('app.media.android_vh_chevron'))
              .width(20)
              .height(20)
          }
          .width('100%')
          .height(AppSpacing.TaskCardHeight)
          .padding({ left: 20, right: 20 })
          .borderRadius(AppSpacing.TaskCardRadius)
          .linearGradient({ angle: 135, colors: [['#3973FF', 0.0], ['#6088FF', 1.0]] })
          .shadow({ radius: 12, color: AppColors.ShadowColor, offsetX: 0, offsetY: 4 })
          .alignItems(VerticalAlign.Center)
        }
        .width('100%')
        .padding({ left: AppSpacing.PagePadding, right: AppSpacing.PagePadding, top: AppSpacing.SectionGap })
        .onClick(() => {
          router.pushUrl({ url: 'pages/study/Study' });
        })

        RecentStudyCard({ active: this.active, onNavigateToMovie: this.onNavigateToMovie })

        Column() {
          Text('今日学习')
            .fontSize(20)
            .fontWeight(FontWeight.Bold)
            .fontColor(AppColors.TitleColor)
            .width('100%')

          Column() {
            Row() {
              Text('学习打卡')
                .fontSize(16)
                .fontWeight(FontWeight.Bold)
                .fontColor(AppColors.TitleColor)

              if (this.checkinModel.data) {
                Row() {
                  Text('连续登录 ')
                    .fontSize(13)
                    .fontColor(AppColors.SubTextColor)
                  Text(`${this.checkinModel.data.streak_days}`)
                    .fontSize(18)
                    .fontWeight(FontWeight.Bold)
                    .fontColor(AppColors.Primary)
                  Text(' 天')
                    .fontSize(13)
                    .fontColor(AppColors.SubTextColor)
                }
                .alignItems(VerticalAlign.Center)
              } else {
                Row() {
                  Text('连续登录 ')
                    .fontSize(13)
                    .fontColor(AppColors.SubTextColor)
                  Text('--')
                    .fontSize(18)
                    .fontWeight(FontWeight.Bold)
                    .fontColor(AppColors.SubTextColor)
                  Text(' 天')
                    .fontSize(13)
                    .fontColor(AppColors.SubTextColor)
                }
                .alignItems(VerticalAlign.Center)
              }
            }
            .width('100%')
            .justifyContent(FlexAlign.SpaceBetween)
            .alignItems(VerticalAlign.Center)

            if (this.checkinModel.loading && !this.checkinModel.data) {
              Column() {
                LoadingProgress()
                  .width(24)
                  .height(24)
                Text('加载中…')
                  .fontSize(13)
                  .fontColor(AppColors.SubTextColor)
                  .margin({ top: 8 })
              }
              .width('100%')
              .padding({ top: 40, bottom: 40 })
              .alignItems(HorizontalAlign.Center)
            } else if (this.checkinModel.error && !this.checkinModel.data) {
              Column() {
                Text(this.checkinModel.error)
                  .fontSize(13)
                  .fontColor('#C65D64')
                Text('点击重试')
                  .fontSize(13)
                  .fontColor(AppColors.Primary)
                  .margin({ top: 8 })
                  .onClick(() => {
                    this.checkinModel.start();
                  })
              }
              .width('100%')
              .padding({ top: 40, bottom: 40 })
              .alignItems(HorizontalAlign.Center)
            } else if (this.checkinModel.data) {
              Column() {
                Row() {
                  ForEach(this.checkinModel.data.days, (day: CheckinDay) => {
                    Text(this.weekdayLabel(day.weekday))
                      .fontSize(12)
                      .fontColor(day.is_today ? AppColors.Primary : AppColors.WeekTextColor)
                      .fontWeight(day.is_today ? FontWeight.Bold : FontWeight.Normal)
                      .layoutWeight(1)
                      .textAlign(TextAlign.Center)
                  }, (day: CheckinDay) => day.date)
                }
                .width('100%')
                .margin({ top: 16 })

                Row() {
                  ForEach(this.checkinModel.data.days, (day: CheckinDay) => {
                    Text(this.formatDate(day.date))
                      .fontSize(11)
                      .fontColor(day.is_today ? AppColors.Primary : (day.checked ? AppColors.WeekTextColor : AppColors.DateInactiveColor))
                      .fontWeight(day.is_today ? FontWeight.Bold : FontWeight.Normal)
                      .layoutWeight(1)
                      .textAlign(TextAlign.Center)
                  }, (day: CheckinDay) => day.date)
                }
                .width('100%')
                .margin({ top: 8 })

                Row() {
                  ForEach(this.checkinModel.data.days, (day: CheckinDay) => {
                    Column() {
                      if (day.checked) {
                        Image($r('app.media.android_vh_check'))
                          .width(28)
                          .height(28)
                      } else {
                        Column()
                          .width(28)
                          .height(28)
                          .borderRadius(14)
                          .border({ width: 1.5, color: AppColors.CircleBorderColor })
                      }
                    }
                    .layoutWeight(1)
                    .alignItems(HorizontalAlign.Center)
                  }, (day: CheckinDay) => day.date)
                }
                .width('100%')
                .margin({ top: 10 })
              }
              .width('100%')
              .margin({ top: 16 })

              if (this.checkinModel.data.days.some((day: CheckinDay) => day.is_today && day.checked)) {
                Text(`本周已登录 ${this.checkinModel.data.week_count} 天 · 今天已打卡`)
                  .fontSize(12)
                  .fontColor(AppColors.SubTextColor)
                  .width('100%')
                  .textAlign(TextAlign.Center)
                  .margin({ top: 16 })
              } else {
                Text(`本周已登录 ${this.checkinModel.data.week_count} 天 · 今天未打卡`)
                  .fontSize(12)
                  .fontColor(AppColors.SubTextColor)
                  .width('100%')
                  .textAlign(TextAlign.Center)
                  .margin({ top: 16 })
              }
            }
          }
          .width('100%')
          .padding(20)
          .borderRadius(20)
          .backgroundColor(AppColors.CardBg)
          .margin({ top: AppSpacing.TitleToCard })
        }
        .width('100%')
        .padding({ left: AppSpacing.PagePadding, right: AppSpacing.PagePadding, top: AppSpacing.SectionGap, bottom: 20 })
      }
      .width('100%')
    }
    .width('100%')
    .height('100%')
    .scrollBar(BarState.Off)
    .backgroundColor(AppColors.PageBg)
  }
}