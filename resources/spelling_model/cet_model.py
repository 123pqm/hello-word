# -*- coding: utf-8 -*-
"""
CET 词汇离线识别模型 —— 单文件完整版
======================================

从你 test.py 里的神经网络思路（nn.Module + 全连接层 + ReLU + CrossEntropyLoss）
演进而来：把「输入 28x28 图片」换成「输入一个英文单词的字符序列」，模型学会识别
该单词属于词库里的哪一个，从而给出中文释义、音标、词性、例句。离线可用，并带
拼写容错（打错一两个字母也能识别）。

一个文件干完所有事：
    python cet_model.py train                     # 训练并保存模型
    python cet_model.py recognize --word abandon  # 识别一个单词
    python cet_model.py interactive               # 交互连续识别

数据文件：
    data/vocab.json           词库（word / us / uk / translations / sentences）
    data/cet-word-list.txt    其纯文本形式（词/美音/英音/中文释义），与上面同源

只依赖 torch、numpy。运行前请确认用装有 torch 的解释器
（本机为 D:\\Deeplystudy\\envs\\pytorch\\python.exe）。
"""
import argparse
import json
import random
import string
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader

# ============================ 超参数（可调） ============================
BASE = Path(__file__).resolve().parent          # 本文件所在目录（工作区根）
VOCAB_JSON = BASE / "data" / "vocab.json"        # 词库（完整知识）
WORD_LIST_TXT = BASE / "data" / "cet-word-list.txt"
MODEL_PT = BASE / "checkpoints" / "model.pt"     # 训练好的权重
META_JSON = BASE / "checkpoints" / "meta.json"   # 字符表、词表顺序、最大长度

EMBED_DIM = 64        # 字符嵌入维度
CONV_FILTERS = 256    # 每种一维卷积核的通道数
FC_HIDDEN = 512       # 全连接层 1 的宽度
FC_EMBED = 256        # 词向量维度（全连接层 2 的输出）
DROPOUT = 0.3         # 防止过拟合的随机失活比例
BATCH = 512           # 每批训练样本数
EPOCHS = 200          # 训练轮数
LR = 2e-3             # 学习率
AUG_PROB = 0.5        # 分类输入被「制造错字」的概率
NCE_TEMP = 0.07       # 对比损失的温度（越小越严格）
NCE_WEIGHT = 1.0      # 对比损失在总损失里的权重


# ============================ 1. 读取词库 ============================
def load_vocab():
    """读取 data/vocab.json，返回 [{"word":.., "us":.., "uk":.., "translations":[..], "sentences":[..]}, ...]"""
    with open(VOCAB_JSON, encoding="utf-8") as f:
        return json.load(f)


def word_meaning(entry):
    """把一个词条的 translations 拼成一行中文释义（不含词性），例如 "丢弃；放弃，抛弃"。"""
    parts = []
    for t in entry.get("translations") or []:
        text = (t.get("translation") or "").strip()  # 中文释义
        if text:
            parts.append(text)
    return "；".join(parts)


# ============================ 2. 字符编码 ============================
def build_char_vocab(words):
    """统计词表里出现过的所有字符，建立 字符->编号 映射。0 留给 <pad>，1 留给 <unk>。"""
    char2idx = {"<pad>": 0, "<unk>": 1}
    for c in sorted(set("".join(words))):
        if c not in char2idx:
            char2idx[c] = len(char2idx)
    return char2idx


def encode_word(word, char2idx, max_len):
    """把单词变成定长字符编号序列：超长截断，不足补 0（<pad>）。"""
    ids = [char2idx.get(c, char2idx["<unk>"]) for c in word][:max_len]
    ids += [char2idx["<pad>"]] * (max_len - len(ids))
    return ids


def mutate_word(word, rng):
    """给单词制造一个「单字符级」错误：删 / 换 / 插 / 交换相邻，用来训练容错能力。"""
    if not word:
        return word
    i = rng.randrange(len(word))
    op = rng.random()
    if op < 0.25:                                   # 删一个字符
        return word[:i] + word[i + 1:]
    if op < 0.50:                                   # 换一个字符
        c = rng.choice(string.ascii_lowercase)
        return word[:i] + c + word[i + 1:]
    if op < 0.75:                                   # 插一个字符
        c = rng.choice(string.ascii_lowercase)
        return word[:i] + c + word[i:]
    j = min(i + 1, len(word) - 1)                   # 交换相邻两个字符
    return word[:i] + word[j] + word[i] + word[j + 1:]


class WordDataset(Dataset):
    """训练数据集：词 -> (字符编号序列, 类别标签)。augment=True 时按概率扰动成错字。"""

    def __init__(self, words, labels, char2idx, max_len, augment=False):
        self.words, self.labels = words, labels
        self.char2idx, self.max_len = char2idx, max_len
        self.augment = augment
        self.rng = random.Random(0)                 # 固定种子，训练可复现

    def __len__(self):
        return len(self.words)

    def __getitem__(self, i):
        w = self.words[i]
        if self.augment and self.rng.random() < AUG_PROB:
            w = mutate_word(w, self.rng)            # 训练时随机把词写错
        x = torch.tensor(encode_word(w, self.char2idx, self.max_len), dtype=torch.long)
        y = torch.tensor(self.labels[i], dtype=torch.long)
        return x, y


