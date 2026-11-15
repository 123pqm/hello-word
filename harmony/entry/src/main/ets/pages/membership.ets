import { router } from '@kit.ArkUI';
import { AuthService } from '../service/AuthService';

@Entry
@Component
struct MembershipPage {
  @StorageLink('access_token') token: string = '';
  @State private vipPlan: number = 1;
  @State private storagePlan: number = 0;
  @State private speechPlan: number = 0;
  @State private logoutLoading: boolean = false;

  private showComingSoon(): void {
    this.getUIContext().getPromptAction().showToast({ message: '购买服务即将开放，敬请期待' });
  }

  private logout(): void {
    if (this.logoutLoading) return;
    this.getUIContext().showAlertDialog({
      title: '退出登录', message: '确定退出当前账号吗？本机已保存的学习进度会保留。',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: { value: '退出', action: () => { this.submitLogout(); } }
    });
  }

  private async submitLogout(): Promise<void> {
    if (this.logoutLoading) return;
    const token = this.token;
    this.logoutLoading = true;
    try {
      await AuthService.logout(token);
      if (this.token !== token) return;
      AppStorage.setOrCreate<number>('user_id', 0);
      AppStorage.set<string>('access_token', '');
      await router.replaceUrl({ url: 'pages/Index' });
      router.clear();
    } catch (error) {
      this.getUIContext().getPromptAction().showToast({ message: (error as Error).message || '退出失败，请重试' });
    } finally { this.logoutLoading = false; }
  }

  @Builder
  VipOption(index: number, title: string, subtitle: string) {
    Column({ space: 8 }) {
      Text(title).fontSize(17).fontWeight(FontWeight.Bold).fontColor(this.vipPlan === index ? '#F6DCA7' : '#E0E5EE')
      Text(subtitle).fontSize(12).fontColor(this.vipPlan === index ? '#CFB989' : '#A6B2C5')
      Text(this.vipPlan === index ? '已选择' : '选择套餐').fontSize(11)
        .fontColor(this.vipPlan === index ? '#F6DCA7' : '#A6B2C5').margin({ top: 4 })
    }.layoutWeight(1).padding({ top: 18, bottom: 18 }).borderRadius(16)
      .backgroundColor(this.vipPlan === index ? '#3F4250' : '#2B374D')
      .border({ width: 1, color: this.vipPlan === index ? '#DCC28D' : '#435067' })
      .onClick(() => { this.vipPlan = index; })
  }

  @Builder
  CapacityOption(index: number, title: string, subtitle: string, speech: boolean) {
    Column({ space: 6 }) {
      Text(title).fontSize(20).fontWeight(FontWeight.Bold)
        .fontColor((speech ? this.speechPlan : this.storagePlan) === index ? '#3478F6' : '#27354B')
      Text(subtitle).fontSize(11).fontColor('#8591A4')
    }.layoutWeight(1).padding({ top: 16, bottom: 16 }).borderRadius(14)
      .backgroundColor((speech ? this.speechPlan : this.storagePlan) === index ? '#EFF5FF' : '#F7F9FC')
      .border({ width: 1, color: (speech ? this.speechPlan : this.storagePlan) === index ? '#8DB5FF' : '#EDF0F5' })
      .onClick(() => { if (speech) this.speechPlan = index; else this.storagePlan = index; })
  }

