# Hello Word · 在电影台词里学英语

从自己选的英语影片里提取词书中的单词。打开闪卡，就能听到这个词出现的那句原声；答错的词可以加入第 1、3、7 天的复习计划。

**[在线查看项目介绍（PDF）](docs/hello-word.pdf)** · [下载面试版 PPT](https://github.com/123pqm/hello-word/raw/refs/heads/master/docs/hello-word.pptx) · [下载演示视频（4 分 35 秒）](https://github.com/123pqm/hello-word/raw/refs/heads/master/docs/media/hello-word-demo.mp4)

## 项目亮点

**一部原片可以支撑整套闪卡。** 例如，某句台词出现在 2.66～3.84 秒：点击闪卡后，鸿蒙播放器跳转到 2.66 秒，确认定位成功后开始播放，播放进度达到 3.84 秒就暂停。词卡只需关联影片、例句和起止时间戳，不必为每句话单独生成视频文件。若识别边界不准，用户可以直接校正时间。首次分析的主要耗时来自 Whisper 转写；回放时复用已有时间戳。[后端写入词卡](backend/app/tasks/movie_task.py) · [鸿蒙播放控制](harmony/entry/src/main/ets/pages/word_flashcard.ets)

**同一个词，在不同环节用不同方式处理。** 匹配词库时，词形还原让台词里的 *ran* 能找到词条 *run*；查词时，近似词模型能为 *addd* 建议 *add*。进入答题模式后，同一个近似词模型还用于挑选干扰项，让用户听原句、辨词义。[词形还原](backend/app/services/lemmatization_service.py) · [近似词服务](backend/app/services/spelling_service.py)

**答错的词能回到原句再学。** 用户可以在闪卡里就当前台词向 AI 提问，也可以在答题后重听原声。开启错题自动收录后，复习计划按第 1、3、7 天安排练习；主动收藏的单词另行保存。[复习计划实现](backend/app/reviews.py)

## 应用实机截图

**选择学习内容**

| 学习首页 | 选择影片 | 选择词书 | 猜你想搜 |
| :---: | :---: | :---: | :---: |
| [![学习首页](docs/media/home.png)](docs/media/home.png) | [![选择影片](docs/media/import-movie.png)](docs/media/import-movie.png) | [![选择词书](docs/media/wordbook.png)](docs/media/wordbook.png) | [![猜你想搜](docs/media/search.png)](docs/media/search.png) |

**看原片、学单词**

| 原片闪卡 | 展开释义与例句 | 校正片段起止时间 | 听原声答题 |
| :---: | :---: | :---: | :---: |
| [![原片闪卡](docs/media/flashcard.png)](docs/media/flashcard.png) | [![展开释义与例句](docs/media/reveal.png)](docs/media/reveal.png) | [![校正片段起止时间](docs/media/correct.png)](docs/media/correct.png) | [![听原声答题](docs/media/quiz-listen.png)](docs/media/quiz-listen.png) |

**答题、提问与复习**

| 对照答案 | AI 快捷提问 | 查看例句解析 | 查看复习计划 |
| :---: | :---: | :---: | :---: |
| [![对照答案](docs/media/quiz.png)](docs/media/quiz.png) | [![AI 快捷提问](docs/media/assistant.png)](docs/media/assistant.png) | [![查看例句解析](docs/media/ai-reply.png)](docs/media/ai-reply.png) | [![查看复习计划](docs/media/review.png)](docs/media/review.png) |

## 配置与启动

**后端。** 准备 Python 3.11、uv、MySQL 8 和 FFmpeg（加入 PATH）。在 Windows PowerShell 中执行：

```powershell
cd backend
uv sync --locked --python 3.11
Copy-Item .env.example .env
```

在 `.env` 填写自己的数据库连接信息及随机生成的 `JWT_SECRET_KEY`。首次启动需要建库、建表权限。AI 问答按所选服务商配置模型、地址和 API Key。不要提交 `.env`。

```powershell
.\dev.ps1
```

服务启动后打开 `http://127.0.0.1:8000/docs`。首次使用，在另一个终端进入 `backend` 导入词库：

```powershell
uv run python -m app.import_words
uv run python -m app.import_netem
uv run python -m app.import_model_supplement resources/spelling_model/data/vocab.json
```

**鸿蒙客户端。** 用 DevEco Studio 打开 `harmony/`，按 `build-profile.json5` 安装相应 SDK、同步依赖并配置自己的签名。在 [AppConfig.ets](harmony/entry/src/main/ets/common/AppConfig.ets) 中修改 `BASE_URL`：真机应填手机可访问的后端地址，例如 `http://<电脑局域网IP>:8000`。手机上的 `127.0.0.1` 指向手机自身。

**测试。** 在 `backend` 配置好 `.env` 的 `JWT_SECRET_KEY` 后执行 `uv run pytest -q`；真实数据库集成测试需要另外启用 `--mysql`。鸿蒙业务逻辑测试位于 [harmony/scripts](harmony/scripts/)，先将 `DEVECO_SDK_HOME` 指向 DevEco Studio 的 SDK 目录，再用 Node.js 运行 `test-*.cjs`。

## 当前实现与未来规划

当前版本使用 **ArkTS / ArkUI 客户端与 FastAPI 后端**，Whisper 转写在后端运行。手机端默认下载 tiny、按需下载更大模型，以及华为云语音转写和 OBS 影片存储，均为后续部署方案。当前可配置的云端大模型问答与规划中的云端语音转写是不同功能。

考研词库的来源与许可见[词库署名说明](backend/resources/vocabulary/netem/ATTRIBUTION.md)。