# ============================ 3. 模型 ============================
class CharCNN(nn.Module):
    """字符级 CNN。结构和 test.py 的 QYnet 一脉相承：全连接 + ReLU + 输出层。"""

    def __init__(self, char_vocab_size, num_classes):
        super().__init__()
        # 字符嵌入：把每个字符编号映射成 EMBED_DIM 维向量（0 是 <pad>，不参与梯度）
        self.embed = nn.Embedding(char_vocab_size, EMBED_DIM, padding_idx=0)
        # 三种宽度(3/4/5)的一维卷积，像看 3/4/5 个连续字母的组合（类似 n-gram）
        self.convs = nn.ModuleList([
            nn.Conv1d(EMBED_DIM, CONV_FILTERS, k, bias=False) for k in (3, 4, 5)
        ])
        # 全连接层：对应 test.py 的 fc1/fc2/fc3
        self.fc1 = nn.Linear(CONV_FILTERS * 3, FC_HIDDEN)
        self.fc2 = nn.Linear(FC_HIDDEN, FC_EMBED)
        self.fc3 = nn.Linear(FC_EMBED, num_classes)   # 输出层：每个词一个类别
        self.dropout = nn.Dropout(DROPOUT)

    def _extract(self, x):
        """共享的卷积特征提取，输入 x: (batch, max_len) 字符编号。"""
        e = self.embed(x).transpose(1, 2)            # (batch, EMBED_DIM, max_len)
        pooled = []
        for conv in self.convs:
            c = F.relu(conv(e))                      # (batch, CONV_FILTERS, L-k+1)
            c = F.max_pool1d(c, c.size(2)).squeeze(2)  # 每个通道取最大 -> (batch, CONV_FILTERS)
            pooled.append(c)
        h = torch.cat(pooled, dim=1)                 # 拼接三种卷积的结果
        h = self.dropout(F.relu(self.fc1(h)))
        h = self.dropout(F.relu(self.fc2(h)))
        return h                                     # (batch, FC_EMBED) 即词向量

    def forward(self, x):
        """分类前向：返回每个类别的 logits（不做 softmax，CrossEntropyLoss 内部处理）。"""
        return self.fc3(self._extract(x))

    def encode(self, x):
        """返回词向量（用于余弦相似度检索）。"""
        return self._extract(x)


# ============================ 4. 损失函数 ============================
def info_nce(anchor, positive, temp=NCE_TEMP):
    """对比损失：让 anchor（错字向量）最接近它对应的 positive（正确词向量），
    远离同批里其他词的向量。先 L2 归一化，点积即余弦相似度。"""
    anchor = F.normalize(anchor, dim=1)
    positive = F.normalize(positive, dim=1)
    sim = anchor @ positive.T / temp                 # (B, B) 相似度矩阵
    labels = torch.arange(anchor.size(0), device=anchor.device)  # 对角线即正确配对
    return F.cross_entropy(sim, labels)


# ============================ 5. 训练 ============================
def train():
    torch.manual_seed(0)
    random.seed(0)
    np.random.seed(0)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"[训练] 使用设备: {device}")

    vocab = load_vocab()
    words = [e["word"] for e in vocab]              # 词表顺序即类别编号顺序
    num_classes = len(words)
    max_len = max(len(w) for w in words)
    char2idx = build_char_vocab(words)
    print(f"[训练] 词数 {num_classes}，最大词长 {max_len}，字符种类 {len(char2idx)}")

    ds = WordDataset(words, list(range(num_classes)), char2idx, max_len, augment=True)
    loader = DataLoader(ds, batch_size=BATCH, shuffle=True)

    model = CharCNN(len(char2idx), num_classes).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=LR)
    ce_loss = nn.CrossEntropyLoss()
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=EPOCHS)

    for ep in range(1, EPOCHS + 1):
        model.train()
        tot_ce = tot_nce = 0.0
        for x, y in loader:
            x, y = x.to(device), y.to(device)
            opt.zero_grad()
            # ① 分类损失：把（可能写错的）输入映射到正确类别
            ce = ce_loss(model(x), y)
            # ② 对比损失：显式让「错字向量」靠近正确词、远离其他词
            clean = [words[l] for l in y.tolist()]
            x_clean = torch.tensor([encode_word(w, char2idx, max_len) for w in clean],
                                   dtype=torch.long, device=device)
            x_typo = torch.tensor([encode_word(mutate_word(w, random), char2idx, max_len)
                                   for w in clean], dtype=torch.long, device=device)
            nce = info_nce(model.encode(x_typo), model.encode(x_clean))
            loss = ce + NCE_WEIGHT * nce
            loss.backward()
            opt.step()
            tot_ce += ce.item() * x.size(0)
            tot_nce += nce.item() * x.size(0)
        sched.step()
        if ep % 30 == 0 or ep == 1:
            print(f"[训练] epoch {ep:3d}/{EPOCHS}  CE={tot_ce/len(ds):.4f}  NCE={tot_nce/len(ds):.4f}")

    # 保存
    BASE.joinpath("checkpoints").mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), MODEL_PT)
    with open(META_JSON, "w", encoding="utf-8") as f:
        json.dump({"char2idx": char2idx, "words": words, "max_len": max_len}, f, ensure_ascii=False)
    print(f"[训练] 已保存模型 -> {MODEL_PT}")
    print(f"[训练] 已保存元信息 -> {META_JSON}")


