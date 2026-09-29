package com.laozhang.assistant.tools;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.laozhang.assistant.embedding.EmbeddingClient;
import com.laozhang.assistant.kb.KbIndex;
import com.laozhang.assistant.model.Reminder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

/**
 * 助手工具集（骨架 —— ★ 函数体你来写）
 * =====================================
 * <p>
 * 【和 W4 Python 版 `week4/tools.py` 的对照清单（2026-09-29 更新 · 四项全部交付）】
 * | Python (week4/tools.py)         | Java (本文件)                        | 状态 |
 * |---------------------------------|--------------------------------------|:----:|
 * | `add_reminder(content, when)`   | `addReminder(content, when)` → `Map` | ✅ |
 * | `calculate(expression)`         | `calculate(expression)`（**SpEL**）   | ✅ |
 * | ——（Python 版没有）              | `getCurrentTime()`                   | ✅ 新增 |
 * | `search_kb(query)`              | `searchKb(query)` **真接向量检索**     | ✅ |
 *
 * ⭐ **`searchKb` 最终走的是"真接"，不是"占位"**（09-28 21:38–21:53）：
 *   链路：query → **阿里云百炼** `text-embedding-v4`（1024 维）→ `KbIndex` 内存暴力余弦 → Top-3。
 *   数据源是 `week5/chroma_db` 里 **200 块真实向量**（由 `probes/export_kb_vectors.py` 导出成 JSON）。
 *   📌 **为什么"搬进内存"而不是引向量库客户端**：本机 `.m2` **没有 Chroma 的 Java client**，
 *      而本项目要求**离线可构建**（不加依赖）→ 200 × 1024 暴力扫只要**毫秒级**。
 *      **向量库的价值在 N 大到内存暴力不行的时候；工具要匹配问题规模，不是越先进越好。**
 *
 * 📌 历史留档：本段曾写着"`search_kb` 降级为接口占位 / 推到国庆"——那是**排期未定时**的备选方案。
 *    实际当晚（09-28）容量够，就直接真接了。**注释与代码不同步，是这类文件最容易出的错**：
 *    改完实现要回头扫一眼"上面那些话还成立吗"。
 * <p>
 * 【Java vs Python 工具定义的三处差异（今晚必须过一遍）】
 * <p>
 * ① **描述写在哪**：
 * Python → 写在 **docstring** 里（`"""添加一条提醒。Args: ..."""`）
 * Java   → 写在 **注解** 里（`@Tool(description = "...")` / `@ToolParam(description = "...")`）
 * 📌 共同点（也是 W2 的核心结论）：**描述是模型唯一的输入**。
 * 写不清 → 模型不选你 → 这不是 bug，是**描述没写好**。
 * <p>
 * ② **参数有哪些**：
 * Python → 靠**函数签名** + docstring 里的 Args 段
 * Java   → 靠函数签名 + `@ToolParam`（**每个参数都要标，否则模型看到的参数是"无名"的**）
 * <p>
 * ③ **返回值怎么给模型**：
 * Python → 返回 `dict`，框架自动 `json.dumps`
 * Java   → 返回**任意对象**，Spring AI 自动序列化
 * 📌 **建议（09-28 实测 + 读源码后定稿；本段曾写错两版，以下是纠正版）**：
 * **结构化结果 → 返回 `Map`；纯文本结果 → 返回 `String`。**
 *
 * ⚠️ **先纠正一个我（狗子）讲错的结论**：这里**没有"双重编码"**。
 *    我一开始用**裸 Jackson** `new ObjectMapper().writeValueAsString(str)` 去模拟框架行为，
 *    得出"返回 String 会被再包一层引号" —— **模拟错了**。直接调框架类 + 读源码后发现，
 *    Spring AI 的 `JsonParser.toJson` 有一道特判：
 *    `if (object instanceof String str && isValidJson(str)) return str;   // 合法 JSON 就原样透传`
 *    `return OBJECT_MAPPER.writeValueAsString(object);                    // 否则才包引号`
 *    → 所以 `return JSON.toJSONString(map)` 那种写法**其实是好的**，模型拿到的是 JSON 对象形状。
 *
 * ⚠️ **那为什么仍建议返回 `Map`？理由是"确定性"，不是"修 bug"**：
 *    返回 String 时，模型到底收到对象还是字符串，**取决于这个串的"内容"是不是合法 JSON**：
 *    `probes/ConverterProbe.java` 实测（同一个方法，只是返回值内容不同）：
 *      `"{\"ok\":true,\"message\":\"hello\"}"` → 原样透传（对象形状）
 *      `"60"`                                → 透传成**裸数字 60**（不是字符串！）
 *      `"(12+8)*3 = 60"`                     → 被包成 `"(12+8)*3 = 60"`
 *      `"{\"ok\":true,}"`（尾逗号非法）        → 被包成 `"{\"ok\":true,}"`
 *    → **行为随内容变 = 不确定**。返回 `Map` 则是**确定**的（永远是 JSON 对象）。
 *    另外，"类型即契约"：`Map` = 我在声明"这是结构化数据"，与 Python 版返回 `dict` 对齐在**类型层**。
 *    顺带最大的实惠：**fastjson 这个高危依赖整个可以删掉**（CVE-2026-16723 攻击面归零）。
 *
 * ⚠️ 唯一下限：**别直接返回自定义对象**（如 `Reminder`）—— 虽然能被序列化，
 *    但字段名/嵌套结构会原样暴露给模型，等于把内部模型当接口用；
 *    要么自己组 `Map`，要么给它写明确的 `toString()`。
 * <p>
 * 【错误处理的总原则（W2 已学，这里照搬）】
 * 工具内部出错 → **返回错误信息字符串**，**不要抛异常**。
 * 理由：抛异常会让整条 Agent 链断掉；返回错误 → **模型能看到错误并优雅应对**。
 * （这正是 W6 两道拒答防线的同款思路：**把失败变成模型可见的信息**。）
 */
