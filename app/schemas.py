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
    account:str
    reply:str

class account_login(BaseModel):
    account:str=Field(min_length=5, max_length=15)
    password:str=Field(min_length=5, max_length=15)