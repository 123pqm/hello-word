import logging
from time import perf_counter

from app.database import connect_database
from app.services.cet4_service import match_cet4_words
from app.services.whisper_service import transcribe_movie


logger = logging.getLogger(__name__)


def process_movie(movie_id: int, file_path: str, selected_word_ids: list[int] | None = None):

    print("开始处理电影：", movie_id)

    started_at = perf_counter()
    database = None
    try:
        words = transcribe_movie(file_path)
        print(f"识别完成：共 {len(words)} 个单词，耗时 {perf_counter() - started_at:.2f} 秒")
        if words:
            print(f"最后一个单词结束位置：{max(word['end'] for word in words):.2f} 秒")

        # 推理结束后创建独立连接，不复用上传请求的连接。
        database = connect_database()
        cet4_words = match_cet4_words(words, database, selected_word_ids)
        print(f"四级词汇匹配完成：去重后共 {len(cet4_words)} 个单词，每词保留首次出现所在句子的时间")
        with database.cursor() as cursor:
            if cet4_words:
                cursor.executemany(
                    """
                    INSERT INTO movie_words (movie_id, word, meaning, start_time, end_time, sentence_text)
                    VALUES (%s, %s, %s, %s, %s, %s)
                    """,
                    [
                        (movie_id, word["word"], word["meaning"], word["start"], word["end"], word.get("sentence_text"))
                        for word in cet4_words
                    ],
                )
            cursor.execute(
                "UPDATE movies SET status = %s, error_message = NULL WHERE id = %s",
                ("completed", movie_id),
            )
        # 单词和完成状态一起提交，避免只保存一部分结果。
        database.commit()
        print("电影处理结果已保存：", movie_id)
        print(f"电影 {movie_id} 最终匹配结果（全部 {len(cet4_words)} 条）：", flush=True)
        for index, word in enumerate(cet4_words, start=1):
            print(
                f"[电影 {movie_id} · {index}] {word['word']} | {word['meaning']} | "
                f"{word['start']:.2f}～{word['end']:.2f} 秒",
                flush=True,
            )
        if not cet4_words:
            print(f"电影 {movie_id}：没有匹配到所选范围内的单词", flush=True)
    except Exception as error:
        logger.exception("电影 %s 后台处理失败", movie_id)
        try:
            if database is not None:
                database.rollback()
                database.close()
                database = None
            database = connect_database()
            with database.cursor() as cursor:
                cursor.execute(
                    "UPDATE movies SET status = %s, error_message = %s WHERE id = %s",
                    ("failed", str(error)[:2000], movie_id),
                )
            database.commit()
        except Exception:
            logger.exception("无法记录电影 %s 的失败状态", movie_id)
    finally:
        if database is not None:
            database.close()
