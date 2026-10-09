# LabRun 工程

原生安卓实验执行 App + Windows 电脑端，共用一个平台无关的 Kotlin 核心。需求与决策见 `../docs/`。

| 模块 | 内容 |
| --- | --- |
| `core/` | 步骤文件解析校验、事件模型、运行引擎（幂等、锚点、采样、结束处置）、状态重放、时间线/测量表、导出包读写、HTML/Excel 报告、设计令牌。纯 JVM，无 Android 依赖 |
| `android/` | Jetpack Compose 原生应用：导入预览、运行、提醒（`setAlarmClock`）、录入、现象（拍照）、结束、导出 |
| `desktop/` | Compose Desktop：导入导出包、检索、测量表/时间线/现象/步骤、修订、导出 HTML/Excel/CSV |

## 构建与测试

环境：JDK 17（`JAVA_HOME`），Android SDK（`local.properties` 的 `sdk.dir`，用正斜杠写），Gradle Wrapper 8.13。

```bash
./gradlew :core:test :desktop:test          # 单元测试（固定案例、导入规则等）
./gradlew :android:assembleDebug            # 调试版 APK → android/build/outputs/apk/debug/
./gradlew :android:assembleRelease          # 正式版（签名 + R8）→ android/build/outputs/apk/release/
./gradlew :desktop:run                      # 运行电脑端
./gradlew :desktop:createDistributable      # 免安装目录 → desktop/build/compose/binaries/main/app/
./gradlew :desktop:packageMsi               # Windows 安装包（需联网下载 WiX；中文路径下须先 subst 成 ASCII 盘符再在该盘执行）
```

电脑端选项：`LABRUN_LIBRARY=<目录>` 指定实验库（默认 `%APPDATA%\LabRun\library`）；`--snapshot <目录>` 离屏渲染真实界面为 PNG（浅/深色各 4 个标签页），用于界面回归检查；命令行参数传入 `.labrun.zip` 会直接导入。

## 正式签名

`keystore/labrun-release.jks` + `keystore/keystore.properties`（含密码）。**务必另行备份**：丢失后无法发布能覆盖安装的更新，用户只能卸载重装（手机里的实验数据会随卸载丢失，需先导出）。不要把 keystore 目录发给他人。缺少该目录时 release 构建会退回调试签名。

## 注意

- 工作目录含中文：`gradle.properties` 里 **不要** 给 daemon 加 `-Dfile.encoding=UTF-8`（测试类会全部找不到）；`android.overridePathCheck=true` 是必须的。
- 数据只追加：手机上每个运行一个 `events.jsonl`，写后 `fsync`；断电留下的半行在下次加载时截掉并提示。
- 核心规则只有一份：手机执行、导出包校验、电脑展示、报告都调用 `RunState.replay`。改规则时先改 `core` 的测试。
