# 上传时选择 Whisper 模型

`POST /video/upload` 和 `/video/web/upload` 的 multipart 表单支持
`whisper_model=tiny.en|base.en`，默认 `tiny.en`，其他值返回 422。
鸿蒙读取现有 `FlashcardSettingsStore.modelSelection`（tiny/base），上传时映射为英文模型名。

选择保存在 `movies.whisper_model`。后台任务从数据库读取后才开始转录，运行中修改个人设置
不会影响已提交任务。启动自动补列，旧任务默认 tiny.en。例句和逐词时间提取保持原流程。

两个权重保留在 Whisper 默认缓存目录（通常 `~/.cache/whisper`），不提交 Git，
不打包进 App。进程内串行推理、只缓存一个模型；切换时释放旧模型并加载另一个，
不必重启后端。首次使用未缓存的模型需要下载，之后离线加载。

可在部署机器提前准备（使用后端 Python 环境）：

```python
import whisper
for name in ("tiny.en", "base.en"):
    model = whisper.load_model(name, device="cpu")
    del model
```

base.en 通常计算更慢，准确率需以实际音频评估；设置仅影响新上传任务，不重处理历史视频。
