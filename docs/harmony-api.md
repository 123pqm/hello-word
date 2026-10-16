# 鸿蒙前端接口接入文档

更新：2026-10-15。对应 `D:/hello-word-repo` 当前后端，鸿蒙工程为 `D:/hello-huaweiword`。
本次已新增用户资料、用户词书持久化接口，修复旧词书选择接口，其他业务复用已有后端。
前端页面和网络层留给华为智能体在 IDE 中实现；提交历史应如实区分现有工程与后续开发。

## 1. 启动与连接

已有电脑环境：在后端目录执行 `./dev.ps1`，监听 `0.0.0.0:8000`。
新环境按后端 README 安装 Python 3.11+、锁定依赖、MySQL、FFmpeg；将 `.env.example` 复制为 `.env`，配置数据库、JWT 密钥和 AI 密钥。启动时建表，词库需另行导入。

```powershell
Set-Location D:/hello-word-repo
./dev.ps1
# 新数据库在启动建表后，按需要另开终端执行：
./.venv/Scripts/python.exe -m app.import_words
./.venv/Scripts/python.exe -m app.import_netem
```

本机文档：`http://127.0.0.1:8000/docs`；机器可读契约：`/openapi.json`。
仓库附带 `openapi.json` 为本次代码导出的快照；服务器更新后重新导出。
真机的 BASE_URL 应使用运行后端电脑的局域网 IPv4，例如 `http://192.168.1.10:8000`，不要填 `localhost` 或 `0.0.0.0`。设备和电脑需网络互通，防火墙允许开发端口。生产地址使用 HTTPS。
鸿蒙工程需要网络权限；开发 HTTP 的网络安全配置、上传和流式网络 API 由华为智能体根据本机 SDK 实现并编译验证。

## 2. 公共规则

- JSON 请求：`Content-Type: application/json`，UTF-8。路径不加 `/api` 前缀。
- 登录后使用 `Authorization: Bearer <access_token>`；不要将用户 ID 当作鉴权依据。
- token 有效期 1800 秒，无刷新接口。收到 401 清理会话并重新登录；未提交的学习操作由用户确认后恢复。
- 各接口返回结构不完全一致：登录在根对象，词书列表在 `data`，视频和收藏多为 `{code,data}`。不要统一强转成一种包装。
- 失败以 HTTP 状态码为准，通常为 `{"detail":"说明"}`；422 的 detail 也可能是字段校验数组。
- 204 无响应体，不要 JSON.parse。查询字符串做 URL 编码；秒数、ID、布尔值保持原类型，nullable 字段允许 null。
- 密码、token、播放票据和 AI 密钥不能写入日志或提交仓库；AI 密钥只留后端。

## 3. 账号与个人中心

### 注册和登录（不需要 token）

`POST /user/sign`，请求 `{"account":"learner01","password":"demo12345"}`；两个字段均 5～15 字符。
成功 HTTP 201：`{"id":7,"reply":"注册成功"}`；重复账号 409。注册后需调用登录。

`POST /user/login`，同样的请求结构。成功例：

```json
{"user_id":7,"account":"learner01","reply":"登录成功","access_token":"<token>","token_type":"bearer","expires_in":1800}
```

密码保留原始空格，不能 trim；账号可 trim。错误账号或密码为 401。成功登录会自动标记当天打卡。

### 当前用户（新增，需要 Bearer）

`GET /user/me`：

```json
{"code":200,"data":{"user_id":7,"account":"learner01","book_id":0,"book":null}}
```

`book_id=0` 表示未选择；此时提示用户选书。`book` 有值时结构与词书目录元信息相同，但不包含 total。

`PUT /user/me/wordbook`，请求 `{"book_id":1}`，成功返回上述用户结构，book_id 和 book 更新为所选词书。只接受正整数，2 或其他未开放词书返回 422，重复选择幂等。只更新 token 对应的用户，不传 user_id。
该接口保存偏好；具体查词和上传仍需显式传 book_id，选择词书不会自动导入词库。

`POST /user/logout`，需要 Bearer，成功 204；后端撤销本次 token，其他会话不受影响。客户端清除本地会话和当前用户缓存。
旧 `PUT /user/chose` 保留 `{"id":7,"book_id":1}`，只允许 id 等于登录用户，成功 `{"reply":"成功更改单词书"}`。鸿蒙使用新接口。

## 4. 首页打卡

`GET /user/checkin` 读取，`POST /user/checkin` 标记当天，均需要 Bearer，无 body。
返回 `code=200` 和 `data`：user_id、today、days、week_count、streak_days、next_checkin_after_seconds。
days 为本周七天，每项 `{date,weekday,checked,is_today}`；weekday=1 表示周一。日期采用北京时间，登录也会打卡。同一天重复请求不重复计数。
跨日可重新 POST；倒计时使用 next_checkin_after_seconds，不用手机日期推测服务端打卡日。

## 5. 词书、分页与搜索（需要 Bearer）

