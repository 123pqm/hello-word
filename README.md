# AI 影视词汇学习（Android + FastAPI）

主开发目录统一为 `D:\hello-word-repo`。`android/` 是 Android 项目，`app/` 和 `tests/` 是后端与测试；在仓库根目录提交会同时包含两部分。C 盘旧项目保留作备份，以后不在那里继续修改、提交或启动后端。

本次整合保留 D 盘的注册、上传状态、首页学习联动、闪卡与播放器，并接入 C 盘的 Whisper、CET4 匹配和句子时间处理。上传接口返回任务编号，后台完成后由 Android 查询状态、加载闪卡；点击视频提示跳到句子开始。首页“更多”已连接“我的观影”，从后端分页读取当前用户电影历史。

当前数据库为本机 MySQL 8，通过 PyMySQL 连接。原 `data/words.db` 留作迁移来源，不再是接口读写的数据源。

本地 `.env`、`data/`、`uploads/` 已从 C 盘复制并保持 Git 忽略。MySQL 数据仍保存在原本的本机数据库中；整合没有清空业务数据库或修改已有电影记录。历史记录中的旧视频路径仍指向原位置，所以暂时保留 C 盘备份。

## 首次配置与迁移

先把 `.env.example` 复制为 `.env`（已有 `.env` 时直接编辑），在本机填写 `MYSQL_PASSWORD`。文件已被 Git 忽略，密码不要发到聊天或写进 Python 源码。

当前默认地址为 `127.0.0.1:3306`，账号为 `root`，数据库为 `movie_vocab`。这是本机学习配置，正式部署使用专用数据库账号。

在 Windows PowerShell 中进入仓库根目录。需要 Python 3.11+、uv、MySQL 和 PATH 中可用的 FFmpeg，安装锁定依赖：

```powershell
Set-Location 'D:\hello-word-repo'
uv sync --locked --python 3.11 --cache-dir '.uv-cache'
```

当前电脑已在 D 盘创建独立 `.venv`，Python 和 uv 放在被忽略的 `.tools/`，可直接启动。需要再次同步时也可以使用本地 uv：

```powershell
.\.tools\uv\bin\uv.exe sync --locked --cache-dir '.uv-cache'
```

Whisper 已加入依赖和锁文件。首次分析按需加载 `tiny.en`，模型未缓存时会下载；普通接口启动和离线测试不会触发模型加载。项目依赖和锁文件使用 TUNA HTTPS 镜像。

启动会补建缺失的 `movies`、`movie_words` 表，并为缺少 ID 的旧 CET4 表增加编号；已有记录和已有 ID 不变。空数据库需要单独导入 CET4 词库：`python -m app.import_words`（使用项目虚拟环境）。

如果 FastAPI 正在运行，先在它的终端按 Ctrl+C，再预览和迁移原数据：

```powershell
.\.venv\Scripts\python.exe migrate_sqlite.py --dry-run
.\.venv\Scripts\python.exe migrate_sqlite.py
```

第二条只在预览通过后运行。迁移保留原 SQLite 文件和 ID；相同记录跳过；冲突或插入失败时整批新增回滚。第一次会自动建库和建表，建库建表不会随插入回滚。修改 `.env` 后需要重启 FastAPI。

## 运行开发服务器

登录签发 JWT 需要本机 `.env` 中的 `JWT_SECRET_KEY`。当前开发环境已配置；新环境请先执行下面的命令生成随机密钥，再把结果写到 `.env` 的 `JWT_SECRET_KEY=` 后面：

```powershell
.\.venv\Scripts\python.exe -c "import secrets; print(secrets.token_hex(32))"
```

密钥不要提交到 Git、发送到前端或公开分享，也不要在每次启动时重新生成。更换密钥会使原来签发的 token 无法通过新密钥验证。修改 `.env` 后需停止并重新启动服务。

在当前目录执行：

```powershell
.\dev.ps1
```

打开以下地址：

- 接口文档：http://127.0.0.1:8000/docs
- 健康检查：http://127.0.0.1:8000/health

## 登录返回 JWT

在 `/docs` 中调用 `POST /user/login`，请求仍然是 JSON，字段为 `account`、`password`，使用已经注册的账号。成功返回 HTTP 200：

```json
{
  "account": "demo_user",
  "reply": "登录成功",
  "access_token": "这里是签发的 JWT",
  "token_type": "bearer",
  "expires_in": 1800
}
```

`app/main.py` 的 `create_access_token()` 是本项目编写的函数，使用 PyJWT 的 `jwt.encode()` 生成 HS256 签名 token。内容只有字符串用户 ID（`sub`）、签发时间（`iat`）和 30 分钟后的过期时间（`exp`），不包含密码或密码哈希。`app/schemas.py` 的 `login_response` 定义上述返回字段。

账号不存在或密码错误统一返回 401；内部异常仅记录在服务端日志，不把异常详情返回给客户端。登录不会往数据库写入 token。

