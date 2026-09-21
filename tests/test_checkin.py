from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta, timezone
import json
from threading import Barrier
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

import app.checkin as checkin
from app.database import connect_database, create_users_table, get_database
from app.main import app, create_access_token


def test_week_uses_full_dates_not_stale_weekdays():
    now = datetime(2026, 9, 23, 12, tzinfo=checkin.BEIJING)
    result = checkin.build_status(1, ["2026-09-21", "2026-09-15", "2026-09-23", None, None, None, None],
                                  now.date(), 1, now)
    assert result.week_count == 2
    assert [d.checked for d in result.days] == [True, False, True, False, False, False, False]
    assert [d.weekday for d in result.days] == list(range(1, 8))
    assert result.days[0].date == date(2026, 9, 21)
    assert result.days[-1].date == date(2026, 9, 27)
    assert result.days[2].is_today
    assert result.next_checkin_after_seconds == 43200


@pytest.mark.parametrize("last, expected", [(date(2026, 9, 23), 8), (date(2026, 9, 22), 8), (date(2026, 9, 21), 0), (None, 0)])
def test_expired_streak_is_not_displayed(last, expected):
    assert checkin.build_status(1, [None] * 7, last, 8,
                               datetime(2026, 9, 23, tzinfo=checkin.BEIJING)).streak_days == expected


def test_beijing_date_changes_at_utc_1600():
    before = datetime(2026, 9, 20, 15, 59, 59, tzinfo=timezone.utc).astimezone(checkin.BEIJING)
    after = (before + timedelta(seconds=1))
    assert before.date() == date(2026, 9, 20)
    assert after.date() == date(2026, 9, 21)
    assert checkin.build_status(1, [None] * 7, None, 0, before).next_checkin_after_seconds == 1
    assert checkin.build_status(1, [None] * 7, None, 0, after).days[0].date == after.date()


@pytest.fixture
def checkin_client(mysql_test_database, monkeypatch):
    monkeypatch.setenv("JWT_SECRET_KEY", "checkin-tests-only-" + "x" * 40)
    connection = connect_database(database_name=mysql_test_database)
    try:
        create_users_table(connection)
        ids = []
        with connection.cursor() as cursor:
            for _ in range(2):
                cursor.execute("INSERT INTO users (account, password_hash) VALUES (%s, 'unused')", ("c" + uuid4().hex[:12],))
                ids.append(cursor.lastrowid)
        connection.commit()
    finally:
        connection.close()

    def database():
        conn = connect_database(database_name=mysql_test_database)
        try:
            yield conn
        finally:
            conn.close()

    app.dependency_overrides[get_database] = database
    client = TestClient(app, headers={"Authorization": "Bearer " + create_access_token(ids[0])})
    try:
        yield client, ids, mysql_test_database
    finally:
        client.close()
        app.dependency_overrides.pop(get_database, None)


def set_day(monkeypatch, value):
    monkeypatch.setattr(checkin, "beijing_now", lambda: datetime.fromisoformat(value).replace(tzinfo=checkin.BEIJING))


@pytest.mark.mysql
def test_login_days_repeat_gap_week_and_year_boundaries(checkin_client, monkeypatch):
    client, ids, name = checkin_client
    dates = ["2026-12-27", "2026-12-28", "2026-12-29", "2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02", "2027-01-03", "2027-01-04"]
    for index, day in enumerate(dates):
        set_day(monkeypatch, day)
        result = client.post("/user/checkin")
        assert result.status_code == 200, result.text
        data = result.json()["data"]
        assert data["streak_days"] == index + 1
        assert data["week_count"] == (1 if index in (0, 8) else index)
        assert client.post("/user/checkin").json()["data"] == data
    set_day(monkeypatch, "2027-01-06")
    assert client.get("/user/checkin").json()["data"]["streak_days"] == 0
    data = client.post("/user/checkin").json()["data"]
    assert data["streak_days"] == 1
    assert data["week_count"] == 2
    connection = connect_database(database_name=name)
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT login_dates, last_login_date, login_streak FROM users WHERE id=%s", (ids[0],))
            row = cursor.fetchone()
        saved = json.loads(row["login_dates"])
        assert len(saved) == 7
        assert saved[0] == "2027-01-04"
        assert saved[1] == "2026-12-29"  # 上周残留无需清空，不计入本周。
        assert saved[2] == "2027-01-06"
        assert row["login_streak"] == 1
    finally:
        connection.close()


@pytest.mark.mysql
def test_identity_is_from_token_and_get_does_not_mark(checkin_client, monkeypatch):
    client, ids, _ = checkin_client
    set_day(monkeypatch, "2026-09-23")
    assert client.get("/user/checkin").json()["data"]["week_count"] == 0
    result = client.post(f"/user/checkin?user_id={ids[1]}&date=2030-01-01", json={"user_id": ids[1]})
    assert result.headers["cache-control"] == "no-store"
    assert result.json()["data"]["user_id"] == ids[0]
    assert result.json()["data"]["today"] == "2026-09-23"
    assert client.get("/user/checkin", headers={"Authorization": "Bearer " + create_access_token(ids[1])}).json()["data"]["week_count"] == 0
    assert client.post("/user/checkin", headers={"Authorization": "Bearer bad"}).status_code == 401
    anonymous = TestClient(app)
    try:
        assert anonymous.post("/user/checkin").status_code == 401
    finally:
        anonymous.close()


@pytest.mark.mysql
def test_concurrent_devices_only_add_one_day(checkin_client, monkeypatch):
    client, ids, name = checkin_client
    set_day(monkeypatch, "2026-09-22")
    assert client.post("/user/checkin").json()["data"]["streak_days"] == 1
    set_day(monkeypatch, "2026-09-23")
    barrier = Barrier(4)

    def mark(_):
        conn = connect_database(database_name=name)
        try:
            barrier.wait(timeout=10)
            return checkin.checkin_status(conn, ids[0], mark=True)
        finally:
            conn.close()

    with ThreadPoolExecutor(max_workers=4) as executor:
        results = list(executor.map(mark, range(4)))
    assert all(row.streak_days == 2 and row.week_count == 2 for row in results)


@pytest.mark.mysql
def test_upgrade_legacy_users_preserves_rows_and_checkins():
    name = "movie_vocab_test_" + uuid4().hex
    connection = connect_database(select_database=False)
    created = False
    try:
        with connection.cursor() as cursor:
            cursor.execute(f"CREATE DATABASE `{name}` CHARACTER SET utf8mb4")
            created = True
            connection.select_db(name)
            cursor.execute("""CREATE TABLE users (
                id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                account VARCHAR(15) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL,
                book_id INT NOT NULL DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
            cursor.execute("INSERT INTO users (account, password_hash, book_id) VALUES ('legacy', 'unchanged', 1)")
            user_id = cursor.lastrowid
        connection.commit()
        create_users_table(connection)
        create_users_table(connection)
        with connection.cursor() as cursor:
            cursor.execute("SELECT * FROM users WHERE id=%s", (user_id,))
            assert cursor.fetchone() == dict(id=user_id, account="legacy", password_hash="unchanged", book_id=1,
                                            login_dates=None, last_login_date=None, login_streak=0)
        checkin.checkin_status(connection, user_id, mark=True)
        create_users_table(connection)
        assert checkin.checkin_status(connection, user_id, mark=False).streak_days == 1
    finally:
        if created:
            connection.select_db("information_schema")
            with connection.cursor() as cursor:
                cursor.execute(f"DROP DATABASE `{name}`")
        connection.close()
