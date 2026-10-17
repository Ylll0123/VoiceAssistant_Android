# 本地智能语音助手（C3 课题）

Android 课程大作业 —— C 档「挑战级」课题 C3：本地智能语音助手。

## 项目简介

通过语音识别 + 规则引擎 + TTS 实现一个可对话、可执行本地功能的语音助手，
并配套一个独立的通知提醒中心伴生模块。

- **主课题**：C3 本地智能语音助手（语音识别 + 规则引擎 + TTS，多功能集成）
- **伴生模块**：P3 通知提醒中心（WorkManager 定时 + 通知直接操作）

## 当前功能（基础版）

- ✅ 语音输入：系统 `SpeechRecognizer`（中文，支持边说边出部分结果）
- ✅ 语音播报：系统 `TextToSpeech`，播报结束回调驱动连续对话
- ✅ 连续对话模式：一轮回答播完自动重新听（顶栏循环图标可开关）
- ✅ 规则引擎：意图注册表结构，内置 10 条规则
  （算术 / 时间 / 日期 / 笑话 / 自我介绍 / 帮助 / 天气占位 / 致谢 / 告别 / 问候）
- ✅ 文字输入兜底：识别服务不可用时仍可对话
- ✅ 权限与生命周期：录音权限动态申请，退后台自动停止识别与播报

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 语言 | Kotlin |
| 架构 | 单 Activity + 分层（speech / assistant / data / ui） |
| 本地存储 | 待接入 Room（伴生模块阶段） |
| 后台任务 | 待接入 WorkManager（伴生模块阶段） |
| 语音 | Android `SpeechRecognizer` / `TextToSpeech` |
| 构建 | Gradle 9.5 + AGP 9.3，minSdk 28 / targetSdk 37 |

## 目录结构

```
app/src/main/java/com/example/voiceassistant/
├── MainActivity.kt          # 界面与对话流程编排
├── speech/SpeechController.kt   # 语音识别 + 播报封装
├── assistant/
│   ├── AssistantEngine.kt   # 规则引擎（意图注册表）
│   ├── AssistantRules.kt    # 内置意图规则
│   ├── IntentRule.kt        # 规则接口
│   └── MathEvaluator.kt     # 四则运算求值
├── data/ChatMessage.kt      # 对话消息模型
└── ui/ChatAdapter.kt        # 聊天列表适配器
```

## 开发进度

- [x] 第 1 周：识别服务探针验证、项目骨架、规则引擎骨架
- [ ] 第 2 周：多轮上下文对话、模糊匹配容错引擎 + 单元测试（已起步：11 个引擎单测）
- [ ] 第 3 周：伴生模块（Room + WorkManager + 通知 Action）
- [ ] 第 4 周：TTS 与 UI 打磨、异常处理、文档与演示视频

## 环境要求

- Android Studio（本项目使用 AGP 9.3 / Gradle 9.5）
- JDK 17+（Gradle Daemon 配置为 JDK 24）
- 最低 SDK 28，目标 SDK 37

## 许可

课程作业，暂不开放许可。
