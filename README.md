# Assistant Java —— 用 Spring AI 重写的个人效率助手

> **同一产品，Python / Java 双实现。**
> 先前的 Python 版用 LangGraph 手搭状态机，这一版用 Spring AI 重写。
> 两者**功能契约完全对齐**，但**框架取舍恰好相反** —— 这个对照本身就是本项目最值得看的地方。

---

## 这是什么

一个命令行 AI 助手：用自然语言驱动 4 个工具，自己决定该调哪个、调几次。

| 工具 | 作用 | 说明 |
|---|---|---|
| `addReminder` | 记一条提醒 | 内存 `List`，**跨轮保持** |
| `calculate` | 算数学表达式 | Java 没有 `eval` → 用 **SpEL** + 白名单 |
| `getCurrentTime` | 报当前时间 | Python 版没有，本版新增 |
| `searchKb` | 检索个人备忘录 | **真接向量检索**（不是占位实现） |

一段真实运行（`mvn -o spring-boot:run`，节选，**按真实输出顺序**）：

```
[启动] Started AssistantApplication in 1.898 seconds

[第 1 问 · 记提醒]
      [外层|order=0]    >>> 进入，本轮送进去 1 条消息
      [内层|order=9000] >>> 进入，本轮送进去 1 条消息
      [内层|order=9000] <<< 返回，模型这轮有没有要调工具 = true
      [外层|order=0]    <<< 返回，模型这轮有没有要调工具 = true
  ... DefaultToolCallingManager : Executing tool call: addReminder
      [外层|order=0]    >>> 进入，本轮送进去 3 条消息      ← 工具结果加回了上下文（1 → 3）
      [内层|order=9000] <<< 返回，模型这轮有没有要调工具 = false  ← 循环的终止条件
Q: 提醒我明天上午9点交周报。
A: 已经帮你记下来了：**交周报 — 明天上午9点** ✅

[第 3 问 · 检索备忘]
  ... Executing tool call: searchKb
      [search_kb] query=「发布值班」 top1=0.5738 (doc_043.md#0)
Q: 我的备忘里有没有关于发布值班的内容？
A: 有的，你的备忘里有两篇同主题（"发布值班"，都记于 2026-09-15）的笔记，内容重合度很高：……
```

> 📌 **为什么 `Q:` 印在轨迹后面**：`demo()` 是**先 `call()`、拿到 answer 之后才 `println`** 的
> （见 `AssistantApplication.demo()`）—— 不是日志顺序错乱。要看「问题 → 轨迹」的直觉顺序，
> 得把 `println` 挪到 `call()` 前面。

## 技术栈

| 层 | 选型 |
|---|---|
| JDK | **17** |
| 框架 | **Spring Boot 4.1.1** + **Spring AI 2.0.1** |
| JSON | **Jackson 3**（`tools.jackson`，随 Spring AI 2.0 一并迁移） |
| 对话模型 | **DeepSeek**（走 OpenAI 兼容协议，`spring-ai-starter-model-openai` + 换 `base-url`） |
| Embedding | **阿里云百炼** `text-embedding-v4`（1024 维） |
| 向量检索 | 本地内存**暴力余弦**（数据源：200 块真实向量，从 Chroma 导出） |

> ⚠️ **对话和 embedding 是两个厂商**：DeepSeek 官方 API **没有 `/embeddings` 端点**（打过去 404），
> 所以两套凭据、两条链路。这是配置结构里就写死的事，避免以后混用。

## 快速开始

**1. 准备凭据**

```bash
cp .env.example .env      # 然后填上两家的 Key（模板里写清了各自去哪申请、要填哪几个变量）
```

`application.yml` 用 **`spring.config.import`** 把 `.env` 当成 properties 直接读 ——

> 这就是 **Java 侧的 `load_dotenv()`**：不需要额外的 dotenv 库、不需要手动 `export`。
> `.env` 的 `KEY=VALUE` 格式恰好就是 properties 语法，Spring Boot 原生就能吃。

也支持另外两种摆法（优先级：就近的赢）：

