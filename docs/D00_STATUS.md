# D00 实际记录

时间：2026-10-09 UTC。状态：**BLOCKED / 待 SDK 许可批准，D00 尚未完成**。
D01 未开始；独立源码审查尚未执行，不能以本轮实现者检查替代独审。

## 已核实的起点

- 本次选择的 AIyou 云环境已启动，仓库实际位于 `/workspace/AIyou`。
- 初始分支 `work`，工作树干净；本地 HEAD 和远端 main 均为
  `7383e502418f5f9fc0c540ef2072742359343d40`。
- 仅有 README、`.gitattributes` 和两份原始方案，无 Starter ZIP 或工程。
  两份方案已完整读取（DOCX 使用 XML 提取全部 581 段文本）；未修改原方案。
- 原工作区及仓库中未发现适用的 AGENTS.md 或项目 skills，受保护的
  `/workspace/.agents` 与 `.codex` 为空；本轮 AGENTS.md 是补建规则。
- 默认网络实际可访问 Google SDK metadata、Google Maven、Maven Central、
  Gradle 分发及其 GitHub 跳转，没有扩大网络策略。
- 初始 Java 是 21 运行时，缺 `javac`；未发现 Android SDK、sdkmanager、adb、
  已接受 SDK 许可或 `/dev/kvm`。没有假定旧实例工具/文件共享。

## 已交付工程

- 原生 Kotlin / Compose，三个可选择的主入口：此刻、回忆、待办；设置对话框
  占位，选择状态用 rememberSaveable 保存。页面清楚标明功能正在准备中。
- 没有记录存储、搜索、AI、任务调度或后端；不宣称“已保存在本机”或
  “提醒已安排”。没有请求联网、麦克风、相册、通知等权限。
- 纯 Kotlin model 与 domain 最小模块，默认 `LOCAL_ONLY`；严格记录策略
  与远端授权判定有真实 JUnit 断言。原文、派生与任务的持久化设计仍属后续。
- 中性几何导航标记和自绘手账图标，无未授权角色、图片或字体。
- 固定工具链、校验过的 Wrapper、依赖锁文件、工作区说明、构建文档、CI。
  没有签名材料、真实密钥或个人记录进入新增文件。
- 备份与设备迁移排除规则已显式配置；其 OEM 实际行为未验证。

## 工具链与许可

实际安装并验证完整 Temurin JDK 17.0.17+10（java + javac）和 Gradle 8.13。
AGP/Kotlin 插件已经成功解析并用于纯 Kotlin 编译；Android 工具链的全流程
仍需 SDK 后验证。选择依据和精确版本见 [BUILD.md](BUILD.md)。

JDK tarball SHA-256：
`992f96e7995075ac7636bb1a8de52b0c61d71ed3137fafc979ab96b4ab78dd75`

Gradle 8.13 bin SHA-256：
`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`

Wrapper JAR SHA-256：
`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`

