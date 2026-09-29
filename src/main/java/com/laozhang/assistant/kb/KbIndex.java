package com.laozhang.assistant.kb;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;
// ⚠️ Jackson 3 迁移的**例外**：`com.fasterxml.jackson.annotation` 这两个注解 **包名不变、不用改**。
//    原因（看 pom 实证）：`tools.jackson.core:jackson-databind:3.1.5` 自己反过来依赖
//    `com.fasterxml.jackson.core:jackson-annotations` —— **Jackson 3 直接复用了 2.x 的注解模块**。
//    所以"Jackson 2 的注解"和"Jackson 3 的 databind"是**设计上就配套**的，不是混用。
//    → 迁移时只需换 `databind` / `core` 里的类型，注解照旧。
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;

/**
 * 内存向量索引 —— 把 week5 的 Chroma 库"搬进 JVM"
 * ==================================================
 *
 * 【数据从哪来】由 `probes/export_kb_vectors.py` 从 `week5/chroma_db` 导出到
 *   `src/main/resources/kb-vectors.json`（200 块 × 1024 维，约 2 MB）。
 *   为什么这么绕：本机 `.m2` **没有 Chroma 的 Java client**，而本项目要求**离线可构建**
 *   → 不能加依赖 → 那就**先把库搬进内存**。
 *
 * 【检索怎么做】**暴力扫全部 200 条算余弦**。
 *   · 复杂度 O(N·d) = 200 × 1024 ≈ 20 万次乘加 → **毫秒级**，比任何索引都快；
 *   · "向量数据库"存在的意义是 **N 大到内存暴力不行** 的时候（百万/十亿级）；
 *   · 📌 **200 条用 HNSW 索引是杀鸡用牛刀 —— 而且是"先上工具，后想需求"**。
 *     这与 W7 那条「先用简单规则跑通流程，再考虑上模型」是同一条思路的另一面：
 *     **工具要匹配问题规模，不是越先进越好。**
 *
 * 【距离口径必须与建库时一致】`week5/build_index.py` 建 collection 时写死了
 *   `metadata={"hnsw:space": "cosine"}`，**建完改不了**（W5 `persist_check` 实测过）。
 *   → 这里必须算**余弦**，不能换成欧氏距离 —— 否则排序结果和 Python 侧对不上。
 *   ⚠️ Chroma 的 `distance = 1 - cosine_similarity`（**越小越相似**）；
 *      本项目内部统一用 **similarity（越大越相似）**，报告里要写清用的是哪个口径。
 */
@Component
public class KbIndex {

    /** 一条命中的结果。`similarity` 越大越相似（与 Chroma 的 distance 相反，见类注释）。 */
    public record Hit(String id, String source, int chunkIndex, String text, double similarity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String id, String text, String source,
                @JsonProperty("chunk_index") int chunkIndex,
                float[] vec) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(String collection, String embedding_model, String hnsw_space,
                   int dim, int count, List<Item> items) {
    }

    private final List<Item> items;
    private final String collection;
    private final String space;
    private final int dim;

    public KbIndex() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("kb-vectors.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "找不到 kb-vectors.json —— 先跑 `probes/export_kb_vectors.py` 生成它");
            }
            Payload payload = JsonMapper.builder().build().readValue(in, Payload.class);
            this.items = payload.items();
            this.collection = payload.collection();
            this.space = payload.hnsw_space();
            this.dim = payload.dim();

            // ⚠️ 启动即自检：文件坏了 / 空库 / 维度不符 → **当场炸**，别等问到才出错。
            if (items == null || items.isEmpty()) {
                throw new IllegalStateException("向量库为空 —— 别拿空库当基线");
            }
            if (!"cosine".equalsIgnoreCase(space)) {
                throw new IllegalStateException("库的距离口径是「" + space + "」，与检索侧假设的 cosine 不一致");
            }
            Item first = items.get(0);
            if (first.vec() == null || first.vec().length != dim) {
                throw new IllegalStateException("向量维度与声明的 dim=" + dim + " 不一致");
            }
        }
    }

    /** 暴力扫全部，返回相似度最高的 topK 条（已按 similarity 降序）。 */
    public List<Hit> search(float[] queryVec, int topK) {
        if (queryVec == null || queryVec.length != dim) {
            throw new IllegalArgumentException(
                    "query 向量维度 " + (queryVec == null ? "null" : queryVec.length) + " != 库维度 " + dim);
        }
        List<Hit> hits = new ArrayList<>(items.size());
        for (Item it : items) {
            hits.add(new Hit(it.id(), it.source(), it.chunkIndex(), it.text(),
                    cosine(queryVec, it.vec())));
        }
        hits.sort(Comparator.comparingDouble(Hit::similarity).reversed());
        return hits.size() <= topK ? hits : new ArrayList<>(hits.subList(0, topK));
    }

    /**
     * 余弦相似度 = 点积 / (模长积)。
     * ⚠️ **必须先算模长**：如果向量没归一化就直接点积，结果会被向量长度带跑偏
     *    （长文本的向量往往模长更大 → 会被系统性偏爱）。这是"看起来对、其实错"的典型。
     */
    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public int size() {
        return items.size();
    }

    public int dim() {
        return dim;
    }

    public String collection() {
        return collection;
    }

    public String space() {
        return space;
    }
}
