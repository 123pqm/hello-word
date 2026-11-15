import { MovieCompletionMonitor } from '../service/MovieCompletionMonitor';
import { router } from '@kit.ArkUI';
import { picker } from '@kit.CoreFileKit';
import { BusinessError } from '@ohos.base';
import { VideoUploadService } from '../service/VideoUploadService';
import { FlashcardSettingsStore } from '../storage/FlashcardSettingsStore';
import { common } from '@kit.AbilityKit';

@Entry
@Component
struct UploadMovie {
  @State book_id: number = 0;
  @State selectedFileName: string = '';
  @State hasSelectedFile: boolean = false;
  private selectedFileUri: string = '';
  @State private uploading: boolean = false;
  @State private movieId: number = 0;
  @State private uploadMessage: string = '';
  private trackingToken: string = '';

  private async uploadMovie(): Promise<void> {
    if (this.uploading || this.movieId > 0 || !this.selectedFileUri) return;
    const token = AppStorage.get<string>('access_token') ?? '';
    const uploadUserId = AppStorage.get<number>('user_id') ?? 0;
    if (!token) { this.uploadMessage = '请先登录'; return; }
    const context = this.getUIContext().getHostContext();
    if (!context) { this.uploadMessage = '页面尚未就绪，请重试'; return; }
    const bookIds: number[] = [1, 3, 4];
    const bookId = bookIds[this.book_id];
    if (!bookId) { this.uploadMessage = '请重新选择词书'; return; }
    this.uploading = true;
    this.uploadMessage = '正在准备并上传视频，请稍候…';
    try {
      const settings = await FlashcardSettingsStore.load(context as common.Context);
      const result = await VideoUploadService.upload(this.selectedFileUri, this.selectedFileName,
        context.cacheDir, bookId, token, settings.lemmatization, settings.modelSelection);
      MovieCompletionMonitor.track(result.movie_id, this.selectedFileName, uploadUserId);
      this.movieId = result.movie_id;
      this.trackingToken = result.tracking_token;
      this.uploadMessage = `上传成功，任务 #${result.movie_id}，处理完成后将在 App 内自动打开闪卡`;
    } catch (error) {
      this.uploadMessage = `${(error as Error).message || '上传失败'}；如网络中断，请先到电影列表确认是否已创建任务`;
    } finally {
      this.uploading = false;
    }
  }

  aboutToAppear() {
    const params = router.getParams() as Record<string, number>;
    if (params) {
      this.book_id = params.book_id;
    }
  }

  private selectFile(): void {
    if (this.uploading) return;
    const context = this.getUIContext().getHostContext();
    if (!context) {
      console.error('File picker error: UIAbility context is unavailable');
      return;
    }
    const documentPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(context);
    const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
    options.fileSuffixFilters = ['视频|.mp4,.mkv,.avi,.mov'];
    options.maxSelectNumber = 1;
    documentPicker.select(options).then((uris: string[]) => {
      if (uris.length > 0) {
        this.selectedFileUri = uris[0];
        const parts: string[] = uris[0].split('/');
        const fileName: string = parts[parts.length - 1];
        try {
          this.selectedFileName = decodeURIComponent(fileName);
        } catch {
          this.selectedFileName = fileName;
        }
        this.hasSelectedFile = true;
        this.movieId = 0;
        this.trackingToken = '';
        this.uploadMessage = '';
      }
    }).catch((err: BusinessError) => {
      console.error(`File picker error (${err.code}): ${err.message}`);
    });
  }

  private bookName(): string {
    // 当前上游传递的是 UI 索引 0/1/2，不是后端词书 ID。
    if (this.book_id === 0) return 'CET-4';
    if (this.book_id === 1) return '考研词汇全表';
    if (this.book_id === 2) return '考研词汇·词频优先';
    return '未选择词书';
  }

  @Builder
  StageDot() {
    Column()
      .width(16)
      .height(16)
      .borderRadius(8)
      .backgroundColor('#FFFFFF')
      .border({ width: 2, color: '#D7DEE9' })
  }

  @Builder
  StageLabel(label: string) {
    Text(label)
      .fontSize(11)
      .fontColor('#8A8F99')
      .textAlign(TextAlign.Center)
      .layoutWeight(1)
  }

