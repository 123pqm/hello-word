"""Wrong-word review: server-verified answers, calendar scheduling and idempotent updates."""
import json
import secrets
from datetime import datetime, timedelta, timezone
from typing import Annotated
from uuid import uuid4
from fastapi import APIRouter, Depends, HTTPException, Query, Path
from pymysql.connections import Connection
from app.auth import get_current_user_id
from app.database import get_database
from app.quiz import Answer
from app.services.spelling_service import quiz_distractors

router = APIRouter(prefix='/reviews', tags=['错题复习'])
DB = Annotated[Connection, Depends(get_database)]
User = Annotated[int, Depends(get_current_user_id)]
QuestionID = Annotated[str, Path(pattern=r'^[a-f0-9]{32}$')]

def today():
    return datetime.now(timezone(timedelta(hours=8))).date()

def utcnow():
    return datetime.now(timezone.utc).replace(tzinfo=None)

@router.post('/wrong/{question_id}')
def add_wrong(question_id: QuestionID, database: DB, user_id: User):
    try:
        with database.cursor() as c:
            c.execute('''SELECT q.*, w.word, w.meaning, m.book_id FROM flashcard_questions q
                JOIN movies m ON m.id=q.movie_id JOIN movie_words w ON w.id=q.word_id AND w.movie_id=m.id
                WHERE q.id=%s AND q.user_id=%s AND m.user_id=%s FOR UPDATE''', (question_id,user_id,user_id))
            q=c.fetchone()
            if not q: raise HTTPException(404, '题目不存在')
            if q['selected_index'] is None or q['selected_index']==q['correct_index']:
                raise HTTPException(409, '只有已提交的错题可以加入复习')
            c.execute('SELECT question_id FROM review_add_events WHERE question_id=%s', (question_id,))
            if c.fetchone():
                database.commit()
                return {'saved':True}
            # Older movies lack book_id. Resolve a stable dictionary ID, preferring CET4.
            book=q['book_id']
            word=None
            if book is None or book==1:
                c.execute('SELECT id, 1 AS book_id FROM cet4 WHERE LOWER(word)=LOWER(%s) ORDER BY id LIMIT 1',(q['word'],))
                word=c.fetchone()
            if word is None:
                sql='SELECT id, book_id FROM wordbook_words WHERE LOWER(word)=LOWER(%s)'
                args=[q['word']]
                if book is not None:
                    sql+=' AND book_id=%s'; args.append(book)
                c.execute(sql+' ORDER BY book_id,id LIMIT 1',tuple(args)); word=c.fetchone()
            if not word: raise HTTPException(409,'无法匹配来源词书，请重新处理该视频')
            c.execute('''INSERT INTO wrong_word_reviews
                (user_id,wordbook_id,word_id,word,meaning,wrong_at,next_review_at)
                VALUES (%s,%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE id=id''',
                (user_id,word['book_id'],word['id'],q['word'],q['meaning'],utcnow(),today()+timedelta(days=1)))
            c.execute('INSERT INTO review_add_events (question_id,user_id) VALUES (%s,%s)',(question_id,user_id))
        database.commit()
        return {'saved':True}
    except Exception:
        database.rollback(); raise

@router.get('')
def list_reviews(database: DB,user_id: User,after_id: Annotated[int,Query(ge=0)]=0,
                 limit: Annotated[int,Query(ge=1,le=50)]=20,due_only: bool=False):
    with database.cursor() as c:
        c.execute('SELECT COUNT(*) AS total, COALESCE(SUM(next_review_at<=%s),0) AS due FROM wrong_word_reviews WHERE user_id=%s',(today(),user_id))
        counts=c.fetchone()
        condition=' AND next_review_at<=%s' if due_only else ''
        args=[user_id,after_id]+([today()] if due_only else [])+[limit]
        c.execute('SELECT *, 0 AS start_time, 0 AS end_time FROM wrong_word_reviews WHERE user_id=%s AND id>%s'+condition+' ORDER BY id LIMIT %s',tuple(args))
        words=c.fetchall()
    return {'words':words,'total':int(counts['due'] if due_only else counts['total']), 'due':int(counts['due'])}

@router.post('/{review_id}/question')
def create_review_question(review_id: Annotated[int,Path(gt=0)],database: DB,user_id: User):
    with database.cursor() as c:
        c.execute('SELECT * FROM wrong_word_reviews WHERE id=%s AND user_id=%s AND next_review_at<=%s',(review_id,user_id,today()))
        r=c.fetchone()
    if not r: raise HTTPException(404,'该单词未到复习时间或已完成复习')
    try:
        options=[{'word':r['word'],'text':r['meaning']}]+quiz_distractors(r['word'],r['meaning'])
    except Exception:
        raise HTTPException(503,'暂时无法出题，请重试') from None
    secrets.SystemRandom().shuffle(options)
    correct=next(i for i,x in enumerate(options) if x['word']==r['word'])
    qid=uuid4().hex
    try:
        with database.cursor() as c:
            c.execute('''INSERT INTO review_questions (id,user_id,review_id,review_count,scheduled_date,options_json,correct_index,expires_at)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s)''',(qid,user_id,review_id,r['review_count'],r['next_review_at'],json.dumps(options,ensure_ascii=False),correct,utcnow()+timedelta(minutes=30)))
        database.commit()
    except Exception:
        database.rollback(); raise
    return {'id':qid,'word':r['word'],'options':[{'text':x['text']} for x in options]}

@router.post('/questions/{question_id}/answer')
def answer_review(question_id: QuestionID,body: Answer,database: DB,user_id: User):
    try:
        with database.cursor() as c:
            c.execute('SELECT * FROM review_questions WHERE id=%s AND user_id=%s FOR UPDATE',(question_id,user_id))
            q=c.fetchone()
            if not q: raise HTTPException(404,'题目不存在')
            selected=q['selected_index']
            if selected is None:
                if q['expires_at']<=utcnow(): raise HTTPException(410,'题目已过期，请重试')
                c.execute('SELECT * FROM wrong_word_reviews WHERE id=%s AND user_id=%s FOR UPDATE',(q['review_id'],user_id))
                r=c.fetchone()
                if not r or r['review_count']!=q['review_count'] or r['next_review_at']!=q['scheduled_date'] or r['next_review_at']>today():
                    raise HTTPException(409,'该复习任务已经更新，请返回刷新')
                selected=body.selected_index
                count=r['review_count']+(selected==q['correct_index'])
                if count>=3:
                    c.execute('DELETE FROM wrong_word_reviews WHERE id=%s AND user_id=%s',(r['id'],user_id))
                else:
                    days=(3 if count==1 else 7) if selected==q['correct_index'] else 1
                    c.execute('UPDATE wrong_word_reviews SET review_count=%s,next_review_at=%s WHERE id=%s AND user_id=%s',
                              (count,today()+timedelta(days=days),r['id'],user_id))
                c.execute('UPDATE review_questions SET selected_index=%s WHERE id=%s',(selected,question_id))
        database.commit()
        return {'selected_index':selected,'correct_index':q['correct_index'],'correct':selected==q['correct_index'],'options':json.loads(q['options_json'])}
    except Exception:
        database.rollback(); raise