| 方法与路径 | 参数与返回 |
| --- | --- |
| GET /wordbooks | 返回 `{"data":[{"id":1,"name":"CET-4","description":"大学英语四级词库","source_url":null,"license":null,"total":实际数量},...]}` |
| GET /wordbooks/{book_id}/words | start_index=1、end_index=20；从 1 起的列表位置，闭区间，每次 1～100 条 |
| GET /wordbooks/{book_id}/search | q 必填，1～80 字符；limit 默认30，上限100；offset 默认0，上限100000 |

开放词书：1 四级、3 考研全表、4 考研词频优先；2 六级未开放。页面从目录生成选项，保留 source_url 和 license 署名展示。
词条列表返回 `{"data":[{"id":1,"word":"example","meaning":"例子","pos":null,"frequency":40}],"total":5530,"book":{...}}`。
pos 和 frequency 可为 null。列表位置不是词条 id，下一页按位置推进；词条身份用 `(book_id,id)`。
尚未导入的词书列表接口返回 503，页面显示提示，不伪造数据。

搜索返回 `{data,book_id,query,limit,has_more,next_offset}`，data 中词条为 `{id,word,meaning,pos}`。
输入停顿约 350ms 后请求；取消/忽略过期结果；下一页使用 next_offset，null 表示结束。
全空白搜索返回 422；无匹配返回空 data。不要使用旧 `/words` 或 `/user/chose/cet4` 构建鸿蒙词书页面。

## 6. 视频上传 → 处理 → 闪卡

### 上传

`POST /video/upload`，需要 Bearer，multipart/form-data。`/video/web/upload` 是兼容别名。

