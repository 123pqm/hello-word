from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.database import (connect_database, create_users_table, create_movie_tables,
                          create_favorite_words_table, get_database)
from app.main import app, create_access_token


@pytest.fixture
def favorites_client(mysql_test_database):
    users, movies = [], []
    with connect_database(database_name=mysql_test_database) as db:
        create_users_table(db)
        create_movie_tables(db)
        create_favorite_words_table(db)
        with db.cursor() as c:
            for _ in range(2):
                c.execute("INSERT INTO users (account, password_hash) VALUES (%s, 'unused')", ("f" + uuid4().hex[:12],))
                users.append(c.lastrowid)
            for owner in (users[0], users[0], users[1]):
                c.execute("INSERT INTO movies (user_id, file_name, status) VALUES (%s, 'example.mp4', 'completed')", (owner,))
                movie_id = c.lastrowid
                movies.append(movie_id)
                c.executemany("""INSERT INTO movie_words
                    (movie_id, word, meaning, sentence_text, start_time, end_time) VALUES (%s,%s,%s,%s,1,2)""",
                    [(movie_id, "Movie", "电影", "It's a movie."), (movie_id, "world", "世界", None)])
        db.commit()

    def database():
        conn = connect_database(database_name=mysql_test_database)
        try:
            yield conn
        finally:
            conn.close()

    app.dependency_overrides[get_database] = database
    client = TestClient(app, headers={"Authorization": "Bearer " + create_access_token(users[0])})
    try:
        yield client, users, movies, mysql_test_database
    finally:
        client.close()
        app.dependency_overrides.pop(get_database, None)


@pytest.mark.mysql
def test_snapshot_dedup_pagination_and_survival_after_movie_delete(favorites_client):
    client, _, movies, name = favorites_client
    saved = client.put("/favorites/words", json={"movie_id": movies[0], "word": " movie "})
    assert saved.status_code == 200, saved.text
    first = saved.json()["data"]
    assert first["meaning"] == "电影"
    assert first["sentence_text"] == "It's a movie."
    assert "user_id" not in first
    repeated = client.put("/favorites/words", json={"movie_id": movies[1], "word": "MOVIE"}).json()["data"]
    assert repeated["id"] == first["id"]
    assert repeated["source_movie_id"] == movies[1]
    assert repeated["created_at"] == first["created_at"]
    assert client.get("/favorites/words/status", params={"word": "MOVIE"}).json()["data"]["is_favorite"]
    assert client.put("/favorites/words", json={"movie_id": movies[0], "word": "world"}).status_code == 200
    page = client.get("/favorites/words", params={"limit": 1}).json()["data"]
    assert page["items"][0]["word"] == "world"
    second = client.get("/favorites/words", params={"limit": 1, "before_id": page["next_before_id"]}).json()["data"]
    assert second["items"][0]["id"] == first["id"]
    assert second["next_before_id"] is None
    assert client.delete(f"/video/{movies[1]}").status_code == 204
    assert len(client.get("/favorites/words").json()["data"]["items"]) == 2
    assert client.delete("/favorites/words", params={"word": "Movie"}).status_code == 204
    assert client.delete("/favorites/words", params={"word": "Movie"}).status_code == 204
    assert not client.get("/favorites/words/status", params={"word": "movie"}).json()["data"]["is_favorite"]
    with connect_database(database_name=name) as db:
        # Re-running startup preserves favorites and their indexes.
        create_favorite_words_table(db)
        with db.cursor() as c:
            c.execute("SHOW INDEX FROM favorite_words")
            assert {"uq_favorite_user_word", "idx_favorite_user_id"} <= {r["Key_name"] for r in c.fetchall()}
    assert len(client.get("/favorites/words").json()["data"]["items"]) == 1


@pytest.mark.mysql
def test_favorites_are_private_and_source_must_belong_to_user(favorites_client):
    client, users, movies, _ = favorites_client
    assert client.put("/favorites/words", json={"movie_id": movies[2], "word": "movie"}).status_code == 404
    assert client.put("/favorites/words", json={"movie_id": movies[0], "word": "unknown"}).status_code == 404
    assert client.put("/favorites/words", json={"movie_id": movies[0], "word": "movie"}).status_code == 200
    other = {"Authorization": "Bearer " + create_access_token(users[1])}
    assert client.get("/favorites/words", headers=other).json()["data"]["items"] == []
    assert not client.get("/favorites/words/status", params={"word": "movie"}, headers=other).json()["data"]["is_favorite"]
    assert client.delete("/favorites/words", params={"word": "movie"}, headers=other).status_code == 204
    assert len(client.get("/favorites/words").json()["data"]["items"]) == 1
    assert client.put("/favorites/words", headers=other, json={"movie_id": movies[2], "word": "movie"}).status_code == 200
    assert len(client.get("/favorites/words", headers=other).json()["data"]["items"]) == 1


@pytest.mark.mysql
def test_concurrent_favorite_is_one_row(favorites_client):
    client, _, movies, _ = favorites_client
    def save(_):
        r = client.put("/favorites/words", json={"movie_id": movies[0], "word": "movie"})
        assert r.status_code == 200, r.text
        return r.json()["data"]["id"]
    with ThreadPoolExecutor(max_workers=4) as pool:
        assert len(set(pool.map(save, range(8)))) == 1
    assert len(client.get("/favorites/words").json()["data"]["items"]) == 1


@pytest.mark.mysql
@pytest.mark.parametrize("extra", [{"word": " "}, {"word": "x" * 101}, {"movie_id": 0}, {"user_id": 123}])
def test_invalid_or_forged_favorite_request(favorites_client, extra):
    client, _, movies, _ = favorites_client
    assert client.put("/favorites/words", json={"movie_id": movies[0], "word": "movie", **extra}).status_code == 422


@pytest.mark.mysql
def test_requires_login_and_completed_movie(favorites_client):
    client, _, movies, name = favorites_client
    with connect_database(database_name=name) as db:
        with db.cursor() as c:
            c.execute("UPDATE movies SET status='processing' WHERE id=%s", (movies[0],))
        db.commit()
    assert client.put("/favorites/words", json={"movie_id": movies[0], "word": "movie"}).status_code == 409
    for method, path in (("get", "/favorites/words"), ("get", "/favorites/words/status?word=movie"),
                         ("delete", "/favorites/words?word=movie"), ("put", "/favorites/words")):
        assert client.request(method, path, headers={"Authorization": "Bearer invalid"},
                              json={"movie_id": movies[0], "word": "movie"}).status_code == 401