  @Builder
  ServiceCard(speech: boolean) {
    Column({ space: 16 }) {
      Row({ space: 12 }) {
        Text(speech ? '声' : '云').fontSize(18).fontWeight(FontWeight.Bold)
          .fontColor(speech ? '#8060CE' : '#3478F6').width(44).height(44).textAlign(TextAlign.Center)
          .backgroundColor(speech ? '#F2EDFF' : '#EBF3FF').borderRadius(14)
        Column({ space: 5 }) {
          Text(speech ? '华为云语音解析' : '云端存储扩容').fontSize(17).fontWeight(FontWeight.Bold).fontColor('#25344B')
          Text(speech ? '更多解析时长，积累更多电影词汇' : '为更多视频和学习资料留出空间').fontSize(12).fontColor('#8793A5')
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      }.width('100%')
      Row({ space: 10 }) {
        this.CapacityOption(0, speech ? '60 分钟' : '20 GB', speech ? '轻量补充' : '轻装起步', speech)
        this.CapacityOption(1, speech ? '300 分钟' : '100 GB', speech ? '持续学习' : '充裕空间', speech)
        this.CapacityOption(2, speech ? '600 分钟' : '200 GB', speech ? '深度探索' : '海量收藏', speech)
      }.width('100%')
      Button(speech ? '增加解析时长' : '扩容存储空间').width('100%').height(44).borderRadius(13)
        .backgroundColor(speech ? '#F1EDFA' : '#EDF3FE').fontColor(speech ? '#7655BC' : '#3478F6')
        .fontSize(14).fontWeight(FontWeight.Medium).onClick(() => this.showComingSoon())
    }.width('100%').padding(18).borderRadius(22).backgroundColor(Color.White)
  }

  build() {
    Column() {
      Row() {
        Button('‹', { type: ButtonType.Normal }).width(44).height(44).fontSize(32)
          .fontColor('#25344B').backgroundColor(Color.Transparent).onClick(() => router.back()).accessibilityText('返回')
        Text('会员与服务').fontSize(19).fontWeight(FontWeight.Bold).fontColor('#25344B')
          .layoutWeight(1).textAlign(TextAlign.Center)
        Blank().width(44)
      }.width('100%').height(56).padding({ left: 8, right: 8 })
      Scroll() {
        Column({ space: 18 }) {
          Row({ space: 12 }) {
            Image($r('app.media.android_ic_profile')).width(44).height(44).padding(10)
              .backgroundColor('#E7EFFD').borderRadius(15).fillColor('#3478F6')
            Column({ space: 4 }) {
              Text('学习者').fontSize(19).fontWeight(FontWeight.Bold).fontColor('#25344B')
              Text('让每一次观影，都成为积累').fontSize(12).fontColor('#8793A5')
            }.alignItems(HorizontalAlign.Start)
          }.width('100%').padding({ left: 4, top: 6, bottom: 2 })

          Column({ space: 20 }) {
            Row({ space: 8 }) {
              Image($r('app.media.vip_badge_crown')).width(24).height(24)
              Text('VIP MEMBERSHIP').fontSize(12).fontWeight(FontWeight.Bold).fontColor('#E9D2A7').letterSpacing(2)
              Blank()
              Text('会员计划').fontSize(11).fontColor('#C8B48E')
            }.width('100%')
            Column({ space: 8 }) {
              Text('给热爱，多一点空间').fontSize(25).fontWeight(FontWeight.Bold).fontColor('#FFF2D9')
              Text('云端收藏 · 语音解析 · 沉浸学习').fontSize(13).fontColor('#B8C2D2')
            }.width('100%').alignItems(HorizontalAlign.Start)
            Row({ space: 12 }) {
              this.VipOption(0, '月度会员', '轻松体验，随心学习')
              this.VipOption(1, '年度会员', '长久陪伴，持续积累')
            }.width('100%')
            Button('开通 VIP').width('100%').height(48).borderRadius(14).fontSize(16).fontWeight(FontWeight.Bold)
              .fontColor('#594226').linearGradient({ angle: 90, colors: [['#FAE6BF', 0], ['#D9BA7F', 1]] })
              .onClick(() => this.showComingSoon())
          }.width('100%').padding(22).borderRadius(24)
            .linearGradient({ angle: 135, colors: [['#202D44', 0], ['#37465F', 1]] })

          this.ServiceCard(false)
          this.ServiceCard(true)
          Text('套餐展示 · 价格与权益以正式上线为准').fontSize(11).fontColor('#9AA4B4').textAlign(TextAlign.Center)
          Button(this.logoutLoading ? '正在退出…' : '退出登录').width('100%').height(50)
            .borderRadius(16).backgroundColor('#FFFFFF').fontColor('#CB5B61').fontSize(15)
            .enabled(!this.logoutLoading).onClick(() => this.logout()).margin({ top: 6 })
        }.width('100%').padding({ left: 18, right: 18, top: 4, bottom: 32 })
      }.layoutWeight(1).width('100%').scrollBar(BarState.Off)
    }.width('100%').height('100%').backgroundColor('#F4F6FA')
  }
}
