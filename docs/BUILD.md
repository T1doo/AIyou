# D00 构建与工具链

本文件为本轮补建，原 Starter ZIP 未提供。两份原始方案未修改。

## 固定版本

| 项目 | 版本 |
| --- | --- |
| AGP | 8.13.2 |
| Gradle Wrapper | 8.13 bin，SHA-256 校验开启 |
| 完整 JDK | Eclipse Temurin 17.0.17+10，Linux x86_64 下载固定 SHA-256 |
| Kotlin / Compose Compiler plugin | 2.3.21 / 2.3.21 |
| Compose BOM | 2025.12.00（Google Maven POM：UI 1.10.0、Material3 1.4.0） |
| Activity Compose | 1.12.2 |
| minSdk / compileSdk / targetSdk | 26 / 36 / 36 |
| Android Platform | android-36 revision 2 |
| Build Tools | 35.0.0 |
| Command-line tools（待批准安装） | 19.0，下载编号 13114758；支持 JDK 17 |
| JUnit | 4.13.2 |

版本目录为 `gradle/libs.versions.toml`。未使用依赖不提前加入，包括 KSP、
Room、SQLCipher、Hilt、AI 协议等，加入时另行复核。SDK 平台/构建工具固定在
app 构建脚本，传递依赖通过 Gradle lockfile 记录实际解析版本。

2026-10-09 复核官方资料：

- [AGP 8.13 兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)：
  Gradle 8.13、JDK 17、Build Tools 35.0.0；8.13.2 支持 Kotlin 2.3。
- [Kotlin Gradle 兼容矩阵](https://kotlinlang.org/docs/gradle-configure-project.html)：
  Kotlin 2.3.20–2.3.21 支持 Gradle 7.6.3–9.3.0、AGP 8.2.2–9.0.0。
- [Compose Compiler 配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)：
  Compose Compiler plugin 与 Kotlin 版本一致。
- [固定 BOM 的官方 Maven POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.12.00/compose-bom-2025.12.00.pom)。
- [官方 SDK 包元数据](https://dl.google.com/android/repository/repository2-3.xml)。

资料兼容结论不代替 `:app:checkDebugAarMetadata`；实际结果见 D00_STATUS。

## JDK 与 Gradle

Linux x86_64 无完整 JDK 时，在仓库执行：

```bash
scripts/setup-jdk.sh
scripts/gradle.sh --version
```

自备 JDK 时设置 `JAVA_HOME`。默认工具目录为仓库 `.toolchains/`（忽略提交）。
本次云环境使用外部目录 `/workspace/toolchains`，每次 shell 显式设置：

```bash
export AIYOU_TOOLCHAIN_ROOT=/workspace/toolchains
```

`scripts/gradle.sh` 复用环境已有 HTTP 代理和已有系统 Java truststore；保留 TLS
校验，不导入证书、不修改网络权限，不配置任何凭据。它要求完整 JDK 17，
并显式设置 Gradle 缓存目录，不依赖前一 shell 的 export。
Android 工具用户目录也放在工具链目录下，避免默认写入云环境的只读 home；
自动生成的 debug 签名文件仅留在该忽略目录，禁止提交。

JDK 官方下载及校验文件：

- [Temurin 17.0.17+10](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.17%2B10)
- [JDK SHA-256](https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.17%2B10/OpenJDK17U-jdk_x64_linux_hotspot_17.0.17_10.tar.gz.sha256.txt)
- [Gradle 8.13 分发 SHA-256](https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256)
- [Gradle Wrapper JAR SHA-256](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256)

## Android SDK 许可与安装

必须由开发者阅读并接受 [Android SDK License](https://developer.android.com/studio/terms)。
本次初始环境未发现 SDK 或已有接受记录。许可状态与批准记录须以
`docs/D00_STATUS.md` 和会话中的明确授权为准；不要把这份安装说明当作已批准。

本 D00 仅需 command-line tools 19.0、Platform 36 r2、Build Tools 35.0.0；
三者的官方 package metadata 均引用 `android-sdk-license`，不需要预览协议、
NDK、模拟器镜像或 Google Play 服务协议。

仅在开发者明确接受协议后下载 command-line tools：

```bash
export ANDROID_HOME="$PWD/.toolchains/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools" .toolchains/downloads
curl --fail --location --max-time 300 \
  https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
  --output .toolchains/downloads/cmdline-tools-19.zip
printf '%s  %s\n' 5fdcc763663eefb86a5b8879697aa6088b041e70 \
  .toolchains/downloads/cmdline-tools-19.zip | sha1sum --check -
unzip -q .toolchains/downloads/cmdline-tools-19.zip -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/19.0"
export JAVA_HOME="$PWD/.toolchains/jdk-17.0.17+10"
"$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" \
  --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;35.0.0'
```

在上述针对性安装的许可提示中由开发者手动确认；不要使用 `yes` 批量接受。
如 sdkmanager 要求新协议或内容变更，停止并再次展示具体协议。已安装的 SDK
无需重装；设置 `ANDROID_HOME` 后用 `python3 scripts/check-sdk.py` 检查平台
revision、构建工具和接受记录。该检查不执行下载或许可接受，也不把文件存在
当作独立的法律授权证明。本环境采用已有默认代理时，sdkmanager 也需指定
`--proxy=http --proxy_host=proxy --proxy_port=8080`；不扩大网络规则。

## 执行检查

```bash
scripts/gradle.sh :core:domain:test
python3 scripts/verify-test-results.py
python3 scripts/check-sdk.py
scripts/gradle.sh :app:checkDebugAarMetadata
scripts/gradle.sh :app:assembleDebug
scripts/gradle.sh :app:lintDebug
```

首次冻结/主动更新依赖时使用 `--write-locks`；通常构建不改 lockfile。
单测位于 `core/domain/src/test/`，要求非零测试、无失败和无跳过；app 没有
单测源时不把其 `testDebugUnitTest NO-SOURCE` 当作测试通过。

- APK：`app/build/outputs/apk/debug/app-debug.apk`（只有成功构建后存在）
- 测试 XML：`core/domain/build/test-results/test/`
- Lint：`app/build/reports/lint-results-debug.html` / `.xml`

CI 使用固定 SHA 的官方 actions，ubuntu-24.04、完整 Temurin JDK 17；运行真实
domain 单测、metadata、assembleDebug、lintDebug 并保存报告与 debug APK。
SDK 检查依赖 runner 已安装且已有开发者接受记录的固定包；缺失时明确失败，
不会替开发者接受新协议。GitHub runner 镜像会变，需保留实际运行日志，不把
workflow 文件存在当作 CI 已通过。没有配置 release 签名或发布步骤。

真机启动、TalkBack、200% 字体、输入法、性能、OEM 通知与 16 KB 设备验证
均单列，不能由编译成功替代。
