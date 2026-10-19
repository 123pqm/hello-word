# 视频词形还原

上传 `/video/upload`（或 `/video/web/upload`）的 multipart 表单新增
`enable_lemmatization=true|false`。省略默认为 false，兼容旧客户端。
鸿蒙上传页面读取 `FlashcardSettingsStore.lemmatization`，默认 true。
设置只影响之后上传的视频，不回写旧闪卡。

`movies.enable_lemmatization` 保存每个任务的选择，worker 从数据库读取。
启用后用本地 spaCy `en_core_web_sm` 对完整句子做词性和词形分析，优先用原形
匹配所选词书/范围；原形不在词书时保留原词匹配。原句和时间不改动。
关闭时跳过分析；模型加载缓存，不逐词查询数据库或请求第三方接口。
缺少原句或无法对齐词元时保留原词。小模型仍可能误判歧义词。

部署：运行 `uv sync` 安装锁定依赖和英文模型，重启后端。
启动时自动添加任务开关列，历史任务默认为 false。本地开发环境已安装模型并升级表。
模型安装需要下载，安装完推理无需联网。

说明：https://spacy.io/api/lemmatizer
