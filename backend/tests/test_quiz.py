import json
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from app.auth import get_current_user_id
from app.database import get_database
import app.quiz as quiz
import app.services.spelling_service as spelling


@pytest.fixture
def client(monkeypatch):
    app=FastAPI(); app.include_router(quiz.router)
    db=MagicMock(); cursor=db.cursor.return_value.__enter__.return_value
    app.dependency_overrides[get_database]=lambda:db
    app.dependency_overrides[get_current_user_id]=lambda:7
    monkeypatch.setattr(quiz,'quiz_distractors',lambda *a:[{'word':w,'text':w+'释义'} for w in ['simple','similarly','simile']])
    with TestClient(app) as c:yield c,db,cursor


def test_create_keeps_answer_and_english_on_server(client):
    c,db,cursor=client
    cursor.fetchone.return_value={'word':'similar','meaning':'相似的'}
    response=c.post('/video/12/words/3/question')
    assert response.status_code==200
    body=response.json()
    assert len(body['options'])==4
    assert all(set(option)=={'text'} for option in body['options'])
    assert 'correct_index' not in body
    assert cursor.execute.call_args_list[0].args[1]==(3,12,7)
    params=cursor.execute.call_args.args[1]
    options=json.loads(params[4]);assert options[params[5]]['word']=='similar'
    db.commit.assert_called_once()


@pytest.mark.parametrize('selected',[None,2])
def test_answer_and_retry_are_stable(client,selected):
    c,db,cursor=client
    options=[{'word':w,'text':w} for w in ['simple','similarly','similar','simile']]
    cursor.fetchone.return_value={'selected_index':selected,'correct_index':2,'options_json':json.dumps(options),
        'expires_at':datetime.now(timezone.utc).replace(tzinfo=None)+timedelta(minutes=10)}
    body=c.post('/video/questions/'+'a'*32+'/answer',json={'selected_index':1}).json()
    assert body['selected_index']==(1 if selected is None else 2)
    assert body['correct']==(selected is not None)
    assert body['options']==options
    assert cursor.execute.call_args_list[0].args[1]==('a'*32,7,7)
    assert cursor.execute.call_count==(2 if selected is None else 1)


def test_missing_expired_and_validation(client):
    c,db,cursor=client
    cursor.fetchone.return_value=None
    assert c.post('/video/12/words/3/question').status_code==404
    assert c.post('/video/questions/'+'a'*32+'/answer',json={'selected_index':0}).status_code==404
    cursor.fetchone.return_value={'selected_index':None,'expires_at':datetime(2020,1,1)}
    assert c.post('/video/questions/'+'a'*32+'/answer',json={'selected_index':0}).status_code==410
    for value in [-1,4,True,'1']:
        assert c.post('/video/questions/'+'a'*32+'/answer',json={'selected_index':value}).status_code==422


def test_auth_and_model_failure(client,monkeypatch):
    c,db,cursor=client
    cursor.fetchone.return_value={'word':'similar','meaning':'相似的'}
    def fail(*args):raise RuntimeError('missing model')
    monkeypatch.setattr(quiz,'quiz_distractors',fail)
    assert c.post('/video/12/words/3/question').status_code==503
    db.commit.assert_not_called()
    c.app.dependency_overrides.pop(get_current_user_id)
    assert c.post('/video/12/words/3/question').status_code==401


def test_distractors_remove_target_duplicates_and_empty_meanings(monkeypatch):
    model=MagicMock()
    model.recognize.return_value=[{'word':'similar','meaning':'相似的'}, {'word':'SIMPLE','meaning':'简单'},
        {'word':'simple','meaning':'别的释义'}, {'word':'blank','meaning':''},
        {'word':'simile','meaning':'比喻'}, {'word':'similarly','meaning':'类似地'}]
    monkeypatch.setattr(spelling,'_recognizer',lambda:model)
    assert [x['word'] for x in spelling.quiz_distractors('similar','相似的')]==['SIMPLE','simile','similarly']


@pytest.mark.mysql
def test_real_database_create_answer_retry_and_owner(mysql_test_database):
    from app.database import connect_database, create_movie_tables
    from fastapi import HTTPException
    db=connect_database(database_name=mysql_test_database)
    try:
        create_movie_tables(db)
        with db.cursor() as c:
            c.execute("INSERT INTO movies (user_id,status) VALUES (77,'completed')")
            movie_id=c.lastrowid
            c.execute("INSERT INTO movie_words (movie_id,word,meaning,start_time,end_time) VALUES (%s,'similar','相似的',1,3)",(movie_id,))
            word_id=c.lastrowid
        db.commit()
        question=quiz.create_question(movie_id,word_id,db,77)
        assert len(question['options'])==4
        with pytest.raises(HTTPException) as error:
            quiz.answer_question(question['id'],quiz.Answer(selected_index=0),db,88)
        assert error.value.status_code==404
        first=quiz.answer_question(question['id'],quiz.Answer(selected_index=0),db,77)
        retry=quiz.answer_question(question['id'],quiz.Answer(selected_index=3),db,77)
        assert first==retry
        assert len({x['word'] for x in first['options']})==4
        assert first['options'][first['correct_index']]['word']=='similar'
    finally: db.close()


def test_word_pagination_bounds_and_owner(client):
    c, db, cursor = client
    cursor.fetchone.side_effect = [{'id': 12}, {'total': 20}]
    cursor.fetchall.return_value = [{'id': 6, 'word': 'similar'}]
    response = c.get('/video/12/quiz-words?offset=5&limit=5')
    assert response.status_code == 200
    assert response.json()['total'] == 20
    assert cursor.execute.call_args_list[0].args[1] == (12, 7)
    assert cursor.execute.call_args.args[1] == (12, 5, 5)
    assert c.get('/video/12/quiz-words?limit=6').status_code == 422
    assert c.get('/video/12/quiz-words?offset=-1').status_code == 422
    cursor.fetchone.side_effect = [None]
    assert c.get('/video/12/quiz-words').status_code == 404
