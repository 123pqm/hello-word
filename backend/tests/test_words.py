import pytest


pytestmark = pytest.mark.mysql


def test_get_word_by_id(client) -> None:
    response = client.get("/words/1")

    assert response.status_code == 200
    assert response.json() == {
        "id": 1,
        "word": "experience",
        "meaning": "经验；经历",
    }


def test_get_word_not_found(client) -> None:
    response = client.get("/words/999")

    assert response.status_code == 404
    assert response.json() == {"detail": "没有找到这个单词"}


def test_search_words(client) -> None:
    response = client.get("/words", params={"keyword": "exper", "limit": 5})

    assert response.status_code == 200
    assert response.json() == {
        "items": [
            {
                "id": 1,
                "word": "experience",
                "meaning": "经验；经历",
            }
        ],
        "total": 1,
    }


def test_search_words_requires_keyword(client) -> None:
    response = client.get("/words")

    assert response.status_code == 422


def test_search_words_validates_limit(client) -> None:
    response = client.get(
        "/words",
        params={"keyword": "movie", "limit": "not-a-number"},
    )

    assert response.status_code == 422


def test_search_words_reports_total_before_limit(client) -> None:
    response = client.get("/words", params={"keyword": "e", "limit": 2})

    assert response.status_code == 200
    assert len(response.json()["items"]) == 2
    assert response.json()["total"] == 4


def test_add_word_persists_and_auto_increments(client):
    response = client.post("/words", json={"word": "  Hello  ", "meaning": "  你好  "})
    assert response.status_code == 201
    data = response.json()
    assert data["id"] > 5
    assert data["word"] == "hello"
    assert data["meaning"] == "你好"
    # 另一次请求会创建新连接，这样可以验证 commit 确实持久化。
    assert client.get(f"/words/{data['id']}").json() == data


def test_duplicate_word_rolls_back_and_next_request_works(client):
    response = client.post("/words", json={"word": " MOVIE ", "meaning": "另一个释义"})
    assert response.status_code == 409
    assert client.get("/words/2").json()["meaning"] == "电影"
    assert client.post("/words", json={"word": "newword", "meaning": "新词"}).status_code == 201


@pytest.mark.parametrize("payload", [
    {"word": "   ", "meaning": "释义"},
    {"word": "hello", "meaning": "   "},
    {"word": "x" * 51, "meaning": "释义"},
    {"word": "hello", "meaning": "x" * 201},
])
def test_add_word_validates_input(client, payload):
    assert client.post("/words", json=payload).status_code == 422


def test_sql_parameters_and_unicode_are_preserved(client):
    word = "x'); drop table words; --"
    response = client.post("/words", json={"word": word, "meaning": "引号与中文 🎬"})
    assert response.status_code == 201
    assert client.get(f"/words/{response.json()['id']}").json() == response.json()
    assert client.get("/words/1").status_code == 200