  build() {
    Column() {
      Row() {
        Image($r('app.media.ic_chevron_left_gray'))
          .width(24)
          .height(24)
          .onClick(() => {
            router.back();
          })

        Text('上传电影')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor('#1A1A1A')
          .layoutWeight(1)
          .textAlign(TextAlign.Center)

        Column()
          .width(24)
      }
      .width('100%')
      .height(56)
      .padding({ left: 16, right: 16 })
      .alignItems(VerticalAlign.Center)
      .backgroundColor('#F5F7FA')

      Scroll() {
        Column() {
          Column() {
            if (this.selectedFileName === '') {
              Column() {
                Image($r('app.media.ic_upload'))
                  .width(48)
                  .height(48)

                Text('选择文件')
                  .fontSize(16)
                  .fontWeight(FontWeight.Bold)
                  .fontColor('#1A1A1A')
                  .margin({ top: 16 })

                Text('支持 MP4、AVI、MKV 等格式')
                  .fontSize(13)
                  .fontColor('#8A8F99')
                  .margin({ top: 8 })
              }
              .width('100%')
              .padding({ top: 48, bottom: 48 })
              .borderRadius(16)
              .border({ width: 1, color: '#D1D5DB', style: BorderStyle.Dashed })
              .justifyContent(FlexAlign.Center)
              .alignItems(HorizontalAlign.Center)
              .onClick(() => {
                this.selectFile();
              })
            } else {
              Column() {
                Image($r('app.media.ic_upload'))
                  .width(40)
                  .height(40)

                Text(this.selectedFileName)
                  .fontSize(16)
                  .fontWeight(FontWeight.Bold)
                  .fontColor('#1A1A1A')
                  .margin({ top: 12 })
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })

                Text('已选择')
                  .fontSize(13)
                  .fontColor('#3B7FFF')
                  .margin({ top: 6 })

                Text('重新选择')
                  .fontSize(14)
                  .fontColor('#3B7FFF')
                  .margin({ top: 16 })
                  .onClick(() => {
                    this.selectFile();
                  })
              }
              .width('100%')
              .padding({ top: 40, bottom: 40 })
              .borderRadius(16)
              .backgroundColor('#FFFFFF')
              .justifyContent(FlexAlign.Center)
              .alignItems(HorizontalAlign.Center)
            }
          }
          .width('100%')
          .margin({ top: 8 })

          Row({ space: 12 }) {
            Text('匹配范围')
              .fontSize(14)
              .fontColor('#8A8F99')
            Text(this.bookName() + '（整本）')
              .fontSize(14)
              .fontWeight(FontWeight.Medium)
              .fontColor('#3973D6')
              .padding({ left: 14, right: 14, top: 10, bottom: 10 })
              .borderRadius(10)
              .border({ width: 1, color: '#B9CBE8' })
              .backgroundColor('#F0F5FF')
              .layoutWeight(1)
          }
          .width('100%')
          .margin({ top: 16, bottom: 16 })

          Column() {
            Row() {
              Text('分析进度')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor('#1A1A1A')
                .layoutWeight(1)
              Text(this.uploading ? '上传中' : (this.movieId > 0 ? '已提交' : '待开始'))
                .fontSize(14)
                .fontColor('#8A8F99')
            }
            .width('100%')

            Text(this.movieId > 0 ? '等待后台分析' : '等待上传')
              .fontSize(28)
              .fontWeight(FontWeight.Bold)
              .fontColor('#397BF4')
              .margin({ top: 28 })
            Text(this.uploadMessage || (this.selectedFileName === '' ? '选择电影后开始学习' : '点击下方按钮上传视频'))
              .fontSize(12)
              .fontColor('#8A8F99')
              .textAlign(TextAlign.Center)
              .margin({ top: 8, bottom: 28 })

            Row() {
              this.StageDot()
              Blank().height(2).backgroundColor('#E1E6EE')
              this.StageDot()
              Blank().height(2).backgroundColor('#E1E6EE')
              this.StageDot()
              Blank().height(2).backgroundColor('#E1E6EE')
              this.StageDot()
            }
            .width('100%')
            .padding({ left: 22, right: 22 })
            .alignItems(VerticalAlign.Center)
            Row() {
              this.StageLabel('上传视频')
              this.StageLabel('语音识别')
              this.StageLabel('词汇匹配')
              this.StageLabel('生成词卡')
            }
            .width('100%')
            .margin({ top: 12 })
          }
          .width('100%')
          .padding({ left: 18, right: 18, top: 18, bottom: 24 })
          .borderRadius(18)
          .border({ width: 1, color: '#EDF0F5' })
          .backgroundColor('#FFFFFF')

          Row({ space: 12 }) {
            Image($r('app.media.android_vh_robot'))
              .width(52)
              .height(52)
              .objectFit(ImageFit.Contain)
            Text(this.selectedFileName === '' ? '请选择视频，准备开始电影学习' : '按所选词书整本匹配，上传后可在电影列表查看结果')
              .fontSize(14)
              .lineHeight(22)
              .fontColor('#34445C')
              .layoutWeight(1)
          }
          .width('100%')
          .padding(16)
          .borderRadius(16)
          .backgroundColor('#EAF3FF')
          .margin({ top: 16 })
        }
        .width('100%')
        .padding({ left: 16, right: 16, bottom: 24 })
        .justifyContent(FlexAlign.Start)
        .alignItems(HorizontalAlign.Start)
      }
      .width('100%')
      .layoutWeight(1)
      // 余下空间交给滚动容器，短内容从顶部开始而不是垂直居中。
      .align(Alignment.TopStart)
      .scrollBar(BarState.Off)

      if (this.hasSelectedFile) {
        Column() {
        Button(this.uploading ? '上传中…' : (this.movieId > 0 ? '已上传' : '确认上传'))
          .width('100%')
          .height(48)
          .fontSize(16)
          .fontColor('#FFFFFF')
          .backgroundColor('#3B7FFF')
          .borderRadius(24)
          .enabled(!this.uploading && this.movieId === 0)
          .onClick(() => {
            this.uploadMovie();
          })
        }
        .width('100%')
        .padding({ left: 24, right: 24, top: 12, bottom: 32 })
      }
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Start)
    .backgroundColor('#F5F7FA')
  }
}
