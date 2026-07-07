# 简历项目经历 — MagicCode AI 编程助手

> 面向岗位：Java + AI 后端开发实习
> 项目类型：个人项目（学习并复刻业界 Agentic AI 助手架构，独立完成 Java 21 实现与云端部署）

---

## MagicCode — 基于 Java 21 的 AI 编程助手（Agentic Loop 架构）

**时间：** 2025.xx – 2025.xx ｜ **角色：** 独立开发 ｜ **代码规模：** 133 个源文件，20+ 包，104 个单测

**项目简介：** 一个支持交互式终端（TUI）、非交互打印、远程 Web 三种模式的 AI 编程助手。核心是 Agentic Loop（感知-决策-行动循环）引擎，能自主调用工具完成代码生成、重构、调试等复杂编程任务，并已部署到阿里云服务器对外提供服务。

**技术栈：** Java 21（虚拟线程 / 密封接口 / Record / 模式匹配）、Spring Boot 3（HTTP + WebSocket）、Anthropic & OpenAI 官方 SDK、MCP 协议 SDK、JLine3、Maven、JUnit 5

### 核心工作与技术亮点

**1. Agentic Loop 核心引擎（AI 后端核心）**
- 设计并实现完整的 Agent 循环：LLM 流式调用 → 工具调度 → 结果回填 → 迭代，支持 `max_tokens` 续写恢复、速率限制退避、上下文过长强制压缩等错误恢复策略，保证长任务稳定运行。
- 用 `sealed interface StreamEvent`（TextDelta / ToolCallComplete / StreamEnd 等 8 种）统一 Anthropic 与 OpenAI 两套流式协议，Agent 核心零感知底层 Provider，**新增 LLM 供应商只需实现一个接口**（适配器 + 策略模式）。
- 设计三级提示缓存锚点（系统提示 / 工具 Schema / 末条用户消息），配合 `ContentReplacementState` 追踪工具结果替换决策保证字节稳定前缀，**最大化缓存命中率，降低 API 成本与延迟**。

**2. 长上下文管理 — 两层渐进式压缩**
- Layer 1 工具结果溢出：单结果超 5 万字符 / 消息聚合超 20 万字符时溢出磁盘，替换为引用 + 预览，幂等决策确保缓存稳定。
- Layer 2 LLM 摘要压缩：基于绝对 Token 计数触发，保留近 1–4 万 Token 原文，旧消息交由 LLM 生成 9 段结构化摘要，并用真实 API 用量锚点（UsageAnchor）校准字符估算，**支持数小时连续编程会话不丢上下文**。
- 压缩后附加恢复附件（最近文件快照、活跃技能 SOP），并写 `compact_boundary` 到 JSONL 会话文件，实现**秒级压缩感知恢复**。

**3. Java 21 虚拟线程高并发**
- 全系统基于虚拟线程：Agent 主循环、LLM 流式、读工具并行执行（`newVirtualThreadPerTaskExecutor()`）、子 Agent 后台任务、记忆召回预取均运行在虚拟线程上。
- `StreamingExecutor` 按工具类别分区调度：相邻 READ 工具合并为并行批次，WRITE/COMMAND 顺序执行，兼顾并发与安全，无需线程池调参。
- 跨线程通信用有界 `BlockingQueue`（容量 64，天然背压）+ `CompletableFuture` 异步桥接权限确认 / 用户提问。

**4. 工具系统与 MCP 协议集成**
- 定义 `Tool` 接口契约，实现 12 个内置工具（Bash / Read / Write / Edit / Glob / Grep 等）及完整的权限 → 钩子 → 执行 → 截断管道。
- 设计**延迟加载机制**：MCP 工具默认 `shouldDefer()=true`，Schema 不下发 LLM，通过 `ToolSearch` 按需发现，**百级工具场景下工具描述 Token 占用减少约 85%**。
- 集成 MCP 官方 SDK，支持 stdio / HTTP 传输、环境变量解析与 Windows 兼容。

**5. 纵深安全防御（9 层权限流水线）**
- 实现 9 层检查：Plan 模式例外 → 安全命令白名单 → 危险命令拦截 → 禁写路径保护 → 路径沙箱 → YAML 规则引擎 → 会话级覆盖 → 沙箱放行 → 权限模式矩阵兜底，任一层拒绝即终止。
- 支持 4 种权限模式（DEFAULT / ACCEPT_EDITS / PLAN / BYPASS）与三级 YAML 规则文件，用户可精细自定义；OS 级沙箱联动（macOS Seatbelt / Linux Bubblewrap）。

