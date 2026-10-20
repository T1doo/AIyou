# D00 构建与工具链

本文件为补建说明，无 Starter ZIP；两份原始方案未修改。2026-10-20 根据真实
lint 报告更新 Gradle 补丁版本，保留严格门槛。原工具链与历史失败见 D00_STATUS.md。

## 固定版本

| 项目 | 版本 |
| --- | --- |
| AGP | 9.4.1，内置 Kotlin；移除外部 kotlin-android |
| Gradle Wrapper | 9.8.2 bin，下载 SHA-256 校验 |
| 完整 JDK | Temurin 17.0.17+10 |
| Kotlin / Compose Compiler | 2.4.21 / 2.4.21 |
| Compose BOM | 2026.09.00（UI 1.12.1、Material3 1.4.0） |
| Activity Compose / JUnit | 1.13.0 / 4.13.2 |
| min / compile / target SDK | 26 / 37.0（minor 0）/ 37 |
| Android Platform / Build Tools | android-37.0 revision 2 / 36.0.0 |

版本目录 gradle/libs.versions.toml；lockfile 来自实际解析，普通构建不改锁。
不宣称所有 Android 配置全量锁定。未使用的 KSP/Room/Hilt/AI 等不提前加入。

## 官方依据及限制

- [AGP 9.4 兼容表](https://developer.android.com/build/releases/agp-9-4-0-release-notes)：Gradle 至少9.6.0、Build Tools36.0.0、JDK17，支持API37。
- [内置 Kotlin 迁移](https://developer.android.com/build/migrate-to-built-in-kotlin)：移除 module/root/catalog 的 kotlin-android；内置 JVM target 默认来自 compileOptions，Java/Kotlin 均17。
- [提升 KGP](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-to-a-higher-kgp-version)：根项目固定 JVM/Compose plugins 使实际解析选择2.4.21，以编译和锁核验。
- [Kotlin 矩阵](https://kotlinlang.org/docs/gradle-configure-project.html)：2.4.21完整支持到Gradle9.7.0/AGP9.3.1。官方允许更新版本但可能有弃用/功能限制；当前9.8.2/9.4.1超出完整支持表，不能称矩阵全覆盖，必须保留实测证据。
- [固定 BOM POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom)：实际UI1.12.1 AAR要求compile37、minor0、AGP>=9.1.0。
- [SDK包元数据](https://dl.google.com/android/repository/repository2-3.xml)：稳定包为platforms;android-37.0 r2及build-tools;36.0.0，引用android-sdk-license。

资料不代替 checkDebugAarMetadata/assemble/lint；本次只将 Gradle9.8.1 升至9.8.2。

### 2026-10-20 补丁核验

[CI39351479100](https://github.com/T1doo/AIyou/actions/runs/39351479100) 对
`b85b15013fd2f1287cc8d14a38673b8420a7927e` 的原始日志确认唯一 lint 错误为
`AndroidGradlePluginVersion`：9.8.1 有9.8.2 更新；不是 D05 源码 lint 错误。
原日志 SHA-256：`262cdf212fd7e62ca9430292cd1815e1c053c39e59074ebbb5c3db7c110a17f2`。
不关闭检测、不加 baseline；AGP/Kotlin/SDK/依赖锁及产品处理策略不变。

[官方发行说明](https://docs.gradle.org/9.8.2/release-notes.html) 说明9.8.2修复
虚拟平台版本对齐的误报；9.8.1中已有安全修复。
[源码差异](https://github.com/gradle/gradle/compare/v9.8.1...v9.8.2) 还更新
可选 Develocity 插件；本项目不使用 Build Scan，不启用它或接受其服务条款。
9.8.1/9.8.2 的 [LICENSE](https://github.com/gradle/gradle/blob/v9.8.2/LICENSE)
和 [NOTICE](https://github.com/gradle/gradle/blob/v9.8.2/packaging/distributions-full/src/toplevel/NOTICE)
分别逐字相同（SHA-256：
`0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594` /
`c2de5fd9adc7ccc4e1382a0e1f38ec6e14f8ca6e28d3c20289fe80353b019aa1`）。
另对校验通过的两版 bin 分发包实际 `LICENSE`/`NOTICE` 逐字比较：
分发 `LICENSE`（含第三方组件清单，16077字节）均为
`f4cff4b801084da2f1df33afead47f475910b441feea6a5183097e8deb97f0fa`；
分发 `NOTICE`（868字节）与上列 NOTICE 相同。源码根 LICENSE 哈希不能代替
分发许可证核验。未发现本补丁新增需接受的协议；未安装或接受 Android SDK 协议。
官方9.8.2 Wrapper JAR校验值与现有文件完全相同，保留原JAR及所有校验入口。
协议测试输入指纹已包含 Wrapper properties/JAR，补丁后由实际测试重新捕获、核验，
不复用9.8.1测试指纹。


## JDK 与 Wrapper

实例若已配置 `/workspace/toolchains`，每次 shell 显式设置：

```bash
export AIYOU_TOOLCHAIN_ROOT=/workspace/toolchains
scripts/gradle.sh --version
```

本次新实例无 `/workspace/toolchains`；通过现有脚本校验并使用忽略的 `.toolchains/`，
不设置上述变量。Linux x86_64 缺完整 JDK 时执行 scripts/setup-jdk.sh，或设置 JAVA_HOME。
默认目录为忽略的 .toolchains/。脚本只复用既有代理和系统 Java truststore，
不导入 CA、关闭 TLS 或设置凭据。debug 签名材料只保存在忽略目录。

| 文件 | SHA-256 |
| --- | --- |
| Temurin17.0.17+10 tarball | 992f96e7995075ac7636bb1a8de52b0c61d71ed3137fafc979ab96b4ab78dd75 |
| Gradle9.8.2 bin | 21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607 |
| Wrapper9.8.2 JAR | 3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98 |

[JDK release](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.17%2B10)、[Gradle校验](https://services.gradle.org/distributions/gradle-9.8.2-bin.zip.sha256)、[Wrapper校验](https://services.gradle.org/distributions/gradle-9.8.2-wrapper.jar.sha256)。

## SDK 许可

本云环境无 SDK，也未获准接受或安装。不要把本文当作批准。已有 SDK 时设置
ANDROID_HOME/ANDROID_SDK_ROOT 并运行只读的 scripts/check-sdk.py，检查精确
revision和当前条款既有接受哈希；不写记录、不安装。哈希匹配不是独立法律授权证明。
条款 canonical SHA-1为24333f8a63b6825ea9c5514f83c2829b004d1fee：由官方XML按
Android repository TrimStringAdapter规范化后计算，不是原始XML文本哈希。

缺包或匹配记录就停止，开发者需先阅读[具体 SDK 协议](https://developer.android.com/studio/terms)，
明确接受并批准Platform37.0 r2/Build Tools36.0.0；CLI安装方案还须单独核定版本
与校验值。禁止yes、自动下载、默认批准。未批准时可继续纯Kotlin单测与只读分析。

## 执行与产物

```bash
scripts/gradle.sh :core:domain:test
python3 scripts/verify-test-results.py
python3 scripts/check-sdk.py
scripts/gradle.sh :app:checkDebugAarMetadata
scripts/gradle.sh :app:assembleDebug
scripts/gradle.sh :app:lintDebug
```

APK：app/build/outputs/apk/debug/app-debug.apk（成功后才存在）。单测XML在
core/domain/build/test-results/test/，必须非零、无失败/跳过；NO-SOURCE不是测试通过。
Lint HTML/XML在app/build/reports/，完整文本也打印到CI日志。

CI固定actions SHA与JDK17，仅复用runner既有SDK。预检缺包/匹配记录即失败，
SDK自动下载关闭，无安装/接受步骤。assemble成功后记录APK SHA-256并保全，
即使后续lint失败也留调试产物；lint失败仍使job失败。无release签名/发布。
设备启动、TalkBack、字体、性能、OEM通知、16KB设备验证为NOT RUN，编译不能替代。
