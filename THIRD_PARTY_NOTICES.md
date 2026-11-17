# 第三方组件说明

本项目自行编写的源码、文档和合成提示音按根目录 MIT License 提供。第三方组件保留各自许可，不因本项目采用 MIT 而改变。

| 组件 | 用途 | 处理方式 |
| --- | --- | --- |
| Gradle Wrapper 8.14 | 下载并启动 Gradle | 仓库包含 Wrapper JAR 和启动脚本，适用 Apache License 2.0；见 `gradle/wrapper/LICENSE` |
| Android Gradle Plugin 8.11.1 | Android 构建 | 由 Gradle 从配置的仓库下载，适用其上游许可 |
| Android SDK | 编译和设备工具 | 由开发者通过 Android 官方工具安装，适用 Android SDK 自身条款 |
| 德佟 / 道臻 LPAPI Android SDK | DP30S 蓝牙连接、标签绘制和打印 | 厂商专有 SDK；完整 SDK、示例和原始文档不上传本仓库。开发者自行向厂商取得并放入 `app/libs/` |
| ReportLab、Pillow | 可选的操作手册 PDF 生成 | 仅用于文档构建，不打包进 Android 应用；适用各自上游许可 |

公开测试 APK 的打印功能包含 LPAPI 运行代码。LPAPI 的权利归原厂商，MIT 授权不涵盖该组件；涉及 SDK 的使用和再分发应遵循取得 SDK 时的厂商条款。

当前构建使用 `LPAPI-2026-10-21-R.jar`，SHA-256：

```text
39c7b7eddfea8035e5cc3e12875e946dc99a73ffb02eb3222da1c46e0c078b5a
```

原始 `SDK/` 目录保留在维护者本机，已加入 Git 忽略。扫码接入使用 Android 系统广播或焦点输入，不将海康读码 SDK 打包进应用。
