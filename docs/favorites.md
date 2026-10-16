# 收藏单词

在现有 MySQL 数据库中新增 `favorite_words` 表，不单独创建数据库。
启动时通过 `create_favorite_words_table` 幂等建表，不修改原电影或单词记录。

## 存储规则

- `user_id` 从登录 token 取得，不接受客户端指定用户。
- `UNIQUE (user_id, word_key)`：去首尾空格并转小写，同一账号同一单词只保存一次。
- `INDEX (user_id, id)`：按用户分页读取，收藏列表不需要关联电影表。
- 保存 `word`、`meaning`、`sentence_text`、来源电影编号/名称、句子起止秒数及收藏时间。
- 内容从该用户已完成电影的 `movie_words` 读取，客户端只提交电影编号和单词。
- 重复收藏更新内容快照，保留原收藏编号和时间。
- 删除来源电影后保留收藏；来源编号仅作记录，不保证视频仍可播放。
- `movie_words` 继续通过 `movie_id` 关联电影归属，不新增冗余用户字段。

## 接口

全部要求 `Authorization: Bearer <登录返回的 token>`，返回禁止缓存。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| PUT | `/favorites/words` | 收藏单词，或更新已收藏单词的内容 |
| GET | `/favorites/words/status?word=movie` | 查询当前账号是否收藏 |
| GET | `/favorites/words?limit=30&before_id=123` | 按收藏编号倒序分页；第一页不传 before_id |
| DELETE | `/favorites/words?word=movie` | 取消收藏，重复取消仍返回 204 |

PUT 请求示例（用自己的电影编号及闪卡中的实际单词替换）：

```json
{"movie_id": 26, "word": "movie"}
```

PUT 返回 `{"code":200,"data":{...收藏详情...}}`；状态返回
`{"code":200,"data":{"word":"movie","is_favorite":true}}`。
列表返回 `{"code":200,"data":{"items":[...],"next_before_id":123}}`，
`next_before_id` 为 null 表示没有下一页。

错误：401 未登录/凭证失效，404 来源不属于当前用户或单词不存在，
409 来源尚未分析完成，422 参数非法，503 数据库操作失败。
取消收藏仅匹配当前用户，不需要来源电影仍然存在。

## Android

`word_flashcard` 单词居中，卡片右上角使用小星标（空心未收藏、实心已收藏）。
每次切卡及返回页面读取服务器状态；保存期间禁用重复点击，迟到的响应不会更改其他单词。
请求失败不伪装成已收藏；结果不确定时重新查询。

个人页“我的收藏”打开 `FavoritesActivity`，沿用 CET-4 列表的背景、白色卡片和字号，
展示单词、释义、已有例句及来源电影，无勾选或确认选词操作。
支持下拉刷新、分页、空状态和加载重试；每条收藏可确认删除。
删除调用原取消收藏接口，成功后移除该条，失败时保留显示；不删除电影里的原词条。

## 验证

`tests/test_favorites.py` 使用随机测试数据库验证归属、去重、并发保存、分页、
取消收藏、来源删除后的快照、索引及参数校验。
`FlashcardNavigationTest` 使用测试接口验证切卡、重开、取消、失败恢复和异步状态隔离。
