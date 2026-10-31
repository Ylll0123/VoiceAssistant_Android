# 本地智能语音助手（C3 课题）

Android 课程大作业 —— C 档「挑战级」课题 C3：本地智能语音助手。

## 项目简介

通过语音识别 + 规则引擎 + TTS 实现一个可对话、可执行本地功能的语音助手，
并配套一个独立的通知提醒中心伴生模块。

- **主课题**：C3 本地智能语音助手（语音识别 + 规则引擎 + TTS，多功能集成）
- **伴生模块**：P3 通知提醒中心（WorkManager 定时 + 通知直接操作）

## 当前功能

### 一、语音交互

- ✅ 语音输入：系统 `SpeechRecognizer`（中文，支持边说边出部分结果）
- ✅ 语音播报：系统 `TextToSpeech`，播报结束回调驱动连续对话
- ✅ 连续对话模式：一轮回答播完自动重新听（顶栏循环图标可开关）
- ✅ 文字输入兜底：识别服务不可用时仍可对话
- ✅ 权限与生命周期：录音权限动态申请，退后台自动停止识别与播报

### 二、对话能力（规则引擎）

- ✅ 意图注册表结构：内置 16 条规则，按优先级依次匹配
- ✅ **多轮上下文**：`DialogContext` 记住上一轮意图与槽位，60 秒内有效
  - 「帮我算 25 乘以 4」→「再加 5」→ 基于上一次结果继续算
  - 「明天天气怎么样」→「那后天呢」→ 追问补全
  - 猜数字游戏：目标值存在槽位里，逐轮提示大了/小了
- ✅ **模糊匹配容错**：`FuzzyMatcher` 三层兜底（精确 → 拼音首字母 → 编辑距离）
  - 短句才允许对 3 字关键词容错，避免「怎么样」误命中「怎么用」
- ✅ 能力覆盖：算术 / 时间 / 世界时间 / 日期 / 日期推算 / 年龄 / 单位换算 /
  随机决策 / 猜数字 / 一句话 / 笑话 / 自我介绍 / 帮助 / 天气占位 / 致谢 / 告别 / 问候

### 三、多页面

- ✅ 底部导航三页：**对话** / **技能** / **设置**
  - 对话页：消息气泡列表（用户/助手）、状态栏、麦克风与发送
  - 技能页：按分类展示全部能力 + 可点击的示例说法，点击后自动跳回对话页执行
  - 设置页：语音播报、连续对话开关，上下文调试信息，清空上下文与对话
- ✅ 对话状态由 `ChatSession` 持有，切页面不丢消息

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 语言 | Kotlin |
| 架构 | 单 Activity + 三 Fragment（speech / assistant / data / ui 分层） |
| 本地存储 | 待接入 Room（伴生模块阶段） |
| 后台任务 | 待接入 WorkManager（伴生模块阶段） |
| 语音 | Android `SpeechRecognizer` / `TextToSpeech` |
| 测试 | JUnit4（36 个单测，覆盖规则引擎、模糊匹配、上下文、技能目录） |
| 构建 | Gradle 9.5 + AGP 9.3，minSdk 28 / targetSdk 37 |

## 目录结构

```
app/src/main/java/com/example/voiceassistant/
├── MainActivity.kt              # 底部导航 + 语音流程编排 + 跨页广播
├── speech/SpeechController.kt   # 语音识别 + 播报封装
├── assistant/
│   ├── AssistantEngine.kt       # 规则引擎（顺序匹配 + 上下文写回）
│   ├── AssistantRules.kt        # 内置意图规则（16 条）
│   ├── IntentRule.kt            # 规则接口 + 请求/回复模型
│   ├── DialogContext.kt         # 多轮上下文（意图 + 槽位 + 60s 超时）
│   ├── FuzzyMatcher.kt          # 模糊匹配（归一化 / 拼音 / 编辑距离）
│   ├── SkillCatalog.kt          # 技能目录（技能页数据源）
│   └── MathEvaluator.kt         # 四则运算求值
├── data/
│   ├── ChatMessage.kt           # 对话消息模型
│   └── ChatSession.kt           # 会话状态（切页面不丢消息）
└── ui/
    ├── ChatFragment.kt          # 对话页
    ├── SkillsFragment.kt        # 技能页
    ├── SettingsFragment.kt      # 设置页
    └── ChatAdapter.kt           # 聊天列表适配器
```

## 开发进度

- [x] 第 1 周：识别服务探针验证、项目骨架、规则引擎骨架
- [x] 第 2 周：多轮上下文对话、模糊匹配容错引擎、多页面改造 + 单测（36 个）
- [ ] 第 3 周：伴生模块（Room + WorkManager + 通知 Action）
- [ ] 第 4 周：TTS 与 UI 打磨、异常处理、文档与演示视频

## 环境要求

- Android Studio（本项目使用 AGP 9.3 / Gradle 9.5）
- JDK 17+（Gradle Daemon 配置为 JDK 24）
- 最低 SDK 28，目标 SDK 37

## 许可

课程作业，暂不开放许可。