| 摆法 | 场景 |
|---|---|
| `java/.env` | 独立仓库形态，**推荐** |
| `week7/.env`（上级目录） | 本地学习仓库形态 —— 与 Python 侧**共用同一个 .env** |
| 系统环境变量 | 容器 / CI，同名即可 |

**2. 运行**

```bash
mvn spring-boot:run        # 首次：联网拉依赖（JDK 17 + Maven 3.9+）
mvn -o spring-boot:run     # 依赖已在本地仓库时：离线跑
```

**3. 关于 `src/main/resources/kb-vectors.json`（2 MB）**

它**已经在仓库里**，不用你自己生成 —— 这是刻意的：

- 它是 `searchKb` 的检索底库（200 块 × 1024 维真实向量 + 原文），启动时由 `KbIndex` 加载；
- 生成它的 `probes/export_kb_vectors.py` 依赖本地 `week5/chroma_db`，**那份库不在公开仓库里**；
- 也就是说：**不带上这个文件，clone 下来一启动就会炸**（`KbIndex` 会主动抛异常，不会静默降级）。

所以宁可让仓库多 2 MB，也不让"clone → 跑不起来"。

想看工具调用过程，`application.yml` 里已开好：

```yaml
logging:
  level:
    org.springframework.ai: DEBUG     # ⚠️ 工具调用日志在框架包里，只开自己的包看不到
```

## 项目结构

```
.
├── .env.example                         # 凭据模板（复制成 .env 填 Key）
├── src/main/java/com/laozhang/assistant/
│   ├── AssistantApplication.java        # 入口 + CommandLineRunner demo
│   ├── tools/AssistantTools.java        # 4 个 @Tool
│   ├── embedding/EmbeddingClient.java   # 调百炼算向量（JDK 自带 HttpClient，零新依赖）
│   ├── kb/KbIndex.java                  # 内存向量索引 + 暴力余弦 + 启动自检
│   ├── advisor/TraceAdvisor.java        # 自定义 Advisor，观测工具循环的每一轮
│   └── model/Reminder.java
├── src/main/resources/
│   ├── application.yml
│   └── kb-vectors.json                  # 200 × 1024 向量（从本地 Chroma 库导出，见「快速开始 3」）
└── probes/                              # 一次性探针 / 脚本（保留，用于复现结论）
```

## 四个值得一看的设计点

**① 检索是"真接"的，而且故意搬进了内存**

`searchKb` 的链路是真的：`query → 百炼 embedding（1024 维）→ 暴力余弦 → Top-3（带来源和相似度）`。

之所以**没有**引入向量数据库客户端：本机环境里没有对应的 Java client，而项目要求**离线可构建**（不加依赖）。
200 条 × 1024 维暴力扫是**毫秒级**的 ——
**向量库的意义在 N 大到内存暴力不行的时候；工具要匹配问题规模，不是越先进越好。**

**② 拒答防线：阈值的作用不是"提高准确率"**

`MIN_SIMILARITY = 0.45`，低于它的命中直接丢弃，检索层返回"没找到"。

关键不在阈值本身，而在于 —— **它把"没找到"变成一个模型能看到的事实**。
实测：模型收到"没找到"之后，**自己改写 query 重试了两次**（「量子计算」→「量子 量子力学 量子比特 qubit」），
最终诚实回答"没有找到相关记录"。

没有这道防线，模型只会拿最相似的垃圾硬编一个答案。

> ⚠️ 诚实标注：`0.45` 是经验值、**尚未标定**（3 个样本的分离度是 0.57/0.64 vs 0.31/0.30）。
> 要换成有依据的值，需要用 golden set 跑相似度分布。

**③ 工具返回值为什么返回 `Map` 而不是 `String`**

不是"修 bug"，是**要确定性**。

实测（`probes/ConverterProbe.java`，直接调用框架的转换器）：

| 工具返回 | 模型实际收到 |
|---|---|
| `"{\"ok\":true}"` | 原样透传（JSON 对象形状） |
| `"60"` | **裸数字 60**（不是字符串） |
| `"(12+8)*3 = 60"` | `"(12+8)*3 = 60"`（被包了引号） |
| `"{\"ok\":true,}"`（尾逗号非法） | `"{\"ok\":true,}"`（被包了引号） |