| 字段 | 内容 |
| --- | --- |
| file | 视频二进制，带文件名和 video/* MIME；支持 .mp4/.mov/.mkv/.avi |
| book_id | 所选词书 ID，建议显式填写 |
| selection_mode | 整本 `all_book`，自选 `selected_book` |
| selected_word_ids | 仅自选时传，例如字符串 `[1,7,20]`；是 JSON 编码数组，不是重复表单字段 |

选中数组 1～10000 项，每项为本词书真实正整数 ID。整本模式不要传 selected_word_ids。
兼容 `all_cet4`/`selected_cet4`，只允许 book_id=1；鸿蒙统一用 all_book/selected_book。
multipart 边界由上传实现生成；不能只写一个无 boundary 的 Content-Type。上传实际文件内容，不能把设备路径作为 file 字符串发给后端。

成功 HTTP 200：

```json
{"code":200,"message":"上传成功","data":{"movie_id":12,"tracking_token":"<tracking-token>","status":"pending","originalFilename":"demo.mp4","filename":"<uuid>.mp4","path":"<server-internal-path>"}}
```

保存 movie_id 和 tracking_token。path 是服务器内部路径，不能拿来播放或在页面展示。一次上传失败不要自动无条件重传（当前无幂等上传键，可能产生重复任务）。

### 查询状态

`GET /video/12/status` + Bearer，返回：

```json
{"code":200,"message":"查询成功","data":{"movie_id":12,"status":"completed","error_message":null,"matched_word_count":8}}
```

状态：pending → processing → completed 或 failed。建议每 2～3 秒查询一次，页面离开停止前台轮询；服务没有真实百分比，不显示虚构进度。
failed 时展示 error_message；completed 后读取 words；匹配数为 0 是正常结果。
token 过期但任务未完成时可用 `GET /video/12/progress`，请求头 `X-Video-Tracking: <tracking_token>`。
tracking_token 有效期24小时，仅能读取对应视频状态，不能用于播放、闪卡或登录，也不会随普通退出登录立即失效。退出后客户端应停止对应用户提醒并清除该凭证。

### 闪卡数据

`GET /video/12/words` + Bearer。未完成返回409，无权访问返回404。成功：

```json
{"code":200,"message":"查询成功","data":{"movie_id":12,"words":[{"word":"example","meaning":"例子","start_time":1.2,"end_time":3.5,"sentence_text":"This is an example."}]}}
```

时间单位为秒，新数据通常是完整句子范围；播放跳转到 start_time，按 end_time 控制片段结束。历史 sentence_text 可为 null。列表可包含相同单词的不同片段，不要用单词文本作为唯一列表 key。

## 7. 视频历史、播放与删除（需要 Bearer，stream 例外）

- `GET /video/history?limit=30` → `{code:200,data:{items:[{id,file_name,status,created_at}],next_before_id}}`。
  limit 范围1～100；下一页加 before_id=next_before_id；null 表示没有下一页。
- `GET /video/12/cover` 返回 JPEG 二进制；404/503 时显示占位封面。网络图片组件需要支持鉴权，否则先用网络层下载到私有缓存。
- `GET /video/12/file` 返回视频文件，支持 Range；需要 Bearer。
- 原生播放器不方便附加请求头时，先 `POST /video/12/playback` + Bearer，无 body，得到 `{"data":{"ticket":"...","expires_at":UNIX秒}}`。
  播放 URL：`BASE_URL/video/12/stream?ticket=<URL编码的ticket>`。stream 不再附加 Bearer，票据绑定视频和会话。
  到期时间不超过登录 token 且最多30分钟；过期需重新获取，退出登录后旧票据也会失效。不要把 URL 写入日志。
- `DELETE /video/12` 成功204，不存在或已删除返回404。删除前由界面确认；删除的是电影记录及关联闪卡，不保证清理服务器视频文件。已有收藏快照不会随电影删除。

## 8. 收藏（需要 Bearer）

- `PUT /favorites/words`，JSON `{"movie_id":12,"word":"example"}`，必须为当前用户已完成电影中真实存在的词。
  成功 `{code:200,data:FavoriteWord}`；相同用户同一规范化单词再次保存会更新快照。
- `GET /favorites/words/status?word=example` → `{code:200,data:{word:"example",is_favorite:true}}`。
- `GET /favorites/words?limit=30` → `{code:200,data:{items:[FavoriteWord],next_before_id}}`，下一页用 before_id，上限100。
- `DELETE /favorites/words?word=example` → 204，幂等。

FavoriteWord 字段：id、word、meaning、sentence_text、source_movie_id、source_movie_name、start_time、end_time、created_at。
除 id、word、created_at 外均允许 null。当前收藏仅支持电影词条，不支持直接收藏任意词书词条。来源电影被删除时仍显示收藏内容，无法播放则提示。

## 9. AI 讲解与聊天

当前 `/ai/*` 沿用原实现，不强制登录；前端可以统一携带 Bearer，但服务端不会因此校验会话。公开部署前应补齐鉴权与限流。模型供应商当前是后端配置的硅基流动，使用华为 IDE/智能体开发并不自动更换模型供应商。

`POST /ai/explain-word`：

```json
{"word":"example","sentence":"This is an example.","previous_sentence":"","next_sentence":""}
```

word、sentence 必填，最大100/4000字符；前后句可省略，各最多4000字符。
成功直接返回 `{word,basic_meaning,context_meaning,sentence_translation,part_of_speech,usage,collocations,example,example_translation}`；collocations 是字符串数组，其余为字符串。
无例句的旧闪卡应让用户提供上下文或改用聊天，不能把 null 作为 sentence。

`POST /ai/chat/stream`，请求：

```json
{"messages":[{"role":"user","content":"请解释 example 的用法"}],"word":"example","sentence":"This is an example."}
```

messages 为1～21条，role仅 user/assistant，最后一条必须 user；每条最多8000字符，总计最多32000。word、sentence可省略。前端保存并发送本轮所需历史，后端不保存聊天会话。
响应是 `text/event-stream`，不是普通 JSON。协议示例：

```text
: connected

data: {"type":"delta","content":"example 表示"}

data: {"type":"delta","content":"例子。"}

data: {"type":"done"}

```

失败事件 `data: {"type":"error","message":"..."}`。即使 HTTP200 也必须处理 error。
使用支持 POST 与增量字节的请求实现；UTF-8 跨块解码，累计到空行再解析事件，忽略以冒号开头的注释。
网络分块不等于事件边界；只有 done 表示完整成功，提前断流应提示重试。离开页面取消连接，超时预留服务端最多180秒生成时间。

## 10. 联调样例（PowerShell）

使用自己的测试账号，示例不会自动注册账号或上传视频：

```powershell
$base = 'http://127.0.0.1:8000'
$loginBody = @{account='learner01'; password='demo12345'} | ConvertTo-Json
$session = Invoke-RestMethod "$base/user/login" -Method Post -ContentType 'application/json' -Body $loginBody
$headers = @{Authorization="Bearer $($session.access_token)"}
Invoke-RestMethod "$base/user/me" -Headers $headers
Invoke-RestMethod "$base/wordbooks" -Headers $headers
Invoke-RestMethod "$base/user/me/wordbook" -Method Put -Headers $headers -ContentType 'application/json' -Body '{"book_id":1}'
Invoke-RestMethod "$base/wordbooks/1/words?start_index=1&end_index=20" -Headers $headers
Invoke-RestMethod "$base/video/history?limit=30" -Headers $headers
```

## 11. 实现顺序与验收

1. 配置 BASE_URL、网络权限、JSON 网络层、错误处理、会话保存；健康探测 GET /health 只检查 status 是否为 ok，旧 isok 字段无业务含义，也不代表数据库健康。
2. 注册登录、个人资料、首页打卡、词书目录和选择；重启 App 后恢复登录并读取 /user/me。
3. 词条分页、搜索、自选范围；切换用户或词书时隔离本地勾选缓存。
4. 选视频、multipart 上传、状态轮询、历史列表、闪卡、原生播放。
5. 收藏、AI 讲解、流式聊天、退出登录；验证空结果、401、409、422、503、取消请求和重复点击。
6. 在实际 HarmonyOS SDK 编译并真机验证上传/播放/流式响应。保存华为智能体的实际对话、代码修改、构建和运行记录。

当前后端没有通用学习进度同步、词书任意词收藏或聊天历史存储接口。闪卡位置和 UI 设置可先本地保存，并按用户/词书/电影隔离；不要在页面声称这些已云同步。
