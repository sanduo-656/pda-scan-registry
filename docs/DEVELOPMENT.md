# 开发与构建

适用版本：0.1.13。面向希望自行编译、修改或验证本项目的开发者。

## 1. 环境

- JDK 17。
- Android SDK：Android 35 平台和对应构建工具；设备测试需要 platform-tools。
- Gradle 8.14 和 Android Gradle Plugin 8.11.1，使用仓库的 Wrapper。
- 最低 Android 8.0（API 26），编译 / 目标 API 35。

在 Android Studio 中打开仓库根目录，或设置 `JAVA_HOME`、`ANDROID_HOME` 后使用命令行。也可在不提交的 `local.properties` 中配置 `sdk.dir`。首次构建需联网下载 Gradle 和依赖。

## 2. 准备打印 SDK

向德佟 / 道臻厂商取得 `LPAPI-2026-10-21-R.jar`，放到：

```text
app/libs/LPAPI-2026-10-21-R.jar
```

SDK 不随源码仓库分发；没有该文件，构建会明确报告缺失路径。对应文件校验值见 [第三方组件说明](../THIRD_PARTY_NOTICES.md)。请使用上述版本，其他版本的 API 和回调兼容性需自行验证。当前仓库没有发布签名密钥。

## 3. 编译与 Lint

Windows PowerShell：

```powershell
.\gradlew.bat assembleStandardDebug lintStandardDebug
```

macOS / Linux：

```bash
./gradlew assembleStandardDebug lintStandardDebug
```

APK：`app/build/outputs/apk/standard/debug/app-standard-debug.apk`。

依赖已缓存时可以追加 `--offline`。调试包由当前开发环境的调试密钥签名；另一台计算机编译的 APK 通常无法直接覆盖维护者发布的测试包。更新安装必须保留应用 ID 并使用同一签名，勿通过卸载绕过签名冲突而丢失数据。

正式部署应自行配置并妥善保存固定的发布签名，勿提交 JKS、口令或 `local.properties`。当前 GitHub 版本为公开测试版，不是应用商店正式版。

## 4. 隔离设备验证

```powershell
.\gradlew.bat assembleVerificationDebug assembleVerificationDebugAndroidTest
adb install -r app/build/outputs/apk/verification/debug/app-verification-debug.apk
adb install -r app/build/outputs/apk/androidTest/verification/debug/app-verification-debug-androidTest.apk
adb shell am instrument -w -r com.sanduo.pdaregistry.verification.test/com.sanduo.pdaregistry.RegistryTestRunner
```

普通应用 ID：`com.sanduo.pdaregistry`。验证应用 ID：`com.sanduo.pdaregistry.verification`。测试运行器拒绝在普通应用中执行，数据库测试使用临时数据库。界面测试会在验证应用内创建演示项目，不连接普通应用的数据。

已有 2026-11-17 的 MV-IDP5204 / Android 11 实机验证记录：37 项通过、0 项失败，涵盖数据库升级、事务与并发、去重、编号、编辑删除、导入映射、盘点、表格格式、页面交互、SDK 标签绘制和异常提示音。使用者另已现场确认 MV-IDP5204 + 德佟 DP30S 蓝牙连接及实际打印正常，标签规格 30 × 20 mm。公开结果见 [验证记录](verification/README.md)。其他硬件型号需自行验证。

## 5. 代码入口

| 文件 | 责任 |
| --- | --- |
| `MainActivity.java` | 页面、扫码状态、项目切换、导入导出及打印操作 |
| `RegistryStore.java` | SQLite、数据库迁移、事务、去重、编号和盘点状态 |
| `ScannerBridge.java` | 扫码广播收发；默认 Action / 数据字段 |
| `TableImporter.java` / `TableExporter.java` | XLSX / CSV 解析、映射、校验和输出 |
| `LabelTemplate.java` / `LabelView.java` | 30 × 20 mm 模板和预览 |
| `PrintRequest.java` / `PrinterController.java` | 范围快照、打印队列、回调和失败恢复 |
| `WarningSound.java` | 离线异常提示音及生命周期 |
| `RegistryTestRunner.java` | 独立验证版的设备测试 |

应用为原生 Java / Android UI，数据保存在本机 SQLite。无需 AndroidX、Excel 运行库或海康直接读码库；应用未声明互联网权限。

## 6. 手册维护

操作手册源文件是 `docs/USER_MANUAL.md`，截图放在 `docs/images/`。PDF 由同一份 Markdown 生成：

```powershell
uv run --with reportlab --with pillow python scripts/build-manual.py
```

默认输出 `docs/PDA扫码登记-操作手册.pdf`。脚本优先使用 Windows 宋体；其他系统可通过 `--font /path/to/chinese.ttf` 指定支持中文的 TrueType 字体。生成后应渲染检查中文、表格和截图。

## 7. 文件约定

`design/PDA.pen` 为 Pencil 原始设计；`docs/templates/` 为可公开使用的空白导入模板。构建产物和历史测试资料在本机 `dist/`，不进入源码历史。对外安装包、校验文件和手册通过 GitHub Releases 提供。
