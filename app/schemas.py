"""接口返回数据的结构。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class WordResponse(BaseModel):
    id: int
    word: str
    meaning: str


class WordListResponse(BaseModel):
    items: list[WordResponse]
    total: int





class WordCreate(BaseModel):
    word: str = Field(min_length=1, max_length=50)
    meaning: str = Field(min_length=1, max_length=200)

class account_resign(BaseModel):
    account:str=Field(min_length=5, max_length=15)
    password:str=Field(min_length=5, max_length=15)

class resign_response(BaseModel):
    id:int
    reply:str

class login_response(BaseModel):
    user_id: int
    account: str
    reply: str
    access_token: str
    token_type: str = "bearer"
    expires_in: int

class account_login(BaseModel):
    account:str=Field(min_length=5, max_length=15)
    password:str=Field(min_length=5, max_length=15)

class chose_book_response(BaseModel):
   reply:str

class book_detail(BaseModel):
    id:int
    book_id:int


class words(BaseModel):
     word:str
     meanig:str
     pos:str


class Cet4WordResponse(BaseModel):
    id: int
    word: str
    meaning: str
    pos: str | None = None


class cet_4_response(BaseModel):
    data: list[Cet4WordResponse]


class WordbookWordResponse(Cet4WordResponse):
    frequency: int | None = None


class WordbookWordListResponse(BaseModel):
    data: list[WordbookWordResponse]
    total: int
    book: dict


class ExplainWordRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    word: str = Field(min_length=1, max_length=100)
    sentence: str = Field(min_length=1, max_length=4000)
    previous_sentence: str = Field(default="", max_length=4000)
    next_sentence: str = Field(default="", max_length=4000)


class ExplainWordResponse(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True, strict=True)

    word: str = Field(min_length=1)
    basic_meaning: str = Field(min_length=1)
    context_meaning: str = Field(min_length=1)
    sentence_translation: str = Field(min_length=1)
    part_of_speech: str = Field(min_length=1)
    usage: str = Field(min_length=1)
    collocations: list[str]
    example: str = Field(min_length=1)
    example_translation: str = Field(min_length=1)


class ChatMessage(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=8000)


class ChatRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    messages: list[ChatMessage] = Field(min_length=1, max_length=21)
    word: str = Field(default="", max_length=100)
    sentence: str = Field(default="", max_length=4000)

    @model_validator(mode="after")
    def validate_conversation(self):
        if self.messages[-1].role != "user":
            raise ValueError("最后一条消息必须是用户问题")
        if sum(len(message.content) for message in self.messages) > 32000:
            raise ValueError("对话内容过长，请开始新对话")
        return self