→ **同一个方法，返回值形状随"内容"变** = 不确定。返回 `Map` 则是确定的。
顺带：这也让 `fastjson` 这个依赖整个可以删掉（连带规避了 CVE-2026-16723）。

**④ 工具循环能被自己插桩（Spring AI 2.0 的新能力）**

`TraceAdvisor` 是一个 30 行的自定义 `CallAdvisor`，挂在链上就能数出**循环跑了几轮**：

| 读数 | 含义 |
|---|---|
| 穿透次数 = 2 / 问 | 循环跑了 2 轮 |
| **消息数 1 → 3** | 第 2 轮多出的两条正是 `assistant(tool_calls)` + `tool(结果)` —— **工具结果被加回上下文的直接证据** |
| `hasToolCalls` true → false | **循环的终止条件** |

> Spring AI 2.0 把工具循环正式纳入了 advisor 体系（新增 `ToolAdvisor` 接口 + `ToolCallingAdvisor` 基类）
> → 从这里拿的是**结构化对象**，而不是解析日志文本。

## 与 Python 版的对照

这是本项目的核心叙事 —— 同一件事，两种框架给出了**相反**的取舍：

| | Python 版（LangGraph） | 本版（Spring AI） |
|---|---|---|
| 工具定义 | `@tool` 装饰器 + docstring | `@Tool` 注解 + `description` |
| 绑定工具 | `model.bind_tools(TOOLS)` | `.tools(实例)` |
| **谁在跑循环** | **自己搭**：state / node / edge / 回边 | **框架跑**：`DefaultToolCallingManager` |
| 循环体可见性 | 逐行走查、中间态想停就停 | 封在框架里 —— 靠日志，或**自己写 advisor 插桩** |
| 返回值契约 | `dict`（框架自动 `json.dumps`） | `Map`（框架自动序列化） |

**取舍的实质**：Spring AI 少写一半代码，但**出问题时能调的地方更少**；
LangGraph 什么都要自己写，但**每一步都握在手里**。
`TraceAdvisor` 那段就是这个问题的一半答案 —— 框架封了循环，但**留了插桩的口子**。

## 踩过的坑（都留了探针，可复现）

- **工具描述到底管什么用** —— 做了四级消融（改方法名 / 删描述 / 留参数名 / 加边界声明）：
  结论是**描述几乎不影响"选不选得中"**（只要参数名清楚、候选工具之间差异大），
  但它**明显影响"回复质量"**（无描述时模型会过度承诺）。
  → 修正后的判据：**「描述是杠杆」的杠杆率 = f(候选工具之间有多像)**。
- **验证框架行为，必须直接调框架那一行** —— 曾用裸 Jackson 模拟框架行为，得出错误结论；
  后来直接调 `DefaultToolCallResultConverter` 跑边界用例，才拿到真读数。
- **Spring AI 1.x → 2.0 迁移** —— 硬门槛是必须上 Spring Boot 4（连带 Spring Framework 7 + Jackson 3，
  包名从 `com.fasterxml.jackson` 换成 `tools.jackson`）。
  踩点包括：`.options` 配置段在 2.0 是**标废（deprecated）而非移除**；
  异常由 checked 变 unchecked；**注解包反而不变**（Jackson 3 反向依赖 2.x 的 annotations）。

## 状态

- ✅ 4 个工具全部跑通（含真接向量检索）｜离线可构建、离线可运行
- ✅ 环境：JDK 17 + Spring Boot 4.1.1 + Spring AI 2.0.1
- ⬜ `MIN_SIMILARITY` 阈值标定（用 golden set 跑分布）
- ⬜ 检索结果只做了截断，没做"查全"（Top-3 相似度接近时该不该都给？）

## 安全

- 所有 Key 走**环境变量 / 上级目录 `.env`**，仓库内**不含任何明文凭据**（`.env` 已被 gitignore）
- 依赖树里已移除 `fastjson`（1.x 有未修补的 RCE，本项目本来就不需要它）
