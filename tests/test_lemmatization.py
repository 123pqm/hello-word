from unittest.mock import MagicMock
import pytest
from app.services.lemmatization_service import lemmatize_words
from app.services.cet4_service import match_vocabulary
import app.tasks.movie_task as worker


@pytest.mark.parametrize('word,sentence,lemma', [
    ('worked','They worked yesterday.','work'), ('went','They went home.','go'),
    ('children','The children are playing.','child'), ('saw','I saw a bird.','see'),
    ('saw','He used a saw to cut wood.','saw'),
])
def test_real_model_preserves_source_and_time(word,sentence,lemma):
    source = {'word':word,'start':2,'end':3,'sentence_start':1,'sentence_end':4,'sentence_text':sentence}
    output = lemmatize_words([source])
    assert output[0]['lemma'] == lemma
    assert 'lemma' not in source
    assert all(output[0][key] == value for key,value in source.items())
    matched = match_vocabulary(output, {lemma:'meaning'})
    assert matched[0]['word'] == lemma
    assert matched[0]['start'] == 1 and matched[0]['end'] == 4


def test_no_context_and_selected_vocabulary_are_preserved():
    words=[{'word':'went','start':1,'end':2}]
    assert lemmatize_words(words) == words
    assert match_vocabulary([dict(words[0],lemma='go')], {'work':'工作'}) == []


@pytest.mark.parametrize('enabled',[False,True])
def test_worker_reads_persisted_option(monkeypatch,enabled):
    db=MagicMock(); cursor=db.cursor.return_value.__enter__.return_value
    cursor.fetchone.side_effect=[{'enable_lemmatization':int(enabled)},{'id':9}]
    words=[{'word':'worked','start':1,'end':2}]
    monkeypatch.setattr(worker,'connect_database',lambda:db)
    monkeypatch.setattr(worker,'transcribe_movie',lambda _:words)
    transform=MagicMock(return_value=[dict(words[0],lemma='work')])
    monkeypatch.setattr(worker,'lemmatize_words',transform)
    matcher=MagicMock(return_value=[])
    monkeypatch.setattr(worker,'match_cet4_words',matcher)
    worker.process_movie(9,'fake.mp4')
    assert transform.call_count == int(enabled)
    assert ('lemma' in matcher.call_args.args[0][0]) == enabled
    db.commit.assert_called_once()
