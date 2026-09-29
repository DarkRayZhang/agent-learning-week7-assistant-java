package com.laozhang.assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.laozhang.assistant.advisor.TraceAdvisor;
import com.laozhang.assistant.tools.AssistantTools;

/**
 * W7 主线 · Spring AI 版个人效率助手（骨架）
 * ==========================================
 *
 * 【跑法】（在 week7/java/ 目录下）
 *   # ① **不需要设任何环境变量** —— `.env` 由 application.yml 的
 *   #     `spring.config.import: optional:file:../.env[.properties],optional:file:.env[.properties]`
 *   #     直接读进来（逗号分隔：**后者优先**）
 *   #     = Java 版的 load_dotenv()；`.env` 的 KEY=VALUE 恰好就是 properties 语法
 *   #     本地学习仓库里 .env 在 `week7/`（与 Python 侧共用）；独立 clone 时放 `java/.env`
 *   # ② 用 wrapper 里的 maven（本机 mvn 不在 PATH 上）
 *   $MVN="C:\Users\w\.m2\wrapper\dists\apache-maven-3.9.9-bin\33b4b2b4\apache-maven-3.9.9\bin\mvn.cmd"
 *   chcp 65001                          # ⚠️ 不切 UTF-8 中文日志会乱码
 *   & $MVN -o spring-boot:run           # -o = 离线（依赖已全在本地仓库）
 *   # ③ 想看"工具真的被调了"必须开 org.springframework.ai: DEBUG（见 application.yml）
 *
 * 【今晚的验收：能跑通一条工具调用链】
 *   输入一句自然语言 → 模型选中工具 → 工具执行 → 模型用结果回答
 *   ⚠️ 这与 W4 Python 版「自然语言 → 调工具 → 回答」是同一条链路，**只是换了语言和框架**。
 *
 * 【Java vs Python 的心智对照（W4 → W7）】
 *   | W4 Python                    | W7 Java                          |
 *   |------------------------------|----------------------------------|
 *   | `model.bind_tools(TOOLS)`    | `.tools(AssistantTools.getInstance())` |
 *   | `@tool` 装饰器 + docstring    | `@Tool` 注解 + description       |
 *   | LangGraph 自己写循环          | **Spring AI 自己跑 Agent 循环**   |
 *     （llm → tools → llm → ...）    （你只写"问一句、要一句回答"）
 *   | 循环体你能逐行走查             | 循环体在 `DefaultToolCallingManager` 里，**只能看日志** |
 *
 *   📌 **最后一行是今晚最该记住的差异**：
 *      Python 侧（LangGraph）循环是**你自己搭的**——state / node / edge / 回边，你全都要写；
 *      Spring AI 把「调工具 → 把结果喂回模型 → 再问」这个循环**封装进了 ChatClient**，
 *      你**看不到**那个 while 循环，也**不需要**手写它。
 *      → 好的一面：省事；坏的一面：**出问题时你能调的地方更少**（可控性换开发速度）。
 *      这也是周五要写进 `notes.md` 的那道对照题。
 */
@SpringBootApplication
public class AssistantApplication {



    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }

    /**
     * 跑**两轮**问答，把结果打到控制台。
     *
     * ★ 第一轮由老张实现（09-28，结构全对）；第二轮是当晚加的**跨轮状态探针**。
     *
     * 实现要点：
     *   ① `builder.build()` 建 client；
     *   ② 链式发起调用：
     *        chatClient.prompt().user(问题)
     *                .tools(工具实例)      // ★ 与 Python 的 bind_tools 对应
     *                .call()              // 同步执行
     *                .content();          // 取文本回答
     *   ③ `System.out.println(answer)` 打印。
     *
     * ⚠️ **注意 `CommandLineRunner` 的 lambda 对应 `void run(String... args)` —— 没有返回值**，
     *    所以"把 answer 返回出去"这一步**不存在**（原注释写的"④ 返回 answer"是错的，已删）。
     *    要"返回"就得换一个载体：`ApplicationRunner` 同样 void；真要把结果传出去，
     *    通常靠写文件 / 打印 / 或改成 `@Component` 暴露方法。
     *
     * ⚠️ 两个坑：
     *   - `.tools(...)` 必须传**对象实例**，不是 Class；
     *   - 方法名/参数名**拼错编译器会直接报**（Java 相对 Python 的便宜），
     *     但**工具描述写错编译器不管你**（模型选不中工具是运行时问题）→ 与 W2 结论一致：
     *     **Function Calling 的核心杠杆是 tool 描述准确性**。
     *
     * ⚠️ **`.tools()` 传的必须是"同一个实例"才能跨轮记住状态** ——
     *    这里由 **Spring 注入** `AssistantTools assistantTools`（类上有 `@Component`，**默认作用域就是 singleton**），
     *    不是每次 `new AssistantTools()`。
     *    🔬 **为什么跑两轮**：第二轮问"再帮我记一条"，若状态没跨轮，`reminders` 会归零 → 答"共 1 条"；
     *    实测答 **"你现在一共有 2 条提醒事项"** → **同一个实例生效**（见 notes.md）。
     *    📌 **为什么从"手写单例"改成 `@Component`**（09-28）：
     *    手写单例（`private` 构造 + `static final` 实例 + `getInstance()`）**能跑**，
     *    但**Spring 注入不进去** —— 而 `search_kb` 必然要注入 embedding client。
     *    `@Component` 拿到的**本来就是单例**，还白得「可注入 / 可被测试替换」两样。
     *    ⚠️ **作用域陷阱**：若将来给它标 `@Scope("prototype")` / `@RequestScope`，
     *    跨轮状态会**静默失效**（每轮新实例）—— 这是 Spring 侧最容易踩的一处：
     *    **"能跑通"和"记得住"是两件事，作用域决定后者。**
     */
    @Bean
    CommandLineRunner demo(ChatClient.Builder builder,AssistantTools assistantTools) {
        return args -> {
            // 🔬 **2.0 观测实验**（09-28）：链上挂两个 `TraceAdvisor`（order=0 / 9000）。
            //    实测：**两者都被穿过 2 次**（= 该问跑了 2 轮 LLM）→ 我原本"外层只穿一次"的预期是错的：
            //    **`ToolCallAdvisor` 的 order 比 0 还小**（在最外层），循环是它**在内部反复调用下游链**。
            //    → **只要 order 比 `ToolCallAdvisor` 大，就能看到每一轮**。
            //    拿到三条读数：① 穿透次数=轮数 ② **消息数 1→3**（工具结果被加回上下文）
            //                  ③ `hasToolCalls` true→false（循环的终止条件）。
            //    📌 这就回答了"2.0 能不能看到过程"：**能，且拿的是结构化对象，不是日志文本**。
            //       （1.1.8 只能开 DEBUG 日志、肉眼数 `Executing tool call:` 的行数。）
            ChatClient chatClient = builder
                    .defaultAdvisors(new TraceAdvisor("外层", 0), new TraceAdvisor("内层", 9000))
                    .build();
            for (String question : new String[]{
                    "提醒我明天上午9点交周报。",
                    "再帮我记一条：后天下午3点开周会。",
                    "我的备忘里有没有关于发布值班的内容？"}) {
                String answer = chatClient.prompt().user(question)
                        .tools(assistantTools).call().content();
                System.out.println("Q: " + question);
                System.out.println("A: " + answer);
                System.out.println("────────────");
            }
        };
    }
}
