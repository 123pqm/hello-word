# 网页分段播放更新

本地网页已改为 POST /video/{id}/playback 获取短期凭证，再由 video 元素 GET /video/{id}/stream 按 Range 请求。原 Android /file 接口保持不变。无需数据库迁移。

## Windows PowerShell：上传
```powershell
scp "D:/hello-word-repo/deploy/playback-update.tar.gz" ubuntu@106.53.40.242:~/
```

## Ubuntu：备份并更新
```bash
cd /home/ubuntu/hello-word
mkdir -p ~/hello-word-playback-update
tar -xzf ~/playback-update.tar.gz -C ~/hello-word-playback-update
backup_dir="backups/playback-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup_dir"
cp app/video_status.py "$backup_dir/video_status.py"
echo "备份目录：$backup_dir"
cp ~/hello-word-playback-update/video_status.py app/video_status.py
.venv/bin/python -c "from app.main import app; print('接口模块加载成功')"
sudo systemctl restart hello-word
systemctl is-active hello-word
curl -sS http://127.0.0.1:8000/health
```
应显示 active，健康接口返回 status:ok。如果模块加载失败，停止后续步骤，将备份文件复制回 app/video_status.py。

刷新网页，重新打开闪卡。在浏览器网络面板中确认 playback 返回 200，stream 的 Range 请求返回 206，Content-Range 显示请求区间。拖动进度条或点击跳转原句，应请求目标附近数据。无需重新上传原电影。

## 限制
播放凭证最长 30 分钟且不会超过登录有效期，退出登录后不可继续请求新片段；已缓冲的内容不会被远程清除。过期后重新登录并打开闪卡。此更新不转码，浏览器不支持的编码仍需转换；MP4 元数据在尾部时浏览器可能额外请求文件尾部。服务器带宽也会影响起播和跳转速度。

短期 ticket 是限电影的访问凭证，不是账号 token。不要分享播放链接。生产代理的访问日志应只记录路径，不记录查询参数（Nginx 使用 $uri 而不是包含参数的 $request_uri）。

## 回退
将上面备份目录的 video_status.py 复制回 app/video_status.py，再重启 hello-word。网页此次改动依赖新接口，回退后端时也需同步回退网页播放改动。
