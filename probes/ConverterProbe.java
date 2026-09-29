import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 证据探针：Spring AI 到底怎么处理工具返回值？
 * =============================================
 *
 * 【起因】跑 Java 版助手时，日志里每个工具执行完都跟着一行
 *   DefaultToolCallResultConverter : Converting tool result to JSON.
 * → 框架对返回值做了一次统一转换。那"工具自己返回 JSON 串"会怎样？
 *
 * 【第一版结论是错的，本探针是纠正版】
 *   第一版用**裸 Jackson**（`new ObjectMapper().writeValueAsString(str)`）模拟框架行为，
 *   得出"String 会被包一层引号 → 双重编码" —— **模拟错了**。
 *   直接调用框架类实测 + 读源码后发现，Spring AI 的 `JsonParser.toJson` 里有一道特判：
 *
 *       if (object instanceof String str && isValidJson(str)) return str;   // 合法 JSON 就透传
 *       return OBJECT_MAPPER.writeValueAsString(object);                    // 否则照常包引号
 *
 * 【2.0.1 复验（2026-09-28 升级后）】
 *   反汇编 `spring-ai-commons:2.0.1` 的 `JsonHelper.toJson(Object, boolean)` 字节码：
 *   上面这段逻辑 **逐字保留**，唯一变化是内部 mapper 的类型 ——
 *       1.1.8 : `com.fasterxml.jackson.databind.ObjectMapper`
 *       2.0.1 : `tools.jackson.databind.json.JsonMapper`      ← Jackson 3
 *   → 所以"String 当合法 JSON 时透传"这条结论**跨版本依然成立**，但**必须实跑复验**：
 *     本探针在 `java/`(1.1.8) 与 `java-v2/`(2.0.1) 下各跑一次，输出应当**逐字一致**。
 *     📌 这正是「**别用模拟品验证框架行为**」的落地：探针调的是**框架那一行**，
 *        换版本时只要**重跑同一份探针**，就能拿到有意义的对比。
 *
 *   → **结论：双重编码不存在。** 教训：**别用"等价物"模拟框架，直接调框架那一行。**
 *
 * 用法（JDK17 单文件模式，classpath 由 `mvn -o dependency:build-classpath` 导出）：
 *   java --class-path "$(cat cp.txt)" probes/ConverterProbe.java
 */
public class ConverterProbe {

    public static void main(String[] args) {
        var converter = new DefaultToolCallResultConverter();

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("ok", true);
        resultMap.put("message", "hello");

        System.out.println("── Spring AI 2.0.1 · DefaultToolCallResultConverter 实测 ──");
        System.out.println("   （同一份探针在 java/(1.1.8) 下也跑过，输出用于逐字对比）");
        System.out.println("   ⚠️ 判据：String 返回值的去留 = 它**内容本身是不是合法 JSON**（isValidJson）");
        System.out.println();

        row(converter, "① return JSON.toJSONString(map)  内容＝合法 JSON 串",
                "{\"ok\":true,\"message\":\"hello\"}", String.class,
                "→ **原样透传**，模型拿到 JSON 对象形状 ✓（所以旧写法其实没坏）");

        row(converter, "② return resultMap              内容＝Map",
                resultMap, Map.class,
                "→ 框架序列化一次，结果与 ① **完全一致** ✓");

        row(converter, "③ return \"(12+8)*3 = 60\"         纯文本，非合法 JSON",
                "(12+8)*3 = 60", String.class,
                "→ 走 writeValueAsString → **被包了对引号**（内容可读，无害）");

        row(converter, "④ return \"60\"                   纯数字串，**是**合法 JSON",
                "60", String.class,
                "→ 透传成裸数字 `60`，**不是字符串**（内容相关的意外行为！）");

        row(converter, "⑤ return \"abc\"                  裸词，非合法 JSON",
                "abc", String.class,
                "→ 被包成 \"abc\"");

        row(converter, "⑥ return \"{\\\"ok\\\":true,}\"      尾逗号 = 非法 JSON",
                "{\"ok\":true,}", String.class,
                "→ 被包成 \"{\\\"ok\\\":true,}\"（**同一个工具，行为随内容变**）");

        System.out.println("""
                ── 结论 ──
                ① 框架**没有**双重编码 —— 我第一版判错了，根因是拿裸 Jackson 模拟框架。
                ② 但"返回 String"的行为是**内容相关**的：同一个方法，返回 `60` 和返回 `abc`
                   形状不同；返回 `{"a":1}` 和返回 `{"a":1,}` 形状也不同。
                   而"返回 Map/对象"的行为是**确定的**（永远是 JSON 对象）。
                ③ → 所以改成返回 Map 仍然值得，**但理由不是"修 bug"，而是**：
                   a. 行为确定（不靠内容猜）；
                   b. 类型即契约（Map = 结构化数据，String = 文本），与 Python 版返回 dict 对齐在**类型层**；
                   c. **fastjson 这个高危依赖整个不需要了**（CVE-2026-16723 攻击面归零）。
                """);
    }

    private static void row(DefaultToolCallResultConverter converter, String label,
                            Object input, Class<?> type, String note) {
        System.out.println(label);
        System.out.println("     模型收到 : " + converter.convert(input, type));
        System.out.println("     " + note);
        System.out.println();
    }
}
