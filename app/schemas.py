"""接口返回数据的结构。"""

from pydantic import BaseModel, Field


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
