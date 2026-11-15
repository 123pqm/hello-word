"""模型生成相似拼写选项，数据库保存答案，提交后揭示英文。"""
import json
import logging
import secrets
from datetime import datetime, timedelta, timezone
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Path, Query
from pydantic import BaseModel, Field
from pymysql.connections import Connection
from app.auth import get_current_user_id
from app.database import get_database
from app.services.spelling_service import quiz_distractors

router = APIRouter(prefix='/video', tags=['闪卡答题'])


@router.get('/{movie_id}/quiz-words')
def quiz_words(movie_id: Annotated[int, Path(gt=0)],
               database: Annotated[Connection, Depends(get_database)],
               user_id: Annotated[int, Depends(get_current_user_id)],
               offset: Annotated[int, Query(ge=0)] = 0,
               limit: Annotated[int, Query(ge=1, le=5)] = 5):
    with database.cursor() as cursor:
        cursor.execute("SELECT id FROM movies WHERE id=%s AND user_id=%s AND status='completed'", (movie_id, user_id))
        if cursor.fetchone() is None:
            raise HTTPException(404, '视频不存在或尚未处理完成')
        cursor.execute('SELECT COUNT(*) AS total FROM movie_words WHERE movie_id=%s', (movie_id,))
        total = cursor.fetchone()['total']
        cursor.execute('SELECT id, word, meaning, start_time, end_time, sentence_text FROM movie_words WHERE movie_id=%s ORDER BY id LIMIT %s OFFSET %s', (movie_id, limit, offset))
        words = cursor.fetchall()
    return {'words': words, 'total': total}


@router.post('/{movie_id}/words/{word_id}/question')
def create_question(movie_id: Annotated[int, Path(gt=0)], word_id: Annotated[int, Path(gt=0)],
                    database: Annotated[Connection, Depends(get_database)],
                    user_id: Annotated[int, Depends(get_current_user_id)]):
    with database.cursor() as cursor:
        cursor.execute('''SELECT w.word, w.meaning FROM movie_words w
            JOIN movies m ON m.id=w.movie_id
            WHERE w.id=%s AND m.id=%s AND m.user_id=%s AND m.status='completed' ''',
                       (word_id, movie_id, user_id))
        word = cursor.fetchone()
    if word is None:
        raise HTTPException(404, '闪卡不存在或无权访问')
    if not word['meaning'] or not word['meaning'].strip():
        raise HTTPException(409, '该单词没有释义，暂时无法出题')
    try:
        options = [{'word': word['word'], 'text': word['meaning']}] + quiz_distractors(word['word'], word['meaning'])
    except Exception:
        logging.getLogger(__name__).exception('生成相似词选项失败')
        raise HTTPException(503, '暂时无法生成选项，请重试或跳过本题') from None
    secrets.SystemRandom().shuffle(options)
    correct = next(i for i, item in enumerate(options) if item['word'] == word['word'])
    question_id = uuid4().hex
    try:
        with database.cursor() as cursor:
            cursor.execute('''INSERT INTO flashcard_questions
                (id,user_id,movie_id,word_id,options_json,correct_index,expires_at)
                VALUES (%s,%s,%s,%s,%s,%s,%s)''',
                (question_id,user_id,movie_id,word_id,json.dumps(options,ensure_ascii=False),correct,
                 datetime.now(timezone.utc).replace(tzinfo=None)+timedelta(minutes=30)))
        database.commit()
    except Exception:
        database.rollback()
        raise HTTPException(503, '题目保存失败，请重试') from None
    return {'id':question_id, 'word':word['word'], 'options':[{'text':x['text']} for x in options]}


class Answer(BaseModel):
    selected_index: int = Field(ge=0,le=3,strict=True)


@router.post('/questions/{question_id}/answer')
def answer_question(question_id: Annotated[str, Path(pattern=r'^[a-f0-9]{32}$')], body: Answer,
                    database: Annotated[Connection, Depends(get_database)],
                    user_id: Annotated[int, Depends(get_current_user_id)]):
    try:
        with database.cursor() as cursor:
            cursor.execute('''SELECT q.* FROM flashcard_questions q JOIN movies m ON m.id=q.movie_id
                WHERE q.id=%s AND q.user_id=%s AND m.user_id=%s FOR UPDATE''',
                           (question_id,user_id,user_id))
            question = cursor.fetchone()
            if question is None:
                raise HTTPException(404,'题目不存在或无权访问')
            if question['selected_index'] is None and question['expires_at'] <= datetime.now(timezone.utc).replace(tzinfo=None):
                raise HTTPException(410,'题目已过期，请重新加载')
            selected = question['selected_index']
            if selected is None:
                selected = body.selected_index
                cursor.execute('UPDATE flashcard_questions SET selected_index=%s WHERE id=%s', (selected,question_id))
            options = json.loads(question['options_json'])
        database.commit()
        return {'selected_index':selected, 'correct_index':question['correct_index'],
                'correct':selected == question['correct_index'], 'options':options}
    except HTTPException:
        database.rollback()
        raise
    except Exception:
        database.rollback()
        raise HTTPException(503,'答案提交失败，请重试') from None