# ============================ 6. 离线识别 ============================
class Recognizer:
    def __init__(self, model, char2idx, words, max_len, vocab_by_word, device):
        self.model = model.eval()
        self.char2idx, self.words, self.max_len = char2idx, words, max_len
        self.vocab_by_word, self.device = vocab_by_word, device
        self.embeddings = self._build_index()        # 预计算全词表的归一化词向量

    def _build_index(self):
        xs = torch.tensor([encode_word(w, self.char2idx, self.max_len) for w in self.words],
                          dtype=torch.long, device=self.device)
        out = []
        with torch.no_grad():
            for i in range(0, len(xs), 512):
                out.append(F.normalize(self.model.encode(xs[i:i + 512]), dim=1))
        return torch.cat(out, dim=0)

    def recognize(self, word, topk=1):
        """输入单词，返回最相似的 topk 个词条（词/音标/词性/释义/例句/相似度）。"""
        word = word.strip().lower()
        x = torch.tensor([encode_word(word, self.char2idx, self.max_len)],
                         dtype=torch.long, device=self.device)
        with torch.no_grad():
            q = F.normalize(self.model.encode(x), dim=1)
        sim = q @ self.embeddings.T                   # (1, N) 余弦相似度
        scores, idxs = sim.topk(min(topk, len(self.words)), dim=1)
        out = []
        for k in range(idxs.size(1)):
            w = self.words[idxs[0, k].item()]
            out.append(format_entry(self.vocab_by_word[w], float(scores[0, k].item())))
        return out


def format_entry(entry, score):
    us = (entry.get("us") or "").strip()
    uk = (entry.get("uk") or "").strip()
    phonetic = "  ".join(p for p in [f"美 /{us}/" if us else "", f"英 /{uk}/" if uk else ""] if p)
    trans = entry.get("translations") or []
    pos = "；".join(dict.fromkeys((t.get("type") or "").strip() for t in trans if (t.get("type") or "").strip()))
    sentences = entry.get("sentences") or []
    example = (f"{sentences[0].get('sentence','')}  ——  {sentences[0].get('translation','')}"
               if sentences else None)
    return {"word": entry.get("word"), "phonetic": phonetic, "pos": pos,
            "meaning": word_meaning(entry), "example": example, "score": round(score, 4)}


def load_recognizer(device=None):
    device = device or torch.device("cuda" if torch.cuda.is_available() else "cpu")
    with open(META_JSON, encoding="utf-8") as f:
        meta = json.load(f)
    vocab = load_vocab()
    char2idx, words, max_len = meta["char2idx"], meta["words"], meta["max_len"]
    model = CharCNN(len(char2idx), len(words)).to(device)
    model.load_state_dict(torch.load(MODEL_PT, map_location=device))
    vocab_by_word = {e["word"]: e for e in vocab}
    return Recognizer(model, char2idx, words, max_len, vocab_by_word, device)


def print_entry(r):
    print(f"识别结果: {r['word']}   (相似度 {r['score']:.4f})")
    if r["phonetic"]:
        print(f"  音标: {r['phonetic']}")
    if r["pos"]:
        print(f"  词性: {r['pos']}")
    if r["meaning"]:
        print(f"  释义: {r['meaning']}")
    if r["example"]:
        print(f"  例句: {r['example']}")


# ============================ 入口 ============================
def main():
    ap = argparse.ArgumentParser(description="CET 词汇离线识别（单文件版）")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("train")
    p_rec = sub.add_parser("recognize")
    p_rec.add_argument("--word", required=True)
    p_rec.add_argument("--topk", type=int, default=1)
    sub.add_parser("interactive")
    args = ap.parse_args()

    if args.cmd == "train":
        train()
        return

    rec = load_recognizer()
    if args.cmd == "recognize":
        for r in rec.recognize(args.word, topk=args.topk):
            print_entry(r)
    else:  # interactive
        print("交互模式：输入英文单词回车识别，输入 exit 退出。")
        while True:
            try:
                w = input(">>> ").strip()
            except (EOFError, KeyboardInterrupt):
                break
            if w.lower() in ("exit", "quit", ""):
                break
            for r in rec.recognize(w, topk=1):
                print_entry(r)


if __name__ == "__main__":
    main()
