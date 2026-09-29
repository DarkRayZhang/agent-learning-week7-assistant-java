# W7 主线 · 任务卡：Spring AI 骨架跑通（09-28 周一晚）

> **今晚主攻**：Java 骨架 —— 从「空的 `week7/java/`」到「一条工具调用链跑通」。
> **顺带清一笔尾账**（10min）：月考题 11 重做的 `except` 分支。
> **今晚不做的**：接备忘录检索（W5/W6 向量检索）→ **09-29 晚**；README + push → **09-30 晚**。

---

## 0. 先看：环境我已经全部备好了（你直接写代码）

| 项 | 状态 |
|---|---|
| JDK | **17**（`C:\Users\w\jdk17`，Temurin 17.0.20）✅ |
| ⚠️ PATH 上的 java | **是 1.8**（老 JDK）→ 别用裸 `java` 命令，走 maven（它认 `JAVA_HOME`） |
| Maven | **3.9.9**，在 `C:\Users\w\.m2\wrapper\dists\apache-maven-3.9.9-bin\33b4b2b4\apache-maven-3.9.9\bin\`（**不在 PATH**） |
| spring-ai | **2.0.1** — 已联网预热 ✅（**2026-09-28 从 1.1.8 升级**） |
| spring-boot | **4.1.1** — 已联网预热 ✅（⚠️ 2.0 跑不了 Boot 3.x → 必须 4.x） |
| **离线构建** | ✅ 已验证：`mvn -o dependency:resolve` 静默通过 |
| 源码编译 | ✅ 已验证：`BUILD SUCCESS`（3.1s） |
| 应用启动 | ✅ 已验证：`Started AssistantApplication in 1.3s` |
| 中文乱码 | ✅ 已修（pom 里 `-Dfile.encoding=UTF-8`；实测 `还没写` 不再变 `»¹Ã»Ð´`） |

**跑法**（复制这一整行，PowerShell）：

```powershell
cd "G:\agent学习\week7\java"
chcp 65001 > $null      # 控制台切 UTF-8（不切会看到中文日志乱码）
$env:JAVA_HOME = "C:\Users\w\jdk17"
& "C:\Users\w\.m2\wrapper\dists\apache-maven-3.9.9-bin\33b4b2b4\apache-maven-3.9.9\bin\mvn.cmd" -o spring-boot:run
```

> ⚠️ `&` 不能省（路径带引号必须用调用运算符）—— 这个坑 09-27 你已经踩过一次。
> ⚠️ 用 IDEA 打开也可以，但**必须把 Project SDK 设成 17**（IDEA 默认可能挂在 1.8 上）。

**当前跑会看到**（这是**期望**的报错，说明骨架在等你写）：

```
Started AssistantApplication in 1.3 seconds
java.lang.UnsupportedOperationException: 还没写
```

---

## 1. 我搭好的三个文件（结构 + 判据齐全，函数体留空）

| 文件 | 我写了什么 | 你写什么 |
|---|---|---|
| `java/pom.xml` | 依赖（**最小依赖，没有 web**）、UTF-8、JDK 17 | — |
| `java/src/main/resources/application.yml` | 配置 + `${DEEPSEEK_API_KEY}` + **`spring.config.import: ../.env`** | — |
| `java/.../AssistantApplication.java` | 类头、`@SpringBootApplication`、`demo()` 的**形状 + 注释 + 坑** | **`demo()` 里那 4 步** |
| `java/.../tools/AssistantTools.java` | 类头、三个 `@Tool` 注解 + `@ToolParam` + 描述 + 判据 | **三个方法体** |

**`spring.config.import: optional:file:../.env[.properties]` 是什么**（今晚第一个知识点）：

> **它就是 Java 版的 `load_dotenv()`。**
> `.env` 的格式（`KEY=VALUE` + `#` 注释）**恰好就是 properties 语法** → Spring Boot 可以直接把它当配置文件导入。
> **不需要设任何环境变量、不需要额外脚本。**
>
> | | Python | Java |
> |---|---|---|
> | 读 .env | `from dotenv import load_dotenv; load_dotenv()` | `spring.config.import: optional:file:../.env[.properties]` |
> | 取值 | `os.getenv("DEEPSEEK_API_KEY")` | `${DEEPSEEK_API_KEY}` |
>
> → **同一个 .env 文件、同一个变量名，两种取值语法** —— 又一次「契约一致、实现各写」。

---

## 2. 今晚 <1.5h> 怎么切

| 时段 | 任务 | 时长 | 交付物 |
|:--:|---|:--:|---|
| ① | **清尾账**：`考核/月度测验/m1-q11-redo.py` L96–97 | 10min | 改完贴运行输出 |
| ② | **`AssistantTools` 三个方法体**（提醒 / 计算 / 时间） | 25min | 代码 |
| ③ | **`AssistantApplication.demo()`** 四步 + 跑通 | 25min | **运行输出** |
| ④ | 回填 `notes.md` 周五节 + 我回填仪表 | 10min | — |

### ① 清尾账（10min）—— 只差半个

昨晚扫盘实况：**`json.loads` 已经改对了**（L95 ✅），但 **`except` 分支还是空操作**：

```python
try:
    args = json.loads(tc.function.arguments)       # ✅ 这行已经对了
except Exception as e:
    json.JSONDecodeError                            # ❌ 光写个类名 = 什么也没做
```

**改成**（就是昨晚日志里那两行）：