**6. 多 Agent 协作与 Web 服务**
- 子 Agent 支持 Fork（克隆对话）/ Spec（专用类型）/ Teammate（长驻团队）三种派发；用 Git Worktree 做文件系统隔离，`FileMailBox`（文件锁 + 重试）实现 Agent 间通信。
- Spring Boot 内嵌 Tomcat 提供 HTTP + WebSocket 双协议远程服务，18 种事件类型的 JSON 消息协议，`CompletableFuture` 桥接前端权限决策。

**7. 云端部署与运维**
- Maven shade 打包为 Fat JAR，scp 上传阿里云 Ubuntu 服务器，配置 systemd 实现后台常驻、崩溃自动重启与开机自启，对外提供稳定的 Web 服务。

### 难点与解决思路（面试可展开）

| 难点 | 解决方案 |
|------|---------|
| 同时支持 Anthropic / OpenAI 两套差异巨大的流式协议 | 密封接口 `StreamEvent` 统一抽象 + 适配器模式，Agent 核心零感知 Provider |
| 长会话超出 LLM 上下文窗口 | 两层渐进式压缩 + UsageAnchor 真实用量校准 + 恢复附件，保证上下文连续性 |
| 上百个 MCP 工具描述挤占上下文 | 延迟加载 + ToolSearch 按需发现，Token 占用降 85% |
| Agent 执行 Shell 命令的安全风险 | 9 层纵深防御流水线 + YAML 规则引擎 + OS 沙箱联动 |
| Windows IME 中文输入导致 TUI 光标错乱 | 弃用相对光标上移，改用 DSR 查询行号 + 绝对定位渲染 |
| Agent 与 UI 跨线程交互 | BlockingQueue 事件总线 + CompletableFuture 异步桥接，UI 层完全可替换 |

### 技术关键词

`Java 21` `虚拟线程` `密封接口/Record/模式匹配` `Agentic Loop` `LLM 流式调用` `Function Calling` `Prompt Caching` `MCP 协议` `上下文压缩` `Spring Boot` `WebSocket` `设计模式（适配器/策略/状态）` `CompletableFuture` `BlockingQueue` `Maven` `JUnit` `Linux 部署` `systemd`

---

## 面试高频问题预案（自用，不放简历）

<details>
<summary>点击展开</summary>

**Q：项目里哪些地方用了并发？怎么管理的？**
A：全面用 Java 21 虚拟线程。Agent 主循环、LLM 流式、工具并行执行、子 Agent 后台任务、记忆召回预取都是虚拟线程。读工具用 `newVirtualThreadPerTaskExecutor()` 并行，写工具顺序执行保证安全。跨线程通信用 BlockingQueue（背压）+ CompletableFuture（异步桥接权限确认）。

**Q：怎么同时支持 Anthropic 和 OpenAI？**
A：定义 `LlmClient` 接口 + `StreamEvent` 密封接口统一抽象，两个 Client 各自把 SDK 事件适配成 StreamEvent，Agent 核心只消费 StreamEvent。新增供应商实现一个接口即可，是适配器 + 策略模式。

**Q：长对话超出上下文窗口怎么办？**
A：两层压缩。第一层工具结果溢出到磁盘留预览；第二层 LLM 摘要压缩，保留近 1–4 万 Token 原文，旧消息生成结构化摘要。用真实 API 用量做锚点校准字符估算，避免缓存命中导致估算偏高。压缩后附恢复附件（文件快照、技能 SOP），保证上下文连续。

**Q：上百个工具怎么避免挤占上下文？**
A：延迟加载。工具声明 `shouldDefer()=true` 后 Schema 不下发，LLM 通过 `ToolSearch` 按需发现，发现后 `markDiscovered()` 加入可见列表。MCP 工具默认延迟，Token 占用降 85%。

**Q：AI 助手要执行 Shell 命令，怎么保证安全？**
A：9 层纵深防御。白名单放行只读命令、正则拦截 rm -rf / 等危险命令、禁写路径保护、路径沙箱限制项目目录、YAML 规则引擎让用户自定义、OS 沙箱联动。任一层拒绝即终止。

**Q：提示缓存怎么优化？**
A：三级锚点——系统提示、工具 Schema 块、末条用户消息尾部都标记 CacheControlEphemeral。再用 ContentReplacementState 追踪工具结果替换决策，保证每次生成的提示字节一致，最大化缓存命中。

</details>
