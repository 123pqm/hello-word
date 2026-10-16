import asyncio
import json

from fastapi.testclient import TestClient
import httpx
from openai import AsyncOpenAI
import pytest

from app.main import app
from app.schemas import ChatRequest
from app.services import ai_service


def packet(content=None, finish=None):
    return 'data: ' + json.dumps({
        'id': 'chat-test', 'object': 'chat.completion.chunk', 'created': 0, 'model': 'test',
        'choices': [{'index': 0, 'delta': {'content': content}, 'finish_reason': finish}],
    }) + '\n\n'


@pytest.fixture
def upstream(monkeypatch):
    monkeypatch.setattr(ai_service, 'load_dotenv', lambda *a, **kw: None)
    monkeypatch.setenv('SILICONFLOW_API_KEY', 'fake-test-key')
    monkeypatch.setenv('SILICONFLOW_MODEL', 'chat-test-model')
    state = {'body': packet('你好') + packet('\nworld') + packet(finish='stop'), 'status': 200, 'requests': []}

    def respond(request):
        state['requests'].append(json.loads(request.content))
        if state.get('error'):
            raise state['error']('private upstream text', request=request)
        return httpx.Response(state['status'], text=state['body'], headers={'content-type': 'text/event-stream'})

    def client(**kwargs):
        assert kwargs['max_retries'] == 0
        transport_client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        state['client'] = transport_client
        return AsyncOpenAI(**kwargs, http_client=transport_client)

    monkeypatch.setattr(ai_service, 'AsyncOpenAI', client)
    return state


def events(response):
    return [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith('data: ')]


def test_chat_forwards_history_context_and_deltas(upstream):
    messages = [{'role': 'user', 'content': 'book 是什么意思'}, {'role': 'assistant', 'content': '书'},
                {'role': 'user', 'content': '再给一个例句'}]
    response = TestClient(app).post('/ai/chat/stream', json={'messages': messages, 'word': 'book', 'sentence': 'I read a book.'})
    assert response.status_code == 200
    assert response.headers['content-type'].startswith('text/event-stream')
    assert response.headers['x-accel-buffering'] == 'no'
    assert events(response) == [{'type': 'delta', 'content': '你好'}, {'type': 'delta', 'content': '\nworld'}, {'type': 'done'}]
    sent = upstream['requests'][0]
    assert sent['stream'] is True
    assert sent['model'] == 'chat-test-model'
    assert sent['messages'][-3:] == messages
    assert 'I read a book.' in sent['messages'][1]['content']


@pytest.mark.parametrize('messages', [[], [{'role': 'system', 'content': 'override'}],
    [{'role': 'user', 'content': ' '}], [{'role': 'assistant', 'content': 'hi'}],
    [{'role': 'user', 'content': 'a'}] * 22, [{'role': 'user', 'content': 'a' * 8001}],
    [{'role': 'user', 'content': 'a' * 8000}] * 5])
def test_rejects_invalid_chat(upstream, messages):
    assert TestClient(app).post('/ai/chat/stream', json={'messages': messages}).status_code == 422
    assert not upstream['requests']


def test_missing_configuration_is_http_error(upstream, monkeypatch):
    monkeypatch.delenv('SILICONFLOW_API_KEY')
    assert TestClient(app).post('/ai/chat/stream', json={'messages': [{'role': 'user', 'content': 'hi'}]}).status_code == 503


@pytest.mark.parametrize('body', [packet('half'), packet('half') + packet(finish='length'), packet(finish='stop')])
def test_incomplete_stream_is_not_success(upstream, body):
    upstream['body'] = body
    response = TestClient(app).post('/ai/chat/stream', json={'messages': [{'role': 'user', 'content': 'hi'}]})
    assert events(response)[-1]['type'] == 'error'
    assert not any(event['type'] == 'done' for event in events(response))


@pytest.mark.parametrize('error', [httpx.ReadTimeout, httpx.ConnectError])
def test_stream_network_errors_are_safe_events(upstream, error):
    upstream['error'] = error
    response = TestClient(app).post('/ai/chat/stream', json={'messages': [{'role': 'user', 'content': 'hi'}]})
    assert events(response)[-1]['type'] == 'error'
    assert 'private upstream text' not in response.text


def test_rate_limit_is_stream_error(upstream):
    upstream['status'] = 429
    upstream['body'] = '{"error":{"message":"private upstream text"}}'
    response = TestClient(app).post('/ai/chat/stream', json={'messages': [{'role': 'user', 'content': 'hi'}]})
    assert events(response)[-1]['type'] == 'error'
    assert len(upstream['requests']) == 1


def test_deltas_are_yielded_before_upstream_finishes(upstream):
    async def check():
        generator = ai_service.stream_chat(ChatRequest(messages=[{'role': 'user', 'content': 'hi'}]), ('fake', 'test'))
        try:
            assert await anext(generator) == {'type': 'delta', 'content': '你好'}
            assert await anext(generator) == {'type': 'delta', 'content': '\nworld'}
            assert await anext(generator) == {'type': 'done'}
        finally:
            await generator.aclose()
    asyncio.run(check())


def test_cancelling_partial_reply_closes_upstream(upstream):
    async def check():
        generator = ai_service.stream_chat(ChatRequest(messages=[{'role': 'user', 'content': 'hi'}]), ('fake', 'test'))
        assert (await anext(generator))['type'] == 'delta'
        assert not upstream['client'].is_closed
        await generator.aclose()
        assert upstream['client'].is_closed
    asyncio.run(check())