```python
except json.JSONDecodeError as e:
    messages.append({"role": "tool", "tool_call_id": tc.id,
                     "content": json.dumps({"error": f"参数不是合法 JSON: {e}"}, ensure_ascii=False)})
    continue
```

**验收**：跑 `m1-q11-redo.py`，`call_3` 的报错从「**工具执行异常**」→「**参数不是合法 JSON: ...**」。
👉 意义：**病因归到正确的层**（参数层，不是工具层）。这是台账 **#25「兜底模式没横向复制」** 的销号条件之一。

### ② `AssistantTools` 三个方法（25min）

对着 `week4/tools.py` 抄**语义**，别抄**语法**。三处差异先看文件头注释。

**判据**（写完自检）：
- [ ] `addReminder` 返回文案**与 Python 版逐字对齐**（方便跨语言对照）
- [ ] `calculate` **非法输入返回错误字符串，不抛异常**
- [ ] `getCurrentTime` 返回 `yyyy-MM-dd HH:mm:ss` 格式（**注意 `MM` 大写=月、`mm`=分钟**）
- [ ] **三个方法都不抛异常**（出错就返回错误字符串）—— 与 W6「把失败变成模型可见的信息」同一条原则

### ③ `demo()` 四步（25min）

`chatClient.prompt().user(...).tools(...).call().content()`

**验收**：
- [ ] 控制台打出答案
- [ ] 日志里能看到**工具被调用**（`logging.level.com.laozhang.assistant: DEBUG` 已开）
- [ ] 问的那句话里包含**两件事**（算数 + 问时间）→ 看模型会不会**一次调两个工具**

---

## 3. 今晚重点（不是"跑通"，是这三个观察）

**跑通只是及格线。今晚真正要带走的是这三条对照：**

### 观察 1：谁在跑循环？

```
Python（LangGraph）： llm → tools_condition → tools → llm → ... ← 这个循环是你自己搭的
                      state / node / edge / 回边，你全都要写

Java（Spring AI）：   chatClient.prompt()...call()   ← 循环被框架封装了，你看不到
```

**`.call()` 里面藏着一个 while 循环**：模型要调工具 → 框架执行 → 结果喂回 → 再问 → …… 直到模型不再要工具。
**你不需要手写它，但也意味着出问题时你能调的地方更少。**

👉 **这是今晚最该写进 `notes.md` 的一句话**（周五节「Java 与 Python Agent 框架对比」那道面试题就靠它）。

### 观察 2：工具描述是杠杆（W2 结论在 Java 侧的复现）

`@Tool(description=...)` 和 `@ToolParam(description=...)` 是**模型唯一的输入**。
- **改坏实验**（如果你有时间，2min）：把 `calculate` 的 description 改成「查天气」→ 再跑 →
  看模型**还会不会选它**。**不会报错，只是行为变了** —— 与台账里那条「enum 硬约束 vs description 软约束」是同族。

### 观察 3：Java 的"编译器帮你挡"到什么程度

| 错误类型 | Python | Java |
|---|---|---|
| 方法名拼错 | 运行时 `AttributeError` | **编译期直接报** ✅ |
| 参数类型错 | 运行时才炸 | **编译期直接报** ✅ |
| **工具描述写错** | 模型不选你，无报错 | **一样无报错** ❌ |
| **`n_result` vs `n_results`** | 运行时 `TypeError` | 编译期报（如果是方法名） |

👉 **结论：静态类型挡得住"语法/类型"，挡不住"语义/描述"。** 后者两门语言一样要靠**评测**发现。

---

## 4. 卡住 5 分钟以上，再看这几个探针

1. **`Started AssistantApplication` 之后立刻 `UnsupportedOperationException`** —— 正常，说明你还没写 `demo()`（**这不是报错，是骨架在等你**）
2. **中文变 `»¹Ã»Ð´`** —— `chcp 65001` 没执行（控制台没切 UTF-8）
3. **`Could not resolve placeholder 'DEEPSEEK_API_KEY'`** —— `.env` 没读到 → 检查 `application.yml` 里 `spring.config.import` 那行的路径是不是 `../.env`
4. **`mvn: command not found`** —— maven 不在 PATH，用上面那条完整路径 + `&`
5. **模型不调用工具，直接瞎编答案** —— 先看 `@Tool` 的 description 写清了没有（**W2 的核心结论**），不是框架问题
6. **`spring-boot:run` 找不到主类** —— 检查包名 `com.laozhang.assistant` 和目录结构是否对齐

---

## 5. 顺延说明（记账）

| 项 | 原排 | 新排 | 理由 |
|---|---|---|---|
| 接「备忘录检索」（调 W5/W6 向量检索） | 09-28 晚 | **09-29 晚** | 今晚 1.5h 只够骨架跑通；原排本身就写着"09-28 晚 = Java 主线**补完**"，但**骨架昨天没开工** → 整条链顺延一格 |
| Java README + push | 09-29 晚 | **09-30 晚**（与 W7 收口合并） | 连带 |
| ⚠️ 风险 | — | — | **09-29/09-30 两晚要装完「检索 + README + push + W7 周检查」**，偏紧。备选：把「备忘录检索」降级为**接口占位 + 说明**，保住"骨架跑通 + README 卖点"这两条硬线 |

---

## 6. 今天确认的日历事实（已回填 `notes.md` 顶部）

> **本周（09-28 ~ 09-30）国庆前不发版** ← 你 09-28 亲自确认
> → **三个晚上全部可用，无豁免日**
> → **也意味着：09-30 晚的 W7 收口（含周检查）不会被发版吃掉** —— 这比 W6 那周（一周两发）宽松得多
