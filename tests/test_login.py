"""登录与 JWT 签发测试；默认使用模拟连接，不访问业务数据库。"""

from datetime import datetime, timezone
import secrets
from unittest.mock import MagicMock

from fastapi.testclient import TestClient
import jwt
import pytest

from app.database import create_users_table, get_database
from app.main import (
    JWT_ALGORITHM,
    TOKEN_EXPIRE_SECONDS,
    app,
    create_access_token,
    get_jwt_secret_key,
    password_hasher,
)


TEST_PASSWORD = "DemoPass123!"


@pytest.fixture
def jwt_key(monkeypatch):
    key = secrets.token_hex(32)
    monkeypatch.setenv("JWT_SECRET_KEY", key)
    return key


@pytest.fixture(scope="module")
def password_hash():
    return password_hasher.hash(TEST_PASSWORD)


@pytest.fixture
def login_client(jwt_key, password_hash):
    database = MagicMock()
    cursor = database.cursor.return_value.__enter__.return_value
    cursor.fetchone.return_value = {
        "id": 7,
        "account": "demo_user",
        "password_hash": password_hash,
    }
    app.dependency_overrides[get_database] = lambda: database
    # 不进入 lifespan，避免启动代码连接真实业务库。
    client = TestClient(app)
    try:
        yield client, database, cursor
    finally:
        client.close()
        app.dependency_overrides.pop(get_database, None)


def test_login_returns_signed_token(login_client, jwt_key, password_hash):
    client, database, cursor = login_client
    before = int(datetime.now(timezone.utc).timestamp())
    response = client.post(
        "/user/login",
        json={"account": " demo_user ", "password": TEST_PASSWORD},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["account"] == "demo_user"
    assert body["reply"] == "登录成功"
    assert body["token_type"] == "bearer"
    assert body["expires_in"] == TOKEN_EXPIRE_SECONDS
    assert set(body) == {"account", "reply", "access_token", "token_type", "expires_in"}
    claims = jwt.decode(
        body["access_token"], jwt_key, algorithms=[JWT_ALGORITHM],
        options={"require": ["sub", "iat", "exp"]},
    )
    assert claims["sub"] == "7"
    assert before <= claims["iat"] <= int(datetime.now(timezone.utc).timestamp())
    assert claims["exp"] - claims["iat"] == TOKEN_EXPIRE_SECONDS
    assert set(claims) == {"sub", "iat", "exp"}
    assert TEST_PASSWORD not in response.text
    assert password_hash not in response.text
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    sql, parameters = cursor.execute.call_args.args
    assert "SELECT id, account, password_hash" in sql
    assert parameters == ("demo_user",)
    database.commit.assert_not_called()


@pytest.mark.parametrize("missing_user", [False, True])
def test_wrong_credentials_have_same_error(login_client, missing_user):
    client, _, cursor = login_client
    if missing_user:
        cursor.fetchone.return_value = None
    response = client.post(
        "/user/login", json={"account": "demo_user", "password": "WrongPass123!"}
    )
    assert response.status_code == 401
    assert response.json() == {"detail": "账号或密码错误"}


@pytest.mark.parametrize("payload", [
    {"account": "     ", "password": TEST_PASSWORD},
    {"account": "demo_user", "password": ""},
    {"account": "demo_user"},
])
def test_invalid_login_input(login_client, payload):
    client, _, cursor = login_client
    response = client.post("/user/login", json=payload)
    assert response.status_code == 422
    cursor.execute.assert_not_called()


def test_password_whitespace_is_preserved(login_client):
    client, _, cursor = login_client
    password = " " + TEST_PASSWORD + " "
    cursor.fetchone.return_value["password_hash"] = password_hasher.hash(password)
    assert client.post(
        "/user/login", json={"account": "demo_user", "password": password}
    ).status_code == 200
    assert client.post(
        "/user/login", json={"account": "demo_user", "password": TEST_PASSWORD}
    ).status_code == 401


def test_database_error_not_exposed(login_client, caplog):
    client, _, cursor = login_client
    cursor.execute.side_effect = RuntimeError("private database error")
    response = client.post(
        "/user/login", json={"account": "demo_user", "password": TEST_PASSWORD}
    )
    assert response.status_code == 500
    assert response.json() == {"detail": "登录失败，请稍后重试"}
    assert "private database error" not in response.text
    assert "登录接口发生内部错误" in caplog.text


def test_invalid_stored_hash_does_not_issue_token(login_client):
    client, _, cursor = login_client
    cursor.fetchone.return_value["password_hash"] = "not-a-password-hash"
    response = client.post(
        "/user/login", json={"account": "demo_user", "password": TEST_PASSWORD}
    )
    assert response.status_code == 500
    assert response.json() == {"detail": "登录失败，请稍后重试"}


@pytest.mark.parametrize("key", [None, "", "too-short"])
def test_missing_or_weak_secret_cannot_issue_token(monkeypatch, key):
    if key is None:
        monkeypatch.delenv("JWT_SECRET_KEY", raising=False)
    else:
        monkeypatch.setenv("JWT_SECRET_KEY", key)
    with pytest.raises(RuntimeError, match="JWT_SECRET_KEY"):
        create_access_token(7)


def test_token_requires_correct_signing_key(jwt_key):
    token = create_access_token(7)
    with pytest.raises(jwt.InvalidSignatureError):
        jwt.decode(token, secrets.token_hex(32), algorithms=[JWT_ALGORITHM])


def test_token_expiration_claim_is_enforced(jwt_key, monkeypatch):
    # 此项验证签发的 exp 能被标准 JWT 验证器执行，不代表其他接口已加鉴权。
    monkeypatch.setattr("app.main.TOKEN_EXPIRE_SECONDS", -60)
    token = create_access_token(7)
    with pytest.raises(jwt.ExpiredSignatureError):
        jwt.decode(token, jwt_key, algorithms=[JWT_ALGORITHM])


def test_openapi_exposes_token_response(login_client):
    client, _, _ = login_client
    schema = client.get("/openapi.json").json()
    assert "200" in schema["paths"]["/user/login"]["post"]["responses"]
    model = schema["components"]["schemas"]["login_response"]
    assert "access_token" in model["required"]
    assert "expires_in" in model["required"]


@pytest.mark.mysql
def test_register_then_login_with_isolated_mysql(client, mysql_database, jwt_key):
    # mysql_database 只连接 conftest.py 创建的临时库，绝不写用户的业务库。
    create_users_table(mysql_database)
    mysql_database.commit()
    credentials = {"account": "jwt_test_user", "password": TEST_PASSWORD}
    registered = client.post("/user/sign", json=credentials)
    assert registered.status_code == 201
    logged_in = client.post("/user/login", json=credentials)
    assert logged_in.status_code == 200
    claims = jwt.decode(
        logged_in.json()["access_token"], get_jwt_secret_key(),
        algorithms=[JWT_ALGORITHM], options={"require": ["sub", "iat", "exp"]},
    )
    assert claims["sub"] == str(registered.json()["id"])
    assert client.post(
        "/user/login", json={**credentials, "password": "WrongPass123!"}
    ).status_code == 401
