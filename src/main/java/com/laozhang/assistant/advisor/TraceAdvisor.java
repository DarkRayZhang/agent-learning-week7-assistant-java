package com.laozhang.assistant.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

/**
 * 观测探针：一个最小可用的自定义 Advisor
 * ========================================
 *
 * 【它要回答的问题】「升级到 Spring AI 2.0，就能直接看到工具循环的过程了？」
 *
 * 【为什么这个问题不能用"看文档"回答】
 *   1.1.8 里 **`ToolCallAdvisor` 就已经存在**（本机 `.m2` 里两个 jar 都验过），
 *   而 1.1.8 我们**也确实看到过程了** —— 靠 `org.springframework.ai: DEBUG` 日志里的
 *   `Executing tool call: xxx` 那一行。所以"2.0 才能看到"这个说法**不准确**。
 *   → 真正要问的是：**能不能拿到"结构化的中间态"，而不是解析日志文本？**
 *
 * 【2.0 的实质变化（javap 实测）】
 *   · **新增接口** `advisor.api.ToolAdvisor`（1.1.8 里没有）
 *   · **新增基类** `ToolCallingAdvisor`（1.1.8 里没有），它同时实现
 *     `CallAdvisor` + `StreamAdvisor` + **`ToolAdvisor`** → **工具循环正式成为 advisor 链的一员**
 *   · `ToolCallAdvisor` 从"直接实现 CallAdvisor"变成 **`ToolCallingAdvisor` 的子类**，
 *     并暴露 public `builder()`（1.1.8 只有 protected 构造器）
 *   → 结论：**循环体的每一步，现在都合法地暴露在 advisor 链上** ——
 *     你插一个 advisor 进去，就能拦住每一次模型调用，拿到结构化的 `ChatClientRequest/Response`。
 *
 * 【怎么用它看见"每一轮"】—— ⚠️ 实测读数（09-28 22:50）
 *   我原本的预期是「order 小 = 最外层 = 只穿一次，order 大 = 最内层 = 每轮一次」。
 *   **实测把这个预期推翻了**：order=0 与 order=9000 **都被穿了 2 次**（= 该问跑了 2 轮 LLM）。
 *   → 说明 **`ToolCallAdvisor` 的 order 比 0 还小**（在更外层），
 *     而"再来一轮"是它**在内部反复调用下游链**实现的：
 *
 *         ToolCallAdvisor（order 更小，在最外）
 *           ├─ 第 1 轮 ──▶ order=0 ──▶ order=9000 ──▶ 真·模型调用（送进去 1 条消息）
 *           └─ 第 2 轮 ──▶ order=0 ──▶ order=9000 ──▶ 真·模型调用（送进去 3 条消息）
 *
 *   📌 所以判据比想象的宽松：**只要 order 比 `ToolCallAdvisor` 大（落在它下游），就能看到每一轮** ——
 *      不需要精确卡进某个夹缝。真正"看不见"的只有比 `ToolCallAdvisor` 更外层的位置。
 *
 * 【实测拿到的三条读数 —— 这才是"2.0 能看到过程"的证据】
 *   ① **穿透次数** → 每问 2 次 = **循环跑了 2 轮**（1.1.8 只能开 DEBUG 日志、肉眼数行）
 *   ② **消息数轨迹 1 → 3** → 第 2 轮多出的 2 条正是 `assistant(tool_calls)` + `tool(结果)`
 *      = **循环"把工具结果加回上下文"的直接证据**
 *      （与 W4 D14 那次「条数轨迹 4→8→5」是同一种铁证手法）
 *   ③ **`hasToolCalls` 由 true → false** → **循环的终止条件**（模型不再要工具了）就摆在眼前
 *
 *   📌 这三条都是从**结构化对象**（`ChatClientRequest` / `ChatClientResponse`）里取出来的，
 *      **不是解析日志文本** —— 这才是 2.0 相对 1.1.8 的实质进步。
 *
 * ⚠️ 注意 `adviseCall` 里的 `chain.nextCall(request)` 是**继续往下走链**（环绕式）：
 *    不调它 = 链断在这里。这不是装饰，是**责任链**。
 */
public class TraceAdvisor implements CallAdvisor {

    private final String tag;
    private final int order;

    /**
     * @param tag   打印用的标签（区分外层/内层）
     * @param order 越小越靠外；**给一个很大的值 → 落到最内层 → 每轮都会被调用一次**
     */
    public TraceAdvisor(String tag, int order) {
        this.tag = tag;
        this.order = order;
    }

    @Override
    public String getName() {
        return "TraceAdvisor-" + tag;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        int inMessages = request.prompt().getInstructions().size();
        System.out.println("      [" + tag + "|order=" + order + "] >>> 进入，本轮送进去 " + inMessages + " 条消息");

        ChatClientResponse response = chain.nextCall(request);

        boolean hasToolCalls = response.chatResponse() != null && response.chatResponse().hasToolCalls();
        System.out.println("      [" + tag + "|order=" + order + "] <<< 返回，模型这轮有没有要调工具 = " + hasToolCalls);
        return response;
    }
}
