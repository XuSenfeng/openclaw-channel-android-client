# OpenClaw Android Channel Client

该仓库提供 Android 客户端，用于连接 Python 虚拟消息平台并与 OpenClaw 进行实时对话。

## 仓库职责

1. 提供移动端聊天 UI 与会话管理
2. 支持 WebSocket 双向消息通信
3. 支持语音输入、语音播报与流式回复显示

## 构建要求

1. Android Studio
2. Android SDK 34
3. JDK 17

## 本地构建

```bash
./gradlew assembleDebug
```

APK 输出位置：

`app/build/outputs/apk/debug/app-debug.apk`

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 连接说明

1. 手机与服务端主机需在同一局域网
2. App 中 WebSocket 地址填写服务端地址（例如 `ws://192.168.1.20:8765`）
3. 不要填写 `ws://127.0.0.1:8765`（会指向手机本机）

## 关联仓库

1. OpenClaw Fork: https://github.com/XuSenfeng/openclaw
2. Python Server: https://github.com/XuSenfeng/open-claw-python-channel-server
3. Integration Docs: https://github.com/XuSenfeng/openclaw-android-demo

## AI 生成声明

本项目文档与部分代码在开发过程中使用了 AI 辅助生成与修改。
所有 AI 产出内容均经过人工审查、调试与验证后再提交。
