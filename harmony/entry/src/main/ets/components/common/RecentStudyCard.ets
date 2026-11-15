import { router } from '@kit.ArkUI';
import { AppColors, AppSpacing } from '../../common/AppTheme';
import { RecentStudyViewModel } from '../../viewmodels/RecentStudyViewModel';

@Component
export struct RecentStudyCard {
  @Prop @Watch('visibilityChanged') active: boolean = true;
  onNavigateToMovie: () => void = () => {};
  @StorageLink('access_token') @Watch('sessionChanged') token: string = '';
  @State private model: RecentStudyViewModel = new RecentStudyViewModel();
  private mounted: boolean = false;

  aboutToAppear(): void {
    this.mounted = true;
    if (this.active) this.model.start();
  }

  aboutToDisappear(): void {
    this.mounted = false;
    this.model.dispose();
  }

  visibilityChanged(): void {
    if (!this.mounted) return;
    if (this.active) this.model.start();
    else { this.model.stop(); }
  }

  sessionChanged(): void {
    if (this.mounted && this.active) this.model.start();
    else this.model.dispose();
  }

  build() {
    Column() {
      Row() {
        Text('最近学习').fontSize(20).fontWeight(FontWeight.Bold).fontColor(AppColors.TitleColor)
        Button(this.model.loading ? '刷新中…' : '刷新', { type: ButtonType.Normal })
          .fontSize(13).fontColor(AppColors.Primary).backgroundColor(Color.Transparent)
          .enabled(!this.model.loading && !this.model.loginRequired)
          .onClick(() => this.model.refresh())
      }.width('100%').justifyContent(FlexAlign.SpaceBetween).alignItems(VerticalAlign.Center)

      Column() {
        if (this.model.loginRequired) {
          Text(this.model.error).fontSize(14).fontColor(AppColors.SubTextColor)
          Button('重新登录').margin({ top: 12 }).onClick(() => {
            this.model.stop();
            router.replaceUrl({ url: 'pages/Index' });
          })
        } else if (this.model.loading && !this.model.recent) {
          LoadingProgress().width(28).height(28)
          Text('正在加载学习记录…').fontSize(14).fontColor(AppColors.SubTextColor)
        } else if (this.model.recent) {
          Row({ space: 12 }) {
            if (this.model.cover) {
              Image(this.model.cover).width(88).height(116).borderRadius(12).objectFit(ImageFit.Cover)
            } else {
              Column() {
                Image($r('app.media.android_ic_video')).width(32).height(32).fillColor('#8BA7D4')
                Text('电影任务').fontSize(11).fontColor(AppColors.SubTextColor).margin({ top: 8 })
              }.width(88).height(116).backgroundColor('#EDF3FB').borderRadius(12).justifyContent(FlexAlign.Center)
            }
            Column({ space: 6 }) {
              Text(this.model.recent?.file_name || '未命名视频')
                .fontSize(17).fontWeight(FontWeight.Bold).fontColor(AppColors.TitleColor)
                .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(`上传于 ${(this.model.recent?.created_at ?? '').replace('T', ' ').slice(0, 19)}`)
                .fontSize(12).fontColor(AppColors.SubTextColor)
              Text(this.model.statusText()).fontSize(13)
                .fontColor(this.model.recent?.status === 'failed' ? '#C44B43' : AppColors.Primary)
              if (this.model.matchedCount >= 0) {
                Text(`匹配 ${this.model.matchedCount} 个词条`).fontSize(12).fontColor(AppColors.SubTextColor)
              }
              if (this.model.recent?.status === 'completed') {
                Button('查看历史记录', { type: ButtonType.Normal })
                  .fontSize(13).fontColor(AppColors.Primary).backgroundColor('#EDF3FF').borderRadius(8)
                  .onClick(() => {
                    this.onNavigateToMovie();
                  })
              } else if (this.model.recent?.status === 'failed') {
                Text(this.model.failureReason || '分析失败，可重新创建任务').fontSize(12).fontColor('#C44B43')
              } else {
                Text('后台处理中，状态会自动更新').fontSize(12).fontColor(AppColors.SubTextColor)
              }
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          }.width('100%').alignItems(VerticalAlign.Top)
        } else if (!this.model.error) {
          Text('还没有学习记录').fontSize(16).fontColor(AppColors.TitleColor)
          Text('上传一部电影，开启第一次学习').fontSize(13).fontColor(AppColors.SubTextColor).margin({ top: 8 })
          Button('新建学习任务').margin({ top: 12 }).onClick(() => router.pushUrl({ url: 'pages/study/Study' }))
        }
        if (this.model.error && !this.model.loginRequired) {
          Text(this.model.error).fontSize(13).fontColor('#C44B43').margin({ top: 10 })
          Button('重新加载').enabled(!this.model.loading).margin({ top: 8 }).onClick(() => this.model.refresh())
        }
      }
      .width('100%').padding(14).borderRadius(AppSpacing.CardRadius)
      .backgroundColor(AppColors.CardBg).border({ width: 1, color: AppColors.CardBorder })
      .margin({ top: AppSpacing.TitleToCard })
    }
    .width('100%')
    .padding({ left: AppSpacing.PagePadding, right: AppSpacing.PagePadding, top: AppSpacing.SectionGap })

  }
}
