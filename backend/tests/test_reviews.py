from datetime import timedelta
import pytest
from fastapi import HTTPException
from app import reviews, quiz
from app.database import connect_database, create_movie_tables, create_cet4, create_wordbook_tables

@pytest.mark.mysql
def test_review_lifecycle(mysql_test_database, monkeypatch):
    db=connect_database(database_name=mysql_test_database)
    monkeypatch.setattr(reviews, 'quiz_distractors', lambda *args: [{'word':w,'text':w} for w in ['simple','simile','similarly']])
    monkeypatch.setattr(quiz, 'quiz_distractors', lambda *args: [{'word':w,'text':w} for w in ['simple','simile','similarly']])
    try:
        create_movie_tables(db); create_cet4(db); create_wordbook_tables(db)
        with db.cursor() as c:
            c.execute("INSERT INTO cet4 (word,pos,meaning) VALUES ('similar','adj','相似的')")
            c.execute("INSERT INTO movies (user_id,status,book_id) VALUES (77,'completed',1)")
            movie=c.lastrowid
            c.execute("INSERT INTO movie_words (movie_id,word,meaning,start_time,end_time) VALUES (%s,'similar','相似的',1,3)",(movie,))
            word=c.lastrowid
        db.commit()
        q=quiz.create_question(movie,word,db,77)
        with db.cursor() as c:
            c.execute('SELECT correct_index FROM flashcard_questions WHERE id=%s',(q['id'],)); correct=c.fetchone()['correct_index']
        quiz.answer_question(q['id'],quiz.Answer(selected_index=(correct+1)%4),db,77)
        with pytest.raises(HTTPException): reviews.add_wrong(q['id'],db,88)
        reviews.add_wrong(q['id'],db,77); reviews.add_wrong(q['id'],db,77)
        listing=reviews.list_reviews(db,77,0,20,False)
        assert listing['total']==1 and listing['due']==0
        r=listing['words'][0]; rid=r['id']
        assert r['next_review_at']==reviews.today()+timedelta(days=1)
        with pytest.raises(HTTPException): reviews.create_review_question(rid,db,77)
        # Existing wrong word never resets the plan, even from a different quiz question.
        other=quiz.create_question(movie,word,db,77)
        with db.cursor() as c:
            c.execute('SELECT correct_index FROM flashcard_questions WHERE id=%s',(other['id'],)); correct=c.fetchone()['correct_index']
        quiz.answer_question(other['id'],quiz.Answer(selected_index=(correct+1)%4),db,77)
        reviews.add_wrong(other['id'],db,77)
        assert reviews.list_reviews(db,77,0,20,False)['words'][0]==r
        # A wrong review keeps the count unchanged and postpones until tomorrow.
        with db.cursor() as c:
            c.execute('UPDATE wrong_word_reviews SET next_review_at=%s WHERE id=%s',(reviews.today(),rid))
        db.commit()
        wrong_question=reviews.create_review_question(rid,db,77)
        with db.cursor() as c:
            c.execute('SELECT correct_index FROM review_questions WHERE id=%s',(wrong_question['id'],)); correct=c.fetchone()['correct_index']
        reviews.answer_review(wrong_question['id'],quiz.Answer(selected_index=(correct+1)%4),db,77)
        r=reviews.list_reviews(db,77,0,20,False)['words'][0]
        assert r['review_count']==0 and r['next_review_at']==reviews.today()+timedelta(days=1)
        for count in range(3):
            with db.cursor() as c:
                c.execute('UPDATE wrong_word_reviews SET next_review_at=%s WHERE id=%s',(reviews.today(),rid))
            db.commit()
            question=reviews.create_review_question(rid,db,77)
            stale=reviews.create_review_question(rid,db,77)
            with db.cursor() as c:
                c.execute('SELECT correct_index FROM review_questions WHERE id=%s',(question['id'],)); correct=c.fetchone()['correct_index']
            with pytest.raises(HTTPException): reviews.answer_review(question['id'],quiz.Answer(selected_index=correct),db,88)
            result=reviews.answer_review(question['id'],quiz.Answer(selected_index=correct),db,77)
            assert result==reviews.answer_review(question['id'],quiz.Answer(selected_index=(correct+1)%4),db,77)
            with pytest.raises(HTTPException): reviews.answer_review(stale['id'],quiz.Answer(selected_index=0),db,77)
            rows=reviews.list_reviews(db,77,0,20,False)['words']
            if count<2:
                assert rows[0]['review_count']==count+1
                assert rows[0]['next_review_at']==reviews.today()+timedelta(days=3 if count==0 else 7)
            else: assert not rows
        # Retrying the original add cannot resurrect a completed record.
        reviews.add_wrong(q['id'],db,77)
        assert reviews.list_reviews(db,77,0,20,False)['total']==0
    finally: db.close()

