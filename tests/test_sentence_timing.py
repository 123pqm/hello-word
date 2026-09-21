from unittest.mock import MagicMock

from app.services.sentence_timing import words_with_sentence_times
from app.services.cet4_service import match_cet4_words
import app.tasks.movie_task as movie_task


def segment(*tokens):
    return {"words": [{"word": word, "start": start, "end": end} for word, start, end in tokens]}


def test_sentences_cross_segments_and_split_inside_segment():
    words = words_with_sentence_times([
        segment(("It", 1, 1.2), ("is", 1.3, 1.4)),
        segment(("beautiful.", 1.5, 2), ("Hello", 2.2, 2.5), ('world!"', 2.6, 3)),
    ])
    assert [word["word"] for word in words] == ["it", "is", "beautiful", "hello", "world"]
    assert [(word["sentence_start"], word["sentence_end"]) for word in words] == [
        (1, 2), (1, 2), (1, 2), (2.2, 3), (2.2, 3),
    ]
    assert words[2]["start"] == 1.5
    assert [word["sentence_text"] for word in words] == [
        "It is beautiful.", "It is beautiful.", "It is beautiful.", 'Hello world!"', 'Hello world!"',
    ]


def test_abbreviation_separate_punctuation_and_unpunctuated_tail():
    words = words_with_sentence_times([segment(
        ("Dr.", 0, 0.2), ("Smith", 0.3, 0.5), ("left", 0.6, 1),
        (".", 1, 1), ("Goodbye", 1.1, 1.5),
    )])
    assert [(word["sentence_start"], word["sentence_end"]) for word in words] == [
        (0, 1), (0, 1), (0, 1), (1.1, 1.5),
    ]
    assert words_with_sentence_times([]) == []
    assert words_with_sentence_times([segment((".", 0, 0))]) == []
    assert words[0]["sentence_text"] == "Dr. Smith left."
    assert words[-1]["sentence_text"] == "Goodbye"


def test_sentence_preserves_numbers_case_contractions_and_punctuation():
    words = words_with_sentence_times([segment(
        (" It", 0, 0.2), ("'s", 0.2, 0.3), (" beautiful", 0.3, 0.8),
        (",", 0.8, 0.8), (" in", 0.9, 1), (" 2026", 1.1, 2), ("!", 2, 2),
    )])
    assert all(word["sentence_text"] == "It's beautiful, in 2026!" for word in words)
    assert all(word["sentence_end"] == 2 for word in words)


def test_missing_punctuation_splits_at_silence_and_length_limit():
    words = words_with_sentence_times([segment(
        ("one", 0, 1), ("two", 3, 4), ("three", 4.5, 5),
        *[(f"word", t, t + 1) for t in range(6, 36)],
    )])
    assert words[0]["sentence_end"] == 1
    assert words[0]["sentence_text"] == "one"
    assert words[1]["sentence_text"].startswith("two three ")
    assert words[-1]["sentence_text"] == "word word word"
    assert words[1]["sentence_start"] == 3
    assert words[-1]["sentence_start"] == 33
    assert all(word["sentence_end"] - word["sentence_start"] <= 30 for word in words)


def test_first_occurrence_is_saved_with_whole_sentence_range(monkeypatch):
    words = words_with_sentence_times([
        segment(("A", 1, 1.1), ("beautiful", 1.2, 1.6), ("world.", 1.7, 2)),
        segment(("beautiful.", 5, 6)),
    ])
    database = MagicMock()
    cursor = database.cursor.return_value.__enter__.return_value
    cursor.fetchall.return_value = [{"id": 7, "word": "beautiful", "meaning": "美丽的"}]
    matched = match_cet4_words(list(reversed(words)), database, [7])
    assert matched == [{"word": "beautiful", "meaning": "美丽的", "start": 1, "end": 2,
                        "sentence_text": "A beautiful world."}]
    monkeypatch.setattr(movie_task, "transcribe_movie", lambda _: words)
    monkeypatch.setattr(movie_task, "connect_database", lambda: database)
    movie_task.process_movie(17, "demo.mp4", [7])
    assert cursor.executemany.call_args.args[1] == [(17, "beautiful", "美丽的", 1, 2, "A beautiful world.")]
    database.commit.assert_called_once()
