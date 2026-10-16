# 词书搜索接口

GET /wordbooks/{book_id}/search?q=app&limit=30&offset=0
Authorization: Bearer <登录凭证>

book_id 必填：1 四级，3 考研完整，4 考研高频。q 必填，去掉首尾空格后不能为空，最多80字符。limit 默认30，范围1~100；offset 默认0，范围0~100000。

搜索单词字段，忽略大小写，排序为完全一致、前缀、包含，再按单词和id稳定排序。不搜索中文释义，不进行拼写纠错或语义近义词检索。百分号和下划线按普通字符处理。

返回示例：
```json
{"data":[{"id":12,"word":"apple","meaning":"苹果","pos":"n."}],"book_id":3,"query":"app","limit":30,"has_more":false,"next_offset":null}
```
无匹配时 data 是空数组。无需返回总数。next_offset 不为空时沿用相同 book_id、q、limit 请求下一批。词库搜索期间保持不变时分页顺序稳定；重新导入词书后应重新搜索。

错误：未登录401，未开放词书404，参数错误422。

网页词条弹窗已接入：输入停止350ms后才发请求，新输入立即取消旧请求，保留跨搜索勾选状态；滚动触底加载下一批，同时保留加载更多按钮。请求中不重复加载同一页，关闭弹窗取消请求。防抖是前端行为，不是后端等待350ms，也不是服务端限流。Android 可按相同协议接入，本次未改 Android 搜索界面。

## 部署
电脑 PowerShell：
```powershell
scp "D:/hello-word-repo/deploy/word-search-update.tar.gz" ubuntu@106.53.40.242:~/
ssh ubuntu@106.53.40.242
```
进入 Ubuntu 后：
```bash
cd /home/ubuntu/hello-word
mkdir -p ~/hello-word-search-update
tar -xzf ~/word-search-update.tar.gz -C ~/hello-word-search-update
cp app/wordbooks.py "app/wordbooks.py.bak-$(date +%Y%m%d-%H%M%S)"
cp ~/hello-word-search-update/wordbooks.py app/wordbooks.py
.venv/bin/python -c "from app.main import app; print('接口模块加载成功')"
```
出现加载成功后才继续：
```bash
sudo systemctl restart hello-word
systemctl is-active hello-word
```
返回active后刷新网页。无需数据库迁移或重新导入词库。部署前网页搜索会因为服务器缺少新接口而返回404。
