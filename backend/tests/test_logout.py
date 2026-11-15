import pytest
from fastapi import FastAPI, Depends
from fastapi.testclient import TestClient
from fastapi.security import HTTPAuthorizationCredentials
from app.auth import get_current_user_id, token_fingerprint
from app.logout import router
from app.database import get_database
from app.main import create_access_token

class Database:
    def __init__(self): self.revoked = set(); self.result = None
    def cursor(self): return self
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def execute(self, sql, args=()):
        if sql.startswith('SELECT id FROM users'):
            self.result = {'id': args[0]} if args[1] not in self.revoked else None
        elif sql.startswith('INSERT IGNORE'): self.revoked.add(args[0])
    def fetchone(self): return self.result
    def commit(self): pass
    def rollback(self): pass

@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv('JWT_SECRET_KEY', 'logout-test-key-' * 4)
    db = Database()
    app = FastAPI(); app.include_router(router)
    @app.get('/private')
    def private(user=Depends(get_current_user_id)): return {'id': user}
    app.dependency_overrides[get_database] = lambda: db
    with TestClient(app) as client: yield client

def test_logout_revokes_only_current_login(client):
    first, second = create_access_token(12), create_access_token(12)
    assert first != second
    headers = {'Authorization': 'Bearer ' + first}
    assert client.get('/private', headers=headers).status_code == 200
    response = client.post('/user/logout', headers=headers)
    assert response.status_code == 204 and response.content == b''
    assert client.get('/private', headers=headers).status_code == 401
    assert client.post('/user/logout', headers=headers).status_code == 401
    assert client.get('/private', headers={'Authorization': 'Bearer ' + second}).status_code == 200

def test_logout_requires_valid_token(client):
    assert client.post('/user/logout').status_code == 401
    assert client.post('/user/logout', headers={'Authorization': 'Bearer invalid'}).status_code == 401

def test_digest_covers_signed_content():
    assert token_fingerprint('header.payload.signature') == token_fingerprint('header.payload.alternate')
    assert token_fingerprint('header.payload.signature') != token_fingerprint('header.other.signature')

@pytest.mark.mysql
def test_revocation_survives_new_database_connection(mysql_test_database):
    from uuid import uuid4
    from fastapi import HTTPException
    from app.database import connect_database, create_users_table
    from app.logout import logout
    with connect_database(database_name=mysql_test_database) as db:
        create_users_table(db)
        with db.cursor() as c:
            c.execute("INSERT INTO users (account, password_hash) VALUES (%s, 'unused')", ('o' + uuid4().hex[:12],))
            user_id = c.lastrowid
        db.commit()
        credentials = HTTPAuthorizationCredentials(scheme='Bearer', credentials=create_access_token(user_id))
        assert get_current_user_id(credentials, db) == user_id
        assert logout(user_id, credentials, db).status_code == 204
    with connect_database(database_name=mysql_test_database) as db:
        with pytest.raises(HTTPException) as exc:
            get_current_user_id(credentials, db)
        assert exc.value.status_code == 401
