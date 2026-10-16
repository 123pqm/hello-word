# 网页视频上传

原有 `POST /video/upload` 是通用 multipart 文件接口，本身支持 Android 和浏览器。
新增 `POST /video/web/upload` 作为网页专用入口，两个路由共享同一个处理函数。
不复制业务逻辑、不新增数据库表，也不改 Android 的原接口和返回结构。

## 请求

请求头：`Authorization: Bearer <access_token>`。
请求体：`multipart/form-data`，由浏览器 FormData 自动生成 Content-Type 和 boundary。

| 字段 | 内容 |
| --- | --- |
| file | 用户选择的视频文件二进制，不是 `C:/...` 路径 |
| book_id | 1 四级、3 考研完整、4 考研高频；默认 1 |
| selection_mode | 推荐网页用 all_book（整本）或 selected_book（自选）；默认 all_cet4 兼容旧端 |
| selected_word_ids | 仅自选时传 JSON 字符串，例如 `[1,7]`，不能是空数组；整本不传 |

目前沿用原上传校验：扩展名 MP4/MOV/MKV/AVI，并且文件 MIME 必须以 video/ 开头。
浏览器若对某个文件给出空 MIME 或 application/octet-stream，会返回 400；不会自动放宽文件校验。
优先用 video/mp4 的 MP4 文件联调。MIME/扩展名只是基础筛选，不是媒体内容完整性验证。

## 返回与后续流程

HTTP 200，结构与原接口一致：`{code: 200, message: "上传成功", data: {...}}`。
data 包含 movie_id、tracking_token、status=pending、originalFilename、filename、path。
其中 path 是服务器内部路径，为兼容旧接口保留，网页不得用它作为播放地址。

1. 上传成功只代表保存文件并创建任务，不代表识别完成。
2. 使用同一登录令牌请求 `GET /video/{movie_id}/status`。
3. completed 后请求 `GET /video/{movie_id}/words`。
4. 历史仍为 `GET /video/history`；同一账号在手机和网页共用历史。
5. 文件播放接口为 `GET /video/{movie_id}/file`，需要鉴权。
   浏览器 video 标签无法直接配置 Authorization，请在实现播放页面时专门处理鉴权。

## Vue 项目

`C:/hello_word/src/api/video.js` 提供 uploadWebVideo：

```js
import { uploadWebVideo } from '@/api/video'

// fileInput 是真实 <input type="file"> 元素；accessToken 来自登录响应。
const task = await uploadWebVideo({
  file: fileInput.files[0],
  token: accessToken,
  bookId: 3,
  selectedWordIds: null,
})
console.log(task.movie_id)
```

Vite 请求 `/api/video/web/upload`，开发代理去掉 `/api`，转发至 FastAPI。
上传封装允许 10 分钟上传时限，并支持 signal 取消和 onUploadProgress 回调。
组件应在上传期间禁用按钮，防止重复创建任务；服务端没有上传幂等键。
当前只增加调用封装，登录和上传页面的完整交互尚未接入。

新增路由需要把本地 `app/main.py` 同步到 Ubuntu 后重启 hello-word 服务才生效。
本次改动不自动更新服务器，也没有开放公网端口或修改 CORS。
