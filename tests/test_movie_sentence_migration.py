"""仅在新建的随机测试库验证旧表升级，不操作业务库。"""
from uuid import uuid4

import pytest

from app.database import connect_database, create_movie_tables


@pytest.mark.mysql
def test_legacy_words_survive_repeated_sentence_upgrade():
    name = "movie_vocab_test_" + uuid4().hex
    connection = connect_database(select_database=False)
    created = False
    try:
        with connection.cursor() as cursor:
            cursor.execute(f"CREATE DATABASE `{name}` CHARACTER SET utf8mb4")
            created = True
            connection.select_db(name)
            cursor.execute("""CREATE TABLE movie_words (
                id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                movie_id INT NOT NULL, word VARCHAR(100) NOT NULL,
                meaning VARCHAR(500), start_time DECIMAL(10,2) NOT NULL,
                end_time DECIMAL(10,2) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
            cursor.execute("""INSERT INTO movie_words
                (movie_id, word, meaning, start_time, end_time)
                VALUES (17, 'beautiful', '美丽的', 1.25, 2.50)""")
            cursor.execute("SELECT * FROM movie_words")
            original = cursor.fetchone()
        connection.commit()
        create_movie_tables(connection)
        create_movie_tables(connection)
        with connection.cursor() as cursor:
            cursor.execute("SELECT * FROM movie_words")
            assert cursor.fetchone() == dict(original, sentence_text=None)
            cursor.execute("UPDATE movie_words SET sentence_text = %s", ("It's a beautiful day.",))
        connection.commit()
        create_movie_tables(connection)
        with connection.cursor() as cursor:
            cursor.execute("SELECT * FROM movie_words")
            assert cursor.fetchone() == dict(original, sentence_text="It's a beautiful day.")
    finally:
        if created:
            connection.select_db("information_schema")
            with connection.cursor() as cursor:
                cursor.execute(f"DROP DATABASE `{name}`")
        connection.close()
