# AI 影视词汇学习后端

当前数据库为本机 MySQL 8，通过 PyMySQL 连接。原 `data/words.db` 留作迁移来源，不再是接口读写的数据源。

详细学习说明见 [SQLite 切换 MySQL 操作与代码说明](../MYSQL_SWITCH_GUIDE.md)。

## 首次配置与迁移

先把 `.env.example` 复制为 `.env`（已有 `.env` 时直接编辑），在本机填写 `MYSQL_PASSWORD`。文件已被 Git 忽略，密码不要发到聊天或写进 Python 源码。

当前默认地址为 `127.0.0.1:3306`，账号为 `root`，数据库为 `movie_vocab`。这是本机学习配置，正式部署使用专用数据库账号。

在 Windows PowerShell 中进入当前 backend 目录，安装锁定依赖：

```powershell
& '..\..\tools\uv\bin\uv.exe' sync --cache-dir '.uv-cache'
```

如果旧的 `tools/uv/bin/uv.exe` 路径提示拒绝访问，本次已在项目虚拟环境中安装可用的 uv，可以改用：

```powershell
.\.venv\Scripts\python.exe -m uv sync --locked --inexact --cache-dir '.uv-cache'
```

`--inexact` 保留环境里额外安装的 pip、uv 等工具。项目依赖和锁文件使用 TUNA HTTPS 镜像。

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

前端后续通过 `Authorization: Bearer <access_token>` 携带凭证。**当前只实现签发：单词等原有接口尚未添加 token 校验，也尚未实现用户数据隔离。** 下一步应实现受保护接口的固定算法验签、过期校验、用户查询及资源权限检查。正式部署还需要 HTTPS、登录限流和客户端安全存储；不要公开部署当前学习版。

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
