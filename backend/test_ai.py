import os

from dotenv import load_dotenv
from openai import OpenAI


load_dotenv()


api_key = os.getenv("SILICONFLOW_API_KEY")

model_name = os.getenv(
    "SILICONFLOW_MODEL",
    "XingChenAGI/Xing4.0-29B"
)


if not api_key:
    raise RuntimeError(
        "没有读取到 SILICONFLOW_API_KEY，请检查 .env"
    )


client = OpenAI(
    api_key=api_key,
    base_url="https://api.siliconflow.cn/v1",
    timeout=30.0
)

print("正在调用硅基流动 AI...")


response = client.chat.completions.create(
    model=model_name,
    messages=[
        {
            "role": "system",
            "content": "你是一名专业的英语老师。"
        },
        {
            "role": "user",
            "content": """
请解释下面这句话：

I can't figure out what he's getting at.

重点解释：
1. figure out 是什么意思
2. getting at 是什么意思
3. 整个句子是什么意思
"""
        }
    ],
    temperature=0.2,
    max_tokens=500
)


print("\nAI 返回结果：\n")

print(response.choices[0].message.content)