尚未获准接受 [Google Android SDK License](https://developer.android.com/studio/terms)。
只读取了官方包元数据与协议文本，**未下载或安装 SDK 包、未写接受记录**。
待批准的最小包：command-line tools 19.0、Platform android-36 revision 2、
Build Tools 35.0.0；均引用 `android-sdk-license`。不需要预览、NDK 或模拟器协议。

## 实际执行结果

下表是本地实际结果，不能把 CI 配置文件存在当作 CI 已运行。

| 命令/检查 | 结果 | 证据或限制 |
| --- | --- | --- |
| 初始 Git 与默认网络探测 | PASS | main SHA 与起点一致，默认网络可用 |
| JDK / Gradle / Wrapper 校验 | PASS | 下载与生成的校验值匹配官方值，java/javac 均 17 |
| `scripts/gradle.sh :core:domain:test --write-locks` | PASS | 3 tests，0 failures，0 errors，0 skipped；测试任务实际 executed |
| `python3 scripts/verify-test-results.py` | PASS | 非零测试 XML；未以 NO-SOURCE 充当成功 |
| `scripts/gradle.sh :app:dependencies --configuration debugRuntimeClasspath --write-locks` | PASS | 解析成功，无 FAILED 依赖标记，生成 app lockfile；不代替 AAR metadata 检查 |
| `python3 scripts/check-sdk.py` | FAIL | SDK 未安装，ANDROID_HOME 未设置，无接受记录 |
| `scripts/gradle.sh :app:checkDebugAarMetadata` | FAIL | 实际任务失败于 SDK location not found |
| `scripts/gradle.sh :app:assembleDebug` | FAIL | 实际命令失败于 SDK location not found |
| `scripts/gradle.sh :app:lintDebug` | FAIL | 实际命令失败于 SDK location not found；没有 lint 通过证据 |
| Shell 语法、XML 解析、workflow YAML 解析、`git diff --check` | PASS | 静态文件检查，不替代 Android lint 或 UI 验证 |
| 干净源码复现 Android 构建 | NOT RUN | 同一 SDK 许可阻塞 |
| GitHub Actions 执行 | NOT RUN | 推送后由远端执行；本地未声称通过 |
| 模拟器/真机启动、截图、TalkBack、200% 字体、320 dp/横屏 | NOT RUN | 无设备环境；未完成视觉验收 |
| 真机性能、输入法/触感、OEM 通知与 16 KB 设备验证 | NOT RUN | 无设备，且相关功能尚未实现 |
| 独立源码审查 | NOT RUN | D00 未达到完整验收门槛，交由独立审查者后续执行 |

测试结果：`core/domain/build/test-results/test/TEST-com.t1doo.aiyou.domain.RemoteProcessingRuleTest.xml`。
HTML 报告：`core/domain/build/reports/tests/test/index.html`。
APK 预期路径：`app/build/outputs/apk/debug/app-debug.apk`，**目前不存在**。

## 故障与证据保全

初始 wrapper URL 校验未使用默认代理，失败；用 curl 核实官方分发 URL 与
校验值后从已校验的 Gradle 生成 wrapper，最终保留 URL 校验与分发 SHA-256。
实际 wrapper 下载通过已有默认代理、既有系统 Java truststore 完成；没有
导入 CA 或关闭 TLS。

初次 AGP 初始化因默认只读 home 的 `.android` 目录失败，改用可写的
`ANDROID_USER_HOME`。随后发现 Gradle 8.13 没有 `failOnNoDiscoveredTests`
属性，移除它并使用实际 XML 的独立检查脚本。一次旧 `ANDROID_SDK_HOME`
尝试被 AGP 拒绝后已撤回；最终只使用 `ANDROID_USER_HOME`。每次修改均针对
实际错误，旧日志保留；未删锁、未杀未知进程或绕过权限拒绝。

AGP analytics 仍报告默认 home 的 settings 不可写，但纯 Kotlin 测试通过；
没有为了可选工具指标改变 home 权限或上传配置。

原始日志仅保留本地忽略目录 `/workspace/AIyou/.local-evidence/`，包括
domain-test.log、domain-test-retry1/2/3.log、app-dependencies.log、aar-metadata.log、
assemble-debug.log、lint-debug.log。工具下载与缓存位于 `/workspace/toolchains/`，
不提交 Git。公开仓库只记录脱敏摘要与固定校验值。

## 下一步与交付边界

最小剩余批准动作：开发者阅读上面的具体 SDK 协议后，明确回复接受该协议，
并授权安装上述三个 D00 必需包。获准后才安装，核验实际接受记录，重跑
metadata / assemble / test / lint，并从干净源码复现，填写 APK 及真实结果。
随后冻结源码，独立审查通过后再进入 D01。

本轮在 `codex/d00-native-bootstrap-20261009` 保存提交并正常推送；准确交付 SHA、
远端核验与未提交/未推送差异以最终报告为准。未更改 main、强推、部署或上架。

原始方案 SHA-256（用于复核未修改）：

- Master Plan：`a9c647e0555cd57a4511e58506a4ad8516d5c329ada7bc5329cdd61839a54e9b`
- Product Architecture：`2d28784231148af980512bd87115bb1c1d243be0c3825f61b441934f76727c94`
