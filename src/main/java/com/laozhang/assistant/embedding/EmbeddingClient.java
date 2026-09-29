package com.laozhang.assistant.embedding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
// ⚠️ Jackson 3 迁移：包名 `com.fasterxml.jackson` → **`tools.jackson`**（本次升级最大的改动）。
//    这个包名是 **Spring AI 2.0 自己也在用的那套** —— 框架的 `JsonHelper` 内部持有的就是
//    `tools.jackson.databind.json.JsonMapper`（javap 反汇编 `spring-ai-commons:2.0.1` 实证）。
//    → 我方与框架用同一套实现，序列化行为才不会两边各说各话。
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Embedding 客户端 —— 调**阿里云百炼**（不是 DeepSeek！）
 * ========================================================
 *
 * 【为什么要单独一个 client】
 *   W5 实测过一条硬事实：**DeepSeek 官方 API 不提供 `/embeddings` 端点**（打过去是 404）。
 *   → **chat 与 embedding 是两个不同厂商的服务，必须两套凭据两条链路**。
 *     本项目的 `spring.ai.openai.*` 配的是 DeepSeek（BaseUrl = api.deepseek.com），
 *     **不能拿它去算 embedding** —— 这是 W5 学过、W6 又复发过一次的坑。
 *
 * 【为什么不用 Spring AI 的 EmbeddingModel，而是手写 HTTP】
 *   两条都行，但手写这条**零新依赖、完全离线可构建**：
 *     · Spring AI 的 `OpenAiEmbeddingModel` 也在这个 starter 里，但要额外配一个
 *       "第二个 OpenAI 身份"的 bean（`OpenAiApi` + options），构造签名**在 1.1.8 / 2.0.1 两个版本里都得现查**；
 *     · JDK 17 **自带 `java.net.http.HttpClient`** —— 发个 POST 就完事。
 *   📌 与 `calculate` 那条"Java 没有 `eval`，就用 SpEL"不同：这一次是**Java 有能力自己干**，
 *      那就别为了"用框架"而用框架。
 *      （W7 一直在练的那把尺子：**框架替你做什么、你自己做什么，是按"值不值"分的，不是按"能不能"分的**。）
 *
 * 【格式参照】`week5/embed_batch.py` 用的是 OpenAI SDK 的
 *   `client.embeddings.create(model=EMBEDDING_MODEL, input=chunk)`
 *   → 百炼的 `compatible-mode/v1` 就是 **OpenAI 兼容**协议，所以请求体是
 *     `{"model": "...", "input": "文本"}`，响应是 `{"data": [{"embedding": [...]}]}`。
 */
@Component
public class EmbeddingClient {

    /**
     * Jackson 3 的入口是 **`JsonMapper`**（`JsonMapper.builder().build()`）——
     * 与框架内部 `JsonHelper` 持的是同一个类型，行为对齐。
     *
     * ⚠️ **Jackson 3 最大的行为变化：异常从 checked 变成 unchecked**
     *   · Jackson 2：`readValue` 抛 `JsonProcessingException`（**checked**）→ 不 catch 就编译不过；
     *   · Jackson 3：`tools.jackson.core.JacksonException extends RuntimeException`（**unchecked**）
     *     → **编译器不再逼你处理 JSON 解析失败**。
     *   这不是"变方便了"，是**风险从编译期挪到了运行期** ——
     *   `JsonProcessingException` 这个类在 Jackson 3 里**根本不存在**（javap 实测）。
     *   📌 对本项目的影响：`embed()` 末尾那个 `catch (Exception e)` 仍然兜得住（RuntimeException
     *      也是 Exception），但**这份兜底现在成了唯一的防线**，不再是"编译器帮我记得写"。
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String url;
    private final String apiKey;
    private final String model;
    private final int dim;

    /** ⚠️ HttpClient 是线程安全的、可复用 —— 别每次调用都 new 一个（和 DateTimeFormatter 同一个道理）。 */
    private final HttpClient http;

    public EmbeddingClient(@Value("${embedding.base-url}") String baseUrl,
                           @Value("${embedding.api-key}") String apiKey,
                           @Value("${embedding.model}") String model,
                           @Value("${embedding.dim:1024}") int dim) {
        this.url = baseUrl.replaceAll("/+$", "") + "/embeddings";
        this.apiKey = apiKey;
        this.model = model;
        this.dim = dim;
        this.http = HttpClient.newBuilder()
                // ⚠️ **超时是必须的**：不设 connectTimeout，网络不通时线程会一直挂着。
                //    这是"把失败变成可见信息"的第一步 —— **先让失败能发生，才谈得上处理失败**。
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 把一段文本变成向量。
     *
     * ⚠️ 出错时**抛异常**（而不是像工具那样返回错误字符串）——
     *    因为**这一层不是工具**，它是工具内部的依赖。错误往上冒到 `AssistantTools.search_kb`，
     *    在**工具那一层**被 catch 成"给模型看的错误串"。分层要清楚：
     *    **底层抛，边界吞。**
     */
    public float[] embed(String text) {
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "model", model,
                    "input", text,
                    "dimensions", dim));

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "embedding 接口返回 " + response.statusCode() + "：" + truncate(response.body()));
            }

            JsonNode vecNode = MAPPER.readTree(response.body()).path("data").path(0).path("embedding");
            if (!vecNode.isArray() || vecNode.isEmpty()) {
                throw new IllegalStateException("embedding 响应里没有向量：" + truncate(response.body()));
            }

            List<Float> boxed = new ArrayList<>(vecNode.size());
            vecNode.forEach(n -> boxed.add((float) n.asDouble()));
            float[] vec = new float[boxed.size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = boxed.get(i);
            }

            // ⚠️ **维度必须校验**：库里存的是 1024 维，如果换了模型回 768 维，
            //    余弦点积会**索引越界**（或者更糟：静默算错）。→ 宁可当场炸，也别给出错误答案。
            if (vec.length != dim) {
                throw new IllegalStateException(
                        "维度不匹配：期望 " + dim + "，实际返回 " + vec.length + "（是不是换了 embedding 模型？）");
            }
            return vec;
        }
        catch (IllegalStateException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException("调用 embedding 接口失败：" + e.getMessage(), e);
        }
    }

    public int dim() {
        return dim;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
