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

如果 FastAPI 正在运行，先在它的终端按 Ctrl+C，再预览和迁移原数据：

```powershell
.\.venv\Scripts\python.exe migrate_sqlite.py --dry-run
.\.venv\Scripts\python.exe migrate_sqlite.py
```

第二条只在预览通过后运行。迁移保留原 SQLite 文件和 ID；相同记录跳过；冲突或插入失败时整批新增回滚。第一次会自动建库和建表，建库建表不会随插入回滚。修改 `.env` 后需要重启 FastAPI。

## 运行开发服务器

在当前目录执行：

```powershell
.\dev.ps1
```

打开以下地址：

- 接口文档：http://127.0.0.1:8000/docs
- 健康检查：http://127.0.0.1:8000/health

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