@Component
public class AssistantTools {

    /**
     * 时间格式串 —— 做成常量（**DateTimeFormatter 线程安全**，可复用）。
     * ⚠️ 大小写承载语义，写错**不一定报错，只会输出错的时间**：
     * yyyy = 年 ｜ **MM = 月**（Month）｜ dd = 日
     * **HH = 24 小时制**（h 是 12 小时制）
     * **mm = 分钟**（minute）｜ ss = 秒
     * → 把 MM 和 mm 写反（`yyyy-mm-dd HH:MM:ss`）会得到
     * `2026-56-28 20:09:33` 这种荒谬结果，而**格式化过程不报错**。
     * 同族：`n_result` vs `n_results` —— **一个字符承载语义**。
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 内存提醒存储 —— W4 Python 版的 `REMINDERS = []` 对应物。
     */
    private final List<Reminder> reminders = new ArrayList<>();

    /**
     * 🆕 `search_kb` 需要的两个依赖 —— **由 Spring 注入**。
     *
     * 📌 **这就是当初把"手写单例"改成 `@Component` 的价值兑现处**：
     *    手写单例的构造器是 `private`，Spring 注入不进来 → 这两个字段**根本没法写**。
     *    （09-28 那句"现在改 5 分钟，明天改是边写工具边改框架"——这就是那个明天。）
     *
     * ⚠️ 注意这两个依赖是**两个不同厂商**：
     *    `EmbeddingClient` → **阿里云百炼**（算向量）；`KbIndex` → 本地内存（存向量）。
     *    而 `ChatClient` 那侧走 **DeepSeek** —— 三条链路，别混。
     */
    private final EmbeddingClient embedding;
    private final KbIndex kb;

    /** 检索默认返回条数（与 Python 版 W6 `rag.py` 的 `top_k=3` 对齐）。 */
    private static final int TOP_K = 3;

    /**
     * 相似度下限 —— **拒答防线的门槛**（W6 学的那两道防线，这里用第一道：检索层）。
     * ⚠️ **0.45 是经验值、尚未标定**：需要拿 golden set / 拒答题跑出分布才知道该切在哪。
     *    📌 阈值的作用不是"提高准确率"，而是**把"没找到"变成一个模型能看到的事实**
     *      —— 有它，模型才可能诚实说"没找到"；没它，模型会拿最相似的垃圾硬编答案。
     */
    private static final double MIN_SIMILARITY = 0.45;

    public AssistantTools(EmbeddingClient embedding, KbIndex kb) {
        this.embedding = embedding;
        this.kb = kb;
    }


    // ══════════════════════════════════════════════════════════════════
    //  工具 1：添加提醒
    // ══════════════════════════════════════════════════════════════════

