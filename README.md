# MagicCode

> 一个基于 **Agentic Loop（智能体循环）** 架构、用 **Java 21** 重新实现的 AI 编程助手（由 Go 版参考实现移植而来），充分利用现代 Java 语言特性重新设计。

[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Build](https://img.shields.io/badge/build-133%20sources%20%2F%200%20errors-brightgreen.svg)](#构建)
[![Tests](https://img.shields.io/badge/tests-104%20passing-brightgreen.svg)](#测试)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](#许可证)

> 🌐 其他语言：[English](./README.md)

MagicCode 是一个以终端为核心的 AI 编程助手，能够自主调用工具（Shell、文件读写改、搜索、子智能体、MCP 服务器等）来完成代码生成、重构、调试等复杂编程任务。同一套代码库支持**三种运行模式**：

- **TUI（交互式终端）** — 基于 The Elm Architecture 的自研终端界面，支持流式输出、权限弹窗、计划审批、团队协作面板。
- **Print（非交互）** — 单次执行、结果输出到标准输出，适合脚本与 CI/CD 集成。
- **Remote（远程 Web 服务）** — 基于 Spring Boot 的 HTTP + WebSocket 服务，内置浏览器 UI。

核心 Agent 引擎与 UI 完全解耦：它通过 `BlockingQueue<AgentEvent>` 事件总线与任意前端通信，因此同一套"大脑"可以同时驱动终端、打印模式和 Web UI。

---

## ✨ 功能特性

- **Agentic Loop 核心引擎** — LLM 流式调用 → 工具调度 → 结果回填 → 迭代循环，内置 `max_tokens` 续写恢复、速率限制退避、上下文超长时强制压缩等错误恢复策略。
- **多 LLM 提供商支持** — Anthropic 与 OpenAI 协议统一在 `LlmClient` 接口之后；新增提供商只需实现一个接口。
- **提示缓存优化** — 三级缓存锚点（系统提示 / 工具 Schema / 末条用户消息）+ 字节稳定前缀追踪，最大化缓存命中、降低 API 成本与延迟。
- **两层渐进式上下文压缩** — 工具结果溢出到磁盘 + LLM 生成的结构化摘要，并用真实 API Token 用量（`UsageAnchor`）校准，支持数小时连续会话不丢失上下文。
- **工具系统 + MCP** — 12 个内置工具，集成 Model Context Protocol（stdio / HTTP），支持**延迟加载**（延迟下发 Schema），百级工具场景下工具描述 Token 占用减少约 85%。
- **9 层纵深权限防御** — 安全命令白名单、危险命令拦截、禁写路径保护、路径沙箱、YAML 规则引擎、OS 级沙箱联动（macOS Seatbelt / Linux Bubblewrap）。
- **多智能体协作** — Fork / Spec / Teammate 三种子智能体派发路径，Git Worktree 文件系统隔离，基于文件的智能体间通信（`FileMailBox`）。
- **技能系统 & 记忆系统** — 三层加载的技能系统（inline / fork 执行、热重载、远程安装）+ 由 LLM 驱动的持久化记忆系统。
- **生命周期钩子引擎** — 9 种事件、4 种动作类型（命令 / 提示 / HTTP / 智能体）、条件表达式语言，全部可通过 YAML 配置。
- **全面使用 Java 21** — 虚拟线程实现轻量高并发、密封接口 + Record 实现类型安全的事件协议、模式匹配、文本块。

---

## 🏗️ 整体架构

```
                         ┌──────────────────┐
                         │  MagicCode.java  │   入口 / CLI 解析 / 模式分发
                         └────────┬─────────┘
                                  │
                ┌─────────────────┼─────────────────┐
                │                 │                  │
         ┌──────▼──────┐  ┌──────▼────────┐  ┌──────▼────────┐
         │  PrintMode  │  │ MagicCodeModel│  │ RemoteServer  │
         │  (非交互)   │  │ (TUI / TEA)   │  │ (Spring Boot  │
         └─────────────┘  └───────┬───────┘  │  HTTP + WS)   │
                                  │           └──────┬────────┘
                                                （共享同一 Agent 核心）
                                  └────────────────┘
                                           │
                                  ┌────────▼────────┐
                                  │    Agent.java   │   智能体循环
                                  │  · LLM 流式调用  │
                                  │  · 工具调度      │
                                  │  · 错误恢复      │
                                  │  · 自动压缩      │
                                  └────────┬────────┘
              ┌──────────┬──────────┬───────┼───────────┬──────────┐
       ┌──────▼───┐ ┌────▼─────┐ ┌─▼────┐ ┌▼────────┐ ┌▼───────┐ ▼──────────┐
       │ToolRegist│ │Streaming │ │Permis│ │Conversa-│ │  MCP   │ │  Skill    │
       │ry        │ │Executor  │ │sion  │ │tionMgr  │ │Manager │ │  Catalog  │
       └──────────┘ └──────────┘ └──────┘ └─────────┘ └────────┘ └───────────┘
```

**核心设计原则**

- **一个核心，三种 UI** — Agent 通过有界 `BlockingQueue` 发射 `AgentEvent`，UI 层完全可替换。
- **事件驱动解耦** — 密封接口 `AgentEvent` 作为事件协议；TUI 与 Remote 共用同一套事件契约。
- **虚拟线程并发** — Agent 循环、LLM 流式、读工具并行、子智能体、记忆预取全部运行在 Java 21 虚拟线程上。
- **纵深防御** — 9 层独立权限检查，任一层拒绝即终止操作。
- **协议抽象** — `LlmClient` + 密封接口 `StreamEvent` 将 Anthropic 与 OpenAI 统一为单一内部事件流。
- **延迟工具加载** — 声明延迟的工具其 Schema 不下发给 LLM，直到通过 `ToolSearch` 被发现。

---

## 🧱 技术栈

| 领域    | 选型                                    | 说明                                         |
| ------- | --------------------------------------- | -------------------------------------------- |
| 语言    | Java 21                                 | 虚拟线程、密封接口、Record、模式匹配、文本块 |
| 构建    | Maven + `maven-shade-plugin`            | 单文件零依赖 Fat JAR                         |
| 终端 UI | JLine 3 + Mordant + 自研 TEA 框架       | 终端原语、颜色/Markdown 渲染、Elm 架构       |
| LLM SDK | Anthropic Java SDK + OpenAI Java SDK    | 官方 SDK，流式优先                           |
| Web     | Spring Boot 3（内嵌 Tomcat）            | HTTP + WebSocket                             |
| MCP     | 官方 Java SDK（Model Context Protocol） | stdio / HTTP 传输                            |
| 配置    | SnakeYAML + Jackson                     | snake_case YAML → camelCase Java             |
| 测试    | JUnit 5                                 | 104 个测试用例                               |
| 日志    | SLF4J NOP                               | 静默，不干扰 TUI                             |

---

## 🚀 快速开始

### 环境要求

- **JDK 21+**（基于 OpenJDK 21 测试）
- **Maven 3.9+**
- 至少一个提供商的 API Key：`ANTHROPIC_API_KEY` 或 `OPENAI_API_KEY`

### 构建

```bash
mvn clean package -DskipTests
# → 生成可执行 Fat JAR：target/magiccode-1.0.0.jar
```

### 运行

**1. TUI 模式（默认，交互式终端）**

```bash
java -jar target/magiccode-1.0.0.jar
```

> 提示：TUI 依赖终端控制序列，请在真实终端或 IDEA 内置 Terminal 中运行，不要用 IDEA 的 Run 输出面板。

**2. Print 模式（非交互，用于脚本 / CI）**

```bash
java -jar target/magiccode-1.0.0.jar -p "解释这个项目的架构"
java -jar target/magiccode-1.0.0.jar -p "重构 main 函数" --output-format stream-json
```

**3. Remote 模式（Web UI）**

```bash
java -jar target/magiccode-1.0.0.jar --remote            # http://localhost:18888
java -jar target/magiccode-1.0.0.jar --remote=:9090      # 自定义端口
```

浏览器打开 `http://localhost:18888` 即可使用。

---

## ⚙️ 配置

MagicCode 合并三层配置（后者覆盖前者）：

| 优先级    | 路径                                | 用途          |
| --------- | ----------------------------------- | ------------- |
| 1（基础） | `~/.magiccode/config.yaml`          | 用户全局配置  |
| 2（覆盖） | `$CWD/.magiccode/config.yaml`       | 项目级配置    |
| 3（覆盖） | `$CWD/.magiccode/config.local.yaml` | 本地/私密覆盖 |

**示例 `~/.magiccode/config.yaml`**

```yaml
providers:
  - name: anthropic
    protocol: anthropic
    base_url: https://api.anthropic.com
    model: claude-3-5-sonnet-20241022
    api_key: ""                # 留空 → 读取环境变量 $ANTHROPIC_API_KEY

  - name: openai
    protocol: openai
    base_url: https://api.openai.com
    model: gpt-4o
    api_key: ""                # 留空 → 读取环境变量 $OPENAI_API_KEY

permission_mode: default       # default | accept_edits | plan | bypass

mcp_servers: []

hooks: []

sandbox:
  enabled: false
  auto_allow: true
  network_enabled: false

enable_coordinator_mode: false
```

**关键环境变量**

| 变量                                   | 用途                         |
| -------------------------------------- | ---------------------------- |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` | 提供商凭证                   |
| `MAGICCODE_CONFIG`                     | 显式指定配置路径（CLI 回退） |
| `SHELL`                                | 系统提示中检测用户 Shell     |
| `TMUX` / `ITERM_SESSION_ID`            | 自动检测团队执行后端         |

---

## 📂 项目结构

133 个源文件，分布在 20+ 个包中。核心包：

| 包名                                            | 职责                                                         |
| ----------------------------------------------- | ------------------------------------------------------------ |
| `com.magiccode`                                 | 入口类 `MagicCode`（CLI 解析 + 模式分发）                    |
| `com.magiccode.agent`                           | 智能体循环、`AgentEvent` 协议、`StreamingExecutor`           |
| `com.magiccode.llm`                             | `AnthropicClient`、`OpenAiClient`、`OpenAiCompatClient`、`StreamEvent` |
| `com.magiccode.tool` / `tool.impl`              | `Tool` 契约 + 12 个内置工具                                  |
| `com.magiccode.permission`                      | 9 层 `PermissionChecker` + YAML 规则引擎                     |
| `com.magiccode.compact`                         | 两层上下文压缩                                               |
| `com.magiccode.memory`                          | 持久化记忆系统                                               |
| `com.magiccode.skill`                           | 技能目录 / 执行器 / 安装器                                   |
| `com.magiccode.subagent` / `teams` / `worktree` | 多智能体协作与隔离                                           |
| `com.magiccode.tui` / `tui.tea`                 | TUI 模型 + 自研 TEA 框架                                     |
| `com.magiccode.remote`                          | Spring Boot HTTP + WebSocket 服务                            |
| `com.magiccode.hook`                            | 生命周期钩子引擎                                             |

---

## 🔍 技术亮点

- **MCP 工具延迟加载** — 延迟的 `shouldDefer()` Schema 配合 `ToolSearch` 发现机制，百级工具场景下工具描述 Token 占用减少约 85%。
- **统一 LLM 协议** — 密封接口 `StreamEvent` 吸收 Anthropic/OpenAI 差异，基于 `switch` 的穷举匹配由编译器保证完整。
- **两层渐进式上下文压缩** — 超大工具结果溢出磁盘 + LLM 结构化摘要，配合 `UsageAnchor` 校准与恢复附件（文件快照、活跃技能 SOP）。
- **9 层权限流水线** — 从安全命令白名单到 OS 沙箱，任一层均可拒绝。
- **虚拟线程并发** — `newVirtualThreadPerTaskExecutor()` 并行执行读工具，写/命令顺序执行；有界 `BlockingQueue` 提供天然背压。
- **Windows IME 安全的 TUI** — 使用 DSR 查询光标行号 + 绝对定位渲染，替代相对光标上移，解决中文输入时光标错乱与闪烁。
- **多智能体团队** — Fork/Spec/Teammate 派发、Git Worktree 隔离、`FileMailBox` 智能体间通信、Coordinator 协调者模式。
- **会话持久化** — JSONL 会话 + `compact_boundary` 记录，实现秒级、感知压缩状态的恢复。

---

## 🧪 测试

```bash
mvn test          # 104 个测试用例，0 失败
```

覆盖上下文压缩、钩子引擎、提供商配置、会话管理、技能安装器、`FileMailBox` 并发、工具结果预算等模块。

---

## ☁️ 部署（Remote / 云端）

Shaded JAR 是单一自包含产物。典型云端部署流程：

```bash
# 1. 本地构建
mvn clean package -DskipTests

# 2. 上传到服务器
scp target/magiccode-1.0.0.jar root@<服务器IP>:/root/
scp -r .magiccode root@<服务器IP>:/root/

# 3. 运行（前台验证）
ssh root@<服务器IP>
java -jar /root/magiccode-1.0.0.jar --remote

# 4. 用 systemd 后台常驻（崩溃重启 + 开机自启）
# /etc/systemd/system/magiccode.service
#   ExecStart=/usr/bin/java -jar /root/magiccode-1.0.0.jar --remote
#   Restart=always
systemctl daemon-reload && systemctl enable --now magiccode
```

在服务器防火墙放行所选端口（默认 `18888`），访问 `http://<服务器IP>:18888`。

---

## 📜 许可证

本项目采用 [MIT License](LICENSE)。

---

## 🙏 致谢
- 构建于官方 Anthropic & OpenAI Java SDK、Model Context Protocol Java SDK、Spring Boot、JLine、Mordant 之上。
