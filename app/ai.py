"""AI 讲解接口。"""

import json

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse

from app.schemas import ChatRequest, ExplainWordRequest, ExplainWordResponse
from app.services.ai_service import AIServiceError, explain_word, get_ai_config, stream_chat


router = APIRouter(prefix="/ai", tags=["AI 讲解"])


@router.post("/explain-word", response_model=ExplainWordResponse)
async def explain_word_endpoint(payload: ExplainWordRequest) -> ExplainWordResponse:
    try:
        return await explain_word(payload)
    except AIServiceError as error:
        raise HTTPException(status_code=error.status_code, detail=error.detail) from error


@router.post("/chat/stream")
async def chat_stream_endpoint(payload: ChatRequest):
    try:
        config = get_ai_config()
    except AIServiceError as error:
        raise HTTPException(status_code=error.status_code, detail=error.detail) from error

    async def events():
        yield ': connected\n\n'
        async for event in stream_chat(payload, config):
            yield "data: " + json.dumps(event, ensure_ascii=False) + "\n\n"

    return StreamingResponse(events(), media_type="text/event-stream", headers={
        "Cache-Control": "no-cache", "X-Accel-Buffering": "no",
    })