    /**
     * ★ 你来写函数体（约 5~8 行）。
     * <p>
     * 要求：
     * ① 把提醒存进 `reminders`（格式随意，比如 "内容（时间）"）
     * ② 返回一句**给人看的确认话**，包含**当前共几条**
     * 例：`已添加提醒：交周报（明天上午9点），当前共 1 条`
     * （和 Python 版 `add_reminder` 的返回**逐字对齐**，方便跨语言对照）
     * <p>
     * ⚠️ `@Tool` 的 description 我写好了，**你别改** —— 它是模型选工具的唯一依据，
     * 今晚最后一步会拿它做对照实验（改坏它 → 看模型是否还选得中）。
     */
    @Tool(description = "添加一条提醒事项（仅登记到列表，不会到点自动提醒/弹窗，也不支持重复或周期提醒）。"
            + "当用户说'提醒我'、'记一下'、'别让我忘了'，或说'设个闹钟'、'加个备忘'、'存一下'时使用 —— "
            + "这类说法都走本工具（只是记一笔，不是真的定时响铃）。"
            + "参数 content 是要提醒的内容，when 是提醒时间（如'明天上午9点'，用户没说就填'未指定'）。")
    public Map<String, Object> addReminder(
            @ToolParam(description = "提醒的内容，例如：交周报") String content,
            @ToolParam(description = "提醒时间描述，例如：明天上午9点；用户没提时间就填'未指定'") String when) {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            Reminder reminder = new Reminder();
            reminder.setContent(content);
            reminder.setWhen(when);
            reminders.add(reminder);
            resultMap.put("ok", true);
            resultMap.put("message", "已添加提醒：" + content + "（" + when + "），当前共 " + reminderCount() + " 条");
        } catch (Exception e) {
            resultMap.put("error", e.getMessage());
        }
        return resultMap;
    }

    // ══════════════════════════════════════════════════════════════════
    //  工具 2：计算
    // ══════════════════════════════════════════════════════════════════

    /**
     * ★ 你来写函数体（约 8~12 行）。
     * <p>
     * 目标：安全地算一个算术表达式（只允许数字和 + - * / ( ) 和小数点）。
     * <p>
     * ⚠️ **Java 没有 Python 的 `eval`** —— 这是本工具最值得体会的一处差异：
     * Python 版是 `eval(expression, {"__builtins__": {}}, {})` 一行搞定（靠白名单正则兜底）；
     * Java 没有等价物，**必须自己解析/求值**。
     * → **两条路，任选一条，但要在注释里写清你选了哪条、为什么**：
     * (a) **自己写一个小解析器**（递归下降 / 双栈）—— 约 30 行，真锻炼，但今晚时间可能不够；
     * (b) **走"够用就好"**：先用**正则白名单**校验输入只含合法字符，
     * 再做最简单的**顺序求值**（不支持括号优先级也行，够演示链路）。
     * <p>
     * 📌 **我的建议：今晚走 (b)** ——
     * 因为今晚的验收目标是「**骨架跑通一条工具调用链**」，不是"写个计算器"。
     * 计算器写太深会吃掉 Java 骨架的时间；而且**先让链路通，再换实现**，
     * 这正是 W7 周一那条「**先用简单规则跑通流程，再考虑上模型**」的同款取舍。
     * <p>
     * 要求：
     * ① 非法字符 → 返回错误字符串（**不抛异常**）
     * ② 正常 → 返回包含**表达式和结果**的一句话
     * 例：`(12+8)*3 = 60`
     */
    @Tool(description = "计算一个算术表达式，支持加(+)、减(-)、乘(*)、除(/)和括号。"
            + "当用户问'等于多少'、'算一下'、涉及数字计算时使用。"
            + "参数 expression 是要计算的数学表达式，例如：(12+8)*3")
    public String calculate(
            @ToolParam(description = "数学表达式，只含数字和 + - * / ( ) . ，例如：(12+8)*3") String expression) {

        String reg = "[0-9+\\-*/().\\s]+";

        // ① 白名单校验 —— **反着判**：不合格就直接 return
        //    你原来写的是 `if (matches) { }`（正着判），那样要把全部逻辑塞进大括号里，
        //    多一层嵌套。**early return** 是 Java/Spring 更常见的写法（Guard Clause）。
        //    ⚠️ 这一步不是可有可无的：SpEL 能调用方法、读写属性 ——
        //       不校验就等于把"表达式求值"变成 RCE 入口（与刚才 fastjson 那个 CVE **同一个主题**）。
        if (expression == null || !expression.matches(reg)) {
            return "表达式含非法字符，只支持数字和 + - * / ( )";
        }

        // ② 求值 —— 这里就是 Java 版的 `eval`
        //    ⚠️ Java **没有** Python 的 eval()；但 Spring 自带一个表达式引擎 **SpEL**，
        //       作用等价（spring-expression 已在 classpath，零新增依赖）。
        //    对照：
        //       Python: eval("(12+8)*3", {"__builtins__": {}}, {})
        //       Java  : new SpelExpressionParser().parseExpression("(12+8)*3").getValue()
        //    两边都必须**先做白名单**才能用（这正是 Python 那版传 {"__builtins__": {}} 的用意）。
        try {
            Object value = new SpelExpressionParser().parseExpression(expression).getValue();
            return expression + " = " + value;
        } catch (Exception e) {
            // ③ 白名单只挡得住"字符"，挡不住"结构"——`1+`、`(((`、`1++2` 字符全合法，
            //    但表达式本身是坏的 → 靠这层 catch 兜底。
            //    ⚠️ 返回错误串、**不抛异常**（与 W6「把失败变成模型可见的信息」同一条原则）。
            return "计算出错：" + e.getMessage();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  工具 3：当前时间（Java 新增，Python 版没有）
    // ══════════════════════════════════════════════════════════════════

    /**
     * ★ 你来写函数体（约 3~5 行）。
     * <p>
     * 目标：返回**当前日期时间**的字符串。
     * <p>
     * ⚠️ Java 的时间 API 是个坑区（比 Python 复杂得多），今晚只需要知道这一个：
     * `java.time.LocalDateTime.now()`        记住这句话怎么念：
     * `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")`
     * `LocalDateTime.now().format(格式化器)`
     * → 注意 `yyyy` / `MM`（大写=月，小写 mm=分钟）/ `dd` / `HH`（24 小时制）——
     * **大小写写错不会报错，只会输出错的时间**（新版 DateTimeFormatter 会抛异常，算它救了你一把）。
     * 📌 这与「`n_result` vs `n_results`」是同一族：**一个字符承载语义**。
     * <p>
     * 要求：返回形如 `现在是 2026-09-28 20:15:33`
     */
    @Tool(description = "获取当前的日期和时间。当用户问'现在几点'、'今天几号'、'当前时间'时使用。无参数。")
    public String getCurrentTime() {
        // ⚠️ DateTimeFormatter 是**线程安全**的 → 做成 static final 常量，
        //    别每次调用都 ofPattern 一遍（和 Python 里预先编译正则 re.compile 是同一个道理）。
        //    格式串的大小写有语义（写错不一定报错，见下面注释）：
        //      yyyy 年 ｜ MM **月** ｜ dd 日 ｜ HH **24小时制**小时 ｜ mm **分钟** ｜ ss 秒
        return "现在是 " + LocalDateTime.now().format(TIME_FORMATTER);
    }

    // ══════════════════════════════════════════════════════════════════
    //  工具 4：知识库检索（🆕 09-28，接 W5/W6 的向量检索）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 在本地知识库里检索与 query 相关的片段。
     *
     * 【与 Python 版的关系】
     *   `week4/tools.py` 的 `search_kb` **本来就是模拟**（4 条硬编码 + 子串匹配，
     *   docstring 里写着「模拟；W7 起替换为向量检索」）→ **Java 版这次是真接**，
     *   底子是 `week5/chroma_db` 导出的 200 块真实向量。
     *   ⚠️ 但**返回契约必须对齐 Python 版**（双实现周的规矩）：
     *      有命中 → `{"hits": [...]}`
     *      无命中 → `{"message": "知识库中没有找到相关内容", "hits": []}`
     *
     * 【链路三层】
     *   query(文本) → `EmbeddingClient`(百炼) → 向量
     *               → `KbIndex`(内存暴力余弦) → Top-K 命中
     *               → 带上**来源与相似度**回给模型
     */
    @Tool(description = "在本地知识库/备忘录中检索与查询相关的片段。"
            + "当用户问'我的备忘里有没有…'、'查一下资料'、'我之前记过什么'、需要回忆个人记录时使用。"
            + "参数 query 是要检索的内容，用自然语言描述即可，例如：发布值班 事故复盘")
    public Map<String, Object> searchKb(
            @ToolParam(description = "检索内容，自然语言即可，例如：发布值班的流程") String query) {

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            float[] queryVec = embedding.embed(query);
            List<KbIndex.Hit> hits = kb.search(queryVec, TOP_K);

            // 📌 **观察点**：把 top-1 相似度打出来 —— 阈值（0.45）现在还只是拍脑袋定的，
            //    要靠这些读数才能标定。**先让数据可见，再谈调参。**
            hits.stream().findFirst().ifPresent(h ->
                    System.out.printf("[search_kb] query=「%s」 top1=%.4f (%s#%d)%n",
                            query, h.similarity(), h.source(), h.chunkIndex()));

            // 拒答防线（检索层）：低于阈值的**一律不返回**，让"没找到"变成模型可见的事实。
            List<KbIndex.Hit> kept = hits.stream()
                    .filter(h -> h.similarity() >= MIN_SIMILARITY)
                    .toList();

            if (kept.isEmpty()) {
                result.put("message", "知识库中没有找到相关内容");
                result.put("hits", List.of());
                return result;
            }

            result.put("hits", kept.stream()
                    .map(h -> "【来源 %s #%d ｜ 相似度 %.3f】%n%s"
                            .formatted(h.source(), h.chunkIndex(), h.similarity(), h.text()))
                    .toList());
            return result;
        }
        catch (Exception e) {
            // 与其它工具同一条原则：**返回错误串、不抛异常** —— 让模型能看到失败并优雅应对。
            result.put("error", "检索失败：" + e.getMessage());
            result.put("hits", List.of());
            return result;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  只为自检用：把当前提醒条数暴露出来（**我写好了，你别改**）
    // ══════════════════════════════════════════════════════════════════
    public int reminderCount() {
        return reminders.size();
    }
}
