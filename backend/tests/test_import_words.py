import pytest

from app.database import connect_database
import app.import_words as importer


def test_repeated_words_preserve_distinct_senses_and_support_bom(tmp_path):
    source = tmp_path / "cet4.txt"
    source.write_text(
        "access\tv. 获取\naccess\tn. 通道\naccess\tv. 获取\n\n"
        "damn\tinterjection. 该死\n",
        encoding="utf-8-sig",
    )
    rows, count = importer.read_cet4(source)
    assert count == 4
    assert rows == [
        ("access", "v. / n.", "v. 获取；n. 通道"),
        ("damn", "interjection.", "该死"),
    ]


@pytest.mark.parametrize("content", ["bad line", "", "word\tn. " + "字" * 201])
def test_invalid_file_fails_before_database_changes(tmp_path, monkeypatch, content):
    source = tmp_path / "cet4.txt"
    source.write_text(content, encoding="utf-8")

    def unexpected_initialization():
        pytest.fail("文件校验失败时不应连接数据库")

    monkeypatch.setattr(importer, "initialize_database", unexpected_initialization)
    with pytest.raises(ValueError):
        importer.import_words(source)


@pytest.mark.mysql
def test_import_upgrades_old_schema_and_can_run_twice(tmp_path, monkeypatch, mysql_test_database):
    monkeypatch.setenv("MYSQL_DATABASE", mysql_test_database)
    source = tmp_path / "cet4.txt"
    source.write_text(
        "access\tv. 获取\naccess\tn. 通道\ndamn\tinterjection. 该死\n",
        encoding="utf-8",
    )
    connection = connect_database()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "CREATE TABLE cet4 (word VARCHAR(50) NOT NULL UNIQUE, "
                "pos VARCHAR(10) NOT NULL, meaning VARCHAR(200) NOT NULL) "
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"
            )
        assert importer.import_words(source) == 2
        assert importer.import_words(source) == 2
        with connection.cursor() as cursor:
            cursor.execute("SHOW COLUMNS FROM cet4 LIKE 'pos'")
            assert cursor.fetchone()["Type"] == "varchar(32)"
            cursor.execute("SELECT word, pos, meaning FROM cet4 ORDER BY word")
            assert cursor.fetchall() == [
                {"word": "access", "pos": "v. / n.", "meaning": "v. 获取；n. 通道"},
                {"word": "damn", "pos": "interjection.", "meaning": "该死"},
            ]
    finally:
        connection.close()