前端后续通过 `Authorization: Bearer <access_token>` 携带凭证。**电影上传、状态和闪卡接口现在校验 JWT 签名、过期时间与用户是否存在，并按电影的 user_id 校验归属；其他原有接口尚未全部接入鉴权。**正式部署还需要 HTTPS、登录限流和客户端安全存储；不要公开部署当前学习版。

仅运行登录相关的离线测试：

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_login.py -q -p no:cacheprovider
```

## 数据库查询示例

迁移完成后，以下接口查询的是 MySQL 中的 words 表。服务启动只建库建表，不自动插入练习数据；原数据使用上面的迁移命令复制。

- 根据 ID 查询：http://127.0.0.1:8000/words/1
- 使用 Query 参数搜索：http://127.0.0.1:8000/words?keyword=exper&limit=5

在 MySQL 命令行或 Workbench 中验证：

```sql
USE movie_vocab;
SELECT id, word, meaning FROM words ORDER BY id;
```

接口保持 GET /words/{word_id}、GET /words?keyword=...&limit=... 和 POST /words。SQL 参数占位符改成 `%s`，值仍单独传入参数元组。

## 运行测试

```powershell
.\test.ps1
```

以上默认运行不需要数据库连接的检查，MySQL 集成测试会显示 skipped。配置好本机密码后运行完整测试：

```powershell
.\test.ps1 -MySQL
```

集成测试新建随机的 `movie_vocab_test_...` 数据库并在完成后删除它，不清空 `movie_vocab`。测试账号需要建库和删库权限。它覆盖原有查询、新增持久化、重复词、输入校验、中文/emoji、迁移重复运行、整批回滚以及启动流程。

原 FastAPI/Starlette 依赖仍可能输出两条弃用警告；这与本次 MySQL 切换无关，检查最终是否 passed。

## Android 与日常提交

Android Studio 打开 `D:\hello-word-repo\android`。后端从根目录执行 `.\dev.ps1`；若 C 盘旧服务还占用 8000 端口，先在旧服务终端停止，再从 D 盘启动。Android 的 `RetrofitClient` 地址应与手机或模拟器可访问的电脑地址一致。

在根目录提交和推送整个项目：

```powershell
Set-Location 'D:\hello-word-repo'
git status
git add .
git commit -m "说明本次修改"
git push origin master
```

不要把 `.env`、虚拟环境、数据库、上传视频、构建产物和缓存加入提交。

## 电影所属用户与手机登录保存

登录响应包含 `user_id`，Android 在私有 SharedPreferences `auth_session` 保存账号、用户编号、token 和过期时间，不保存密码，登录凭证排除系统备份。应用进程启动时恢复未过期的登录信息；电影接口返回 401 时清除对应失效会话并返回登录页。启动到登录页仍可直接登录或切换账号。

电影上传只信任经过验证的 token 中的用户编号，不接受客户端指定电影所有者。状态和单词接口对不属于当前用户（包括 `user_id IS NULL` 的历史记录）的电影返回 404。没有批量修改历史归属，旧记录需确认所有者后再单独迁移；更新后请重新登录并上传新视频验证。

闪卡每次读取先请求受保护接口，再按用户编号保存本地副本，不使用旧的无账号缓存。电影列表按账号从后端读取，可在不同设备登录后查询；在线视频播放尚未实现。

## 单词对应的电影例句

`movie_words.movie_id` 对应 `movies.id`（当前通过查询关联，未添加数据库外键约束）。`movie_words.sentence_text` 保存该单词首次出现所在句子的英文原文，与 `start_time/end_time` 的播放范围一致。识别时保留大小写、数字和标点，不额外调用 Whisper 或翻译服务。

后端启动会为旧表补充可空的 `sentence_text TEXT` 字段，保留已有记录。旧记录返回 `null`，不会根据时间自动生成原句；要补齐需要重新处理原视频。`GET /video/{movie_id}/words` 通过原有用户归属校验后返回此字段，Android 按用户保存的 JSON 副本也包含例句。闪卡释义区点击可一起显示或隐藏释义和英文例句；没有例句时隐藏对应组件，不显示示例文字或中文翻译。

更新后重启后端，并重新构建、安装 Android 应用。新上传并处理成功的视频将自动保存例句。

## 我的观影历史

`GET /video/history?limit=30&before_id=123` 需要 `Authorization: Bearer <token>`。`limit` 为 1～100，第一页省略 `before_id`；响应 `data.items` 返回 `id/file_name/status/created_at`，按编号倒序，`data.next_before_id` 为下一页游标，空值表示已到末页。只返回 token 所属用户的记录，不返回服务器文件路径，也不返回无归属的历史记录。

首页“更多”打开 `MyMoviesActivity`，使用现有 `activity_my_movies` 和 `item_movie` 布局；支持加载、空列表、重试、分页与刷新。点击分析完成的电影打开闪卡；仅当前设备仍保留对应本地视频位置时可播放视频。文件大小与时长尚未存入电影表，列表显示上传日期而非示例数值。
