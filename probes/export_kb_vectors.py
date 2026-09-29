"""把 week5 的 Chroma 向量库导出成 Java 侧可加载的 JSON。
=========================================================

【为什么需要这个脚本】
  Java 侧要接 W5/W6 的向量检索，但有两个硬约束撞在一起：
    ① 本机 `~/.m2` 里**没有 Chroma 的 Java client**（实测）；
    ② 整个 week7/java 项目是**离线可构建**的（`mvn -o`），**不能加新依赖**。
  → 换一条路：**把库"搬进内存"**。
    200 块 × 1024 维的向量 + 原文导出成一个 JSON，Java 侧启动时加载，
    query 向量化之后**在内存里暴力算余弦**（200 条 = 毫秒级）。

  📌 这不是"降级"，是**先用最笨但确定能跑的方式把链路接通** ——
     与 W7 那条「先用简单规则跑通流程，再考虑上模型」是同款取舍。
     （真上向量库是 W9 评估周之后的事。）

【输出契约】（Java 侧 `KbIndex` 按这个结构读）
  {
    "collection": "week5_docs",
    "embedding_model": "text-embedding-v4",
    "hnsw_space": "cosine",
    "dim": 1024,
    "count": 200,
    "items": [ { "id": "...", "text": "...", "source": "...", "chunk_index": 0,
                 "vec": [0.012345, ...] }, ... ]
  }

【用法】（必须在 week5 的 venv 里跑 —— 那里才有 chromadb）
    cd G:/agent学习/week7/java
    ../../week5/.venv/Scripts/python.exe probes/export_kb_vectors.py
"""

import json
import os
import sys

import chromadb

# week5 的库路径（相对本脚本：probes/ -> java/ -> week7/ -> agent学习/week5/chroma_db）
HERE = os.path.dirname(os.path.abspath(__file__))
PERSIST_DIR = os.path.abspath(os.path.join(HERE, "..", "..", "..", "week5", "chroma_db"))
OUT_PATH = os.path.abspath(os.path.join(HERE, "..", "src", "main", "resources", "kb-vectors.json"))
COLLECTION = "week5_docs"
VEC_DECIMALS = 6      # 向量分量保留精度；cosine 对 1e-7 级差异不敏感，换来体积减半


def main():
    if not os.path.isdir(PERSIST_DIR):
        sys.exit(f"[FATAL] 找不到 Chroma 库目录：{PERSIST_DIR}")

    client = chromadb.PersistentClient(path=PERSIST_DIR)
    # ⚠️ 用 get_collection 而非 get_or_create_collection —— 后者会**静默建一个空库**（W5 踩过）
    try:
        col = client.get_collection(COLLECTION)
    except Exception as e:
        sys.exit(f"[FATAL] 取不到 collection「{COLLECTION}」：{e}")

    data = col.get(include=["documents", "embeddings", "metadatas"])
    ids = data["ids"]
    docs = data["documents"]
    vecs = data["embeddings"]
    metas = data["metadatas"]

    if not ids:
        sys.exit("[FATAL] collection 是空的 —— 别拿空库当基线")

    dim = len(vecs[0])
    counts = {len(ids), len(docs), len(vecs), len(metas)}
    if len(counts) != 1:
        sys.exit(f"[FATAL] 四个数组长度不一致：ids={len(ids)} docs={len(docs)} "
                 f"vecs={len(vecs)} metas={len(metas)}")

    items = []
    for i in range(len(ids)):
        meta = metas[i] or {}
        items.append({
            "id": ids[i],
            "text": docs[i],
            "source": meta.get("source", ""),
            "chunk_index": meta.get("chunk_index", -1),
            "vec": [round(float(x), VEC_DECIMALS) for x in vecs[i]],
        })

    payload = {
        "collection": COLLECTION,
        "embedding_model": "text-embedding-v4",
        "hnsw_space": "cosine",
        "dim": dim,
        "count": len(items),
        "items": items,
    }

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))

    size_mb = os.path.getsize(OUT_PATH) / 1024 / 1024
    print(f"[ok] collection = {COLLECTION}")
    print(f"[ok] 块数 = {len(items)} ｜ 维度 = {dim}")
    print(f"[ok] 输出 = {OUT_PATH}")
    print(f"[ok] 体积 = {size_mb:.2f} MB")
    print()
    print("--- 抽样第 1 条（验证契约）---")
    print(f"  id      : {items[0]['id']}")
    print(f"  source  : {items[0]['source']}")
    print(f"  text[:60]: {items[0]['text'][:60]}...")
    print(f"  vec[:3] : {items[0]['vec'][:3]}")
    print(f"  vec 长度: {len(items[0]['vec'])}")


if __name__ == "__main__":
    main()
