import { AuthService, LoginResponse, SignResponse } from '../service/AuthService';
import { router } from '@kit.ArkUI';

@Entry
@Component
struct Index {
  @State isLogin: boolean = true;
  @State account: string = '';
  @State password: string = '';
  @State isLoading: boolean = false;
  @State errorMsg: string = '';

  private readonly PageBgColor: string = '#F0F2F5';
  private readonly CardBgColor: string = '#FFFFFF';
  private readonly InputBgColor: string = '#F5F7FA';
  private readonly InputBorderColor: string = '#E5E8EF';
  private readonly PrimaryColor: string = '#5B6BF0';
  private readonly TitleColor: string = '#2C2C34';
  private readonly TextColor: string = '#333333';
  private readonly SubTextColor: string = '#999999';
  private readonly ErrorColor: string = '#E84026';

  switchMode(): void {
    if (this.isLoading) {
      return;
    }
    this.isLogin = !this.isLogin;
    this.errorMsg = '';
  }

  showToast(message: string): void {
    try {
      this.getUIContext().getPromptAction().showToast({ message: message });
    } catch (e) {
      // 页面已销毁时不让提示失败覆盖接口的实际结果。
    }
  }

  async handleSubmit(): Promise<void> {
    if (this.isLoading) {
      return;
    }
    const acc: string = this.account.trim();
    if (acc.length < 5 || acc.length > 15) {
      this.errorMsg = '账号需为 5~15 个字符';
      return;
    }
    if (this.password.length < 5 || this.password.length > 15) {
      this.errorMsg = '密码需为 5~15 个字符';
      return;
    }
    this.errorMsg = '';
    this.isLoading = true;
    try {
      if (this.isLogin) {
        const resp: LoginResponse = await AuthService.login(acc, this.password);
        AppStorage.setOrCreate('access_token', resp.access_token);
        AppStorage.setOrCreate('user_id', resp.user_id);
        AppStorage.setOrCreate('account', resp.account);
        this.showToast(resp.reply);
        router.replaceUrl({ url: 'pages/home/Home' });
      } else {
        const resp: SignResponse = await AuthService.sign(acc, this.password);
        this.showToast(resp.reply);
        this.isLogin = true;
        this.password = '';
      }
    } catch (e) {
      const err: Error = e as Error;
      this.errorMsg = err.message;
      this.showToast(err.message);
    } finally {
      this.isLoading = false;
    }
  }

  build() {
    Column() {
      Column() {
        Text('Hello, Word')
          .fontSize(42)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.TitleColor)
        Text('Welcome back')
          .fontSize(14)
          .fontColor(this.SubTextColor)
          .margin({ top: 8 })
      }
      .alignItems(HorizontalAlign.Center)

      Column() {
        if (this.isLogin) {
          Text('登录')
            .fontSize(22)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.TextColor)
          Text('欢迎回来，开始今天的探索')
            .fontSize(13)
            .fontColor(this.SubTextColor)
            .margin({ top: 6 })
        } else {
          Text('注册')
            .fontSize(22)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.TextColor)
          Text('欢迎加入我们，让我们开始遨游词海吧')
            .fontSize(13)
            .fontColor(this.SubTextColor)
            .margin({ top: 6 })
        }

        Text('账号')
          .fontSize(13)
          .fontColor(this.TextColor)
          .margin({ top: 24 })
        TextInput({ placeholder: '请输入账号', text: this.account })
          .width('100%')
          .height(44)
          .placeholderColor(this.SubTextColor)
          .placeholderFont({ size: 14 })
          .fontSize(14)
          .backgroundColor(this.InputBgColor)
          .borderRadius(10)
          .border({ width: 1, color: this.InputBorderColor })
          .margin({ top: 8 })
          .onChange((value: string) => {
            this.account = value;
          })
          .enabled(!this.isLoading)

        Text('密码')
          .fontSize(13)
          .fontColor(this.TextColor)
          .margin({ top: 16 })
        TextInput({ placeholder: '请输入密码', text: this.password })
          .type(InputType.Password)
          .width('100%')
          .height(44)
          .placeholderColor(this.SubTextColor)
          .placeholderFont({ size: 14 })
          .fontSize(14)
          .backgroundColor(this.InputBgColor)
          .borderRadius(10)
          .border({ width: 1, color: this.InputBorderColor })
          .margin({ top: 8 })
          .onChange((value: string) => {
            this.password = value;
          })
          .enabled(!this.isLoading)

        if (this.errorMsg.length > 0) {
          Text(this.errorMsg)
            .fontSize(12)
            .fontColor(this.ErrorColor)
            .margin({ top: 12 })
            .alignSelf(ItemAlign.Start)
        }

        if (this.isLogin) {
          Button(this.isLoading ? '登录中...' : '登录')
            .width('100%')
            .height(44)
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor(Color.White)
            .backgroundColor(this.PrimaryColor)
            .borderRadius(12)
            .margin({ top: 28 })
            .enabled(!this.isLoading)
            .onClick(() => {
              this.handleSubmit();
            })
        } else {
          Button(this.isLoading ? '注册中...' : '注册')
            .width('100%')
            .height(44)
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor(Color.White)
            .backgroundColor(this.PrimaryColor)
            .borderRadius(12)
            .margin({ top: 28 })
            .enabled(!this.isLoading)
            .onClick(() => {
              this.handleSubmit();
            })
        }

        if (this.isLogin) {
          Row() {
            Text('还没有账号？')
              .fontSize(13)
              .fontColor(this.SubTextColor)
            Text('立即注册')
              .fontSize(13)
              .fontColor(this.PrimaryColor)
              .fontWeight(FontWeight.Medium)
              .margin({ left: 4 })
              .onClick(() => {
                this.switchMode();
              })
          }
          .margin({ top: 24 })
        } else {
          Row() {
            Text('返回')
              .fontSize(13)
              .fontColor(this.SubTextColor)
            Text('登录')
              .fontSize(13)
              .fontColor(this.PrimaryColor)
              .fontWeight(FontWeight.Medium)
              .margin({ left: 4 })
              .onClick(() => {
                this.switchMode();
              })
          }
          .margin({ top: 24 })
        }
      }
      .width('88%')
      .padding(24)
      .backgroundColor(this.CardBgColor)
      .borderRadius(20)
      .shadow({
        radius: 20,
        color: 'rgba(0, 0, 0, 0.06)',
        offsetX: 0,
        offsetY: 8
      })
      .alignItems(HorizontalAlign.Start)
      .margin({ top: 56 })
    }
    .width('100%')
    .height('100%')
    .padding({ left: 16, right: 16 })
    .backgroundColor(this.PageBgColor)
    .justifyContent(FlexAlign.Center)
  }
}
