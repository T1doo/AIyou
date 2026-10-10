# D00 实际记录

更新：2026-10-10 UTC。状态：**D00 工程、严格 CI 与独立源码审查通过，待验收；D01 未开始**。
本云环境仍无 Android SDK 或许可批准，未安装 SDK、未接受协议。
GitHub 托管 runner 的既有 SDK 已实际通过只读包版本/接受记录检查；其执行
结果与本地许可阻塞分开记录，不再把远端结果写作未知。

## 2026-10-10 已核实的托管 CI

通过既有 GitHub 只读连接取得 [run 37986477589](https://github.com/T1doo/AIyou/actions/runs/37986477589)
的 job 114009616810 完整日志，head 为
`eaeaf36d6483ce6610ec7fff19550077252d4277`：

| 检查 | 结果 | 实际证据 |
| --- | --- | --- |
| Wrapper 校验、JDK 配置 | PASS | 固定 Wrapper 校验匹配，Temurin 17.0.17+10 |
| `:core:domain:test` 与 XML 检查 | PASS | 6 个任务 executed；3 tests，0 failures/errors/skipped |
| 既有 SDK 只读检查 | PASS | android-36 r2、Build Tools 35.0.0、既有接受记录；workflow 无安装/接受步骤 |
| `:app:checkDebugAarMetadata` | PASS | 1 个任务 executed |
| `:app:assembleDebug` | PASS | 39 tasks，36 executed、3 up-to-date |
| `:app:lintDebug` | FAIL | 9 errors；原日志只展示首个 NewApi（API 27 属性处于 min26 通用样式） |
| 测试/lint 报告上传 | PASS | artifact 11644045434；ZIP SHA-256 a8d00ff5aa68d41106ec6afab318e50037ad2652cc22acd5d3b7807243d6d205 |
| APK 上传 | NOT RUN | 原 workflow 在 lint 失败后跳过；编译成功不等于已交付 APK |

报告下载工具返回文件引用，但在默认网络下载 ZIP 时 HTTP 403；没有绕过拒绝。
日志保存在 `/workspace/d00-ci-lint-review-eaeaf36/job.log`。首个已证实错误在
`12cf836b233c263d0898a200259dbc60a0737d26` 修复：共用基础样式，API 27
导航栏属性放入 values-v27，保留 min26 和严格 lint。CI 补充完整 lint 文本
输出及报告保全，APK 仅在 assemble 成功时上传，即使后续 lint 失败也保全
调试产物；lint 失败仍使 job 失败。

[run 38017473944](https://github.com/T1doo/AIyou/actions/runs/38017473944)
实测单测/XML/SDK/metadata/assemble 均 PASS；NewApi 已消失，lint 仍 FAIL，
余下 8 项均为版本建议经 warningsAsErrors 提升：OldTargetApi（target36）、
AndroidGradlePluginVersion（Gradle、AGP）、GradleDependency（BOM、Activity）
及三处 Kotlin NewerVersionAvailable。完整日志保存在上述目录的
`job-12cf836.log`。严格门槛未关闭或降级，未用 baseline 隐藏问题。

APK 保全本次 PASS：[artifact 11655944929](https://github.com/T1doo/AIyou/actions/runs/38017473944/artifacts/11655944929)，
runner 内路径 `app/build/outputs/apk/debug/app-debug.apk`；ZIP SHA-256 为
`6eb7a30fac339249352eaade9e644139811f7096a25118814e59f54e8cedb829`。
这是旧工具链的调试 APK，不是 lint 已通过的验收产物，本云环境尚未取得该文件。

固定 12cf836 已由独审在 archive 隔离快照复核：资源继承/版本限定与 CI
严格门槛静态 PASS；JVM 6 tasks executed、3 tests 且零失败/错误/跳过，
原方案未变，三处差异未发现新的高信度缺陷；Android 构建由 CI 提供证据，
独审本地未运行。证据 `/workspace/d00-readonly-review-12cf836`。

随后按完整报告准备 AGP9.4.1/Gradle9.8.1/Kotlin2.4.21/BOM2026.09/Activity1.13
固定升级，采用官方内置 Kotlin 路线，compile37.0/target37/BuildTools36。
官方 SDK metadata 与该次固定 runner 镜像清单列有 Platform37.0 r2 和
BuildTools36；仍须实际只读预检，不假定文档等于安装或许可状态。预检新增
匹配 canonical 条款哈希，缺包/记录即失败，自动 SDK 下载仍关闭。
该组合超出 Kotlin 完整支持表，具体官方依据和限制见 BUILD.md；不得声称
矩阵全覆盖。实际纯 Kotlin 单测/编译及 debug 编译、运行时和编译器依赖解析
PASS；XML 为3 tests零失败/错误/跳过，新Wrapper与分发均核官方SHA-256。
升级后的 Android CI 与最终固定源码独审已完成，结果见下一节；设备验收未执行。

本轮本地 `:core:domain:test --rerun-tasks` 再次 PASS：6 tasks executed，
3 tests、0 failures/errors/skipped；资源 XML、workflow YAML 和 diff 检查 PASS。
本地 Android 检查仍受 SDK 许可阻塞，未用托管结果冒充本地执行。

## 升级后的实际验收证据

固定源码：`def6db4828a64a8d2bef1236debf36c091455249`。
[run 38018373718](https://github.com/T1doo/AIyou/actions/runs/38018373718)
已完成且 conclusion=success；job114113672093 完整日志保存在
`/workspace/d00-ci-lint-review-eaeaf36/job-def6db4.log`。

| 检查 | 结果 | 实际证据与范围 |
| --- | --- | --- |
| Wrapper / JDK | PASS | 新 Wrapper 官方 SHA 匹配，Temurin17.0.17+10 |
| `:core:domain:test` / XML | PASS | 6 tasks executed；3 tests，零 failures/errors/skipped |
| 既有 SDK 包/许可只读预检 | PASS | Platform37.0 r2、BuildTools36.0.0、匹配 canonical 接受记录；未安装/接受 |
| `:app:checkDebugAarMetadata` | PASS | 实际任务成功，12s |
| `:app:assembleDebug` | PASS | 38 tasks、35 executed/3 up-to-date，48s；compileDebugKotlin 实际 executed |
| `:app:lintDebug` | PASS | 29 tasks、10 executed/19 up-to-date，23s；No issues found；无 disable/baseline/降级 |
| 报告及 APK 上传 | PASS | artifact11657556256 /11657481372 均未过期 |
| 干净源码 Android 构建 | PASS（托管 CI 范围） | fresh checkout 固定 SHA，无复用 build 输出、无缓存恢复步骤；runner SDK 为既有安装 |
| 本云环境 Android 构建 | NOT RUN（当前升级 SHA） | 无 SDK、未批准安装；2026-10-09 原版本的实际失败保留在历史表 |
| 当前 APK 下载到本云环境 | FAIL | 新 artifact 文件引用下载仍 HTTP403；没有绕过、没有本地 APK |
| 设备启动/UI/无障碍/16KB/性能 | NOT RUN | 没有设备证据；CI 编译不能代替 |

交付 APK 为 [artifact11657481372](https://github.com/T1doo/AIyou/actions/runs/38018373718/artifacts/11657481372)，
runner 内实际路径 `app/build/outputs/apk/debug/app-debug.apk`。
APK SHA-256（直接对 APK 计算）：
`ad23185c3da598202606f18691761441c9d430cf9e45e0a22241c85cc5990d5f`。
上传 ZIP SHA-256（与 APK 哈希不同）：
`3bf36953168e29ff6080d9c79618d26da81764a8dceb8d945ba15a3b83c0e1e9`。
artifact 过期时间为 2026-10-24 UTC；没有向仓库提交 APK 或 debug 签名私钥。
报告 ZIP SHA-256：`0cc4c30e123ed3a2b20d1797b34d58b64853214773ae332806535877f0afb496`。

升级消除了原9项lint：1项API限定错误、8项版本建议。固定版本、内置Kotlin迁移、
实际锁范围和超出Kotlin完整支持表的限制见 BUILD.md。通过这些 D00 检查不能
声称所有工具功能、真机行为或后续持久化/AI功能兼容。SDK 许可未在本云环境
接受，未扩大网络、配置凭据、调用真实模型或发布。

固定 def6db4 的最终独立只读审查 PASS（限定范围）：全新 archive 实际 JVM
6 tasks executed /3 tests，零失败、错误、跳过；四项配置无 write-locks 的
严格锁解析 PASS、无 FAILED，锁文件验证后未变。Wrapper 官方校验、XML/YAML/
Shell 配置与 canonical 许可哈希复核 PASS；独审重新核对上述精确 SHA 的
CI 全量成功日志及 APK 哈希。此前 P2 锁遗漏、P3 隐含 rg、NewApi 已关闭，
新源码/配置未发现新的高信度缺陷。证据
`/workspace/d00-readonly-review-def6db4/REVIEW_RESULT.md`。
独审指出的 P3 旧许可/工具链文档描述在本状态文档收尾时修订；仅此文档变化，
其余源码与配置保持受审 def6db4 字节。最后记录提交的精确 SHA/远端状态以
交付报告为准；审查不代替设备检查，也不证明所有变体锁与工具功能兼容。

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

## 2026-10-09 工具链与许可（历史）

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

## 2026-10-09 本地实际执行结果（保留历史）

下表是本地实际结果，不能把 CI 配置文件存在当作 CI 已运行。

| 命令/检查 | 结果 | 证据或限制 |
| --- | --- | --- |
| 初始 Git 与默认网络探测 | PASS | main SHA 与起点一致，默认网络可用 |
| JDK / Gradle / Wrapper 校验 | PASS | 下载与生成的校验值匹配官方值，java/javac 均 17 |
| `scripts/gradle.sh :core:domain:test --write-locks` | PASS | 3 tests，0 failures，0 errors，0 skipped；测试任务实际 executed |
| `python3 scripts/verify-test-results.py` | PASS | 非零测试 XML；未以 NO-SOURCE 充当成功 |
| 从已提交的干净源码快照重跑 `:core:domain:test` 及 XML 验证 | PASS | git archive 源码，无复制 build 输出；3 tests，0 failures/errors/skipped，6 个任务实际 executed；复用已校验工具与下载缓存 |
| `scripts/gradle.sh :app:dependencies --configuration debugRuntimeClasspath --write-locks` | PASS | 解析成功，无 FAILED 依赖标记，生成 app lockfile；不代替 AAR metadata 检查 |
| `python3 scripts/check-sdk.py` | FAIL | SDK 未安装，ANDROID_HOME 未设置，无接受记录 |
| `scripts/gradle.sh :app:checkDebugAarMetadata` | FAIL（BLOCKED） | 实际任务失败于 SDK location not found |
| `scripts/gradle.sh :app:assembleDebug` | FAIL（BLOCKED） | 实际命令失败于 SDK location not found |
| `scripts/gradle.sh :app:lintDebug` | FAIL（BLOCKED） | 实际命令失败于 SDK location not found；没有 lint 通过证据 |
| Shell 语法、XML 解析、workflow YAML 解析、`git diff --check` | PASS | 静态文件检查，不替代 Android lint 或 UI 验证 |
| 干净源码复现 Android 构建 | NOT RUN | 同一 SDK 许可阻塞 |
| GitHub Actions 执行结果查询 | FAIL | `gh run list` 只读查询返回 Forbidden；远端执行结果未知，未声称通过，也未修改凭据/网络 |
| 模拟器/真机启动、截图、TalkBack、200% 字体、320 dp/横屏 | NOT RUN | 无设备环境；未完成视觉验收 |
| 真机性能、输入法/触感、OEM 通知与 16 KB 设备验证 | NOT RUN | 无设备，且相关功能尚未实现 |
| 独立只读源码审查与修复复核 | PASS（限定范围） | 起点 bf45623 的 P2/P3 已在固定修复 SHA bd3eead 上独立复核关闭；不代替 SDK/Android/设备门槛 |

测试结果：`core/domain/build/test-results/test/TEST-com.t1doo.aiyou.domain.RemoteProcessingRuleTest.xml`。
HTML 报告：`core/domain/build/reports/tests/test/index.html`。
本云环境 APK 预期路径：`app/build/outputs/apk/debug/app-debug.apk`，本地未构建。

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
干净源码测试日志为 `clean-snapshot-test.log`，源码快照目录为
`/workspace/d00-clean-248b873`；其中没有 SDK，也不是干净工具缓存构建。

## 2026-10-09 独立审查与修复（历史）

按父任务要求，独立审查者只读审查固定提交
`bf456231102d9b95fbbaf255392bdd10dfd38b74`，完整读取两方案、581 段 DOCX
及关系链接、工作区规则、全部源码、构建/锁/Wrapper/CI/备份配置及可达 Git
历史；产品只由实现者写入。审查证据隔离在
`/workspace/d00-readonly-review-bf45623`，没有新增外围报告。

- **P2：debug 编译依赖缺锁。** app 原锁文件只覆盖 debugRuntimeClasspath。
  隔离严格模式验证明确报告 debugCompileClasspath 无 lock state。已实际解析
  并补锁 debugCompileClasspath、kotlinBuildToolsApiClasspath 与
  kotlinCompilerPluginClasspathDebug；严格模式的编译依赖验证转为 PASS。
  SDK 获准后仍需从实际 assemble/lint 收集其余解析配置，未宣称全量 Android
  构建锁或验证已经完成。
- **P3：工具脚本依赖未声明的 rg。** 独审实际移除 PATH 中 rg，观察到返回 2
  并误报 JDK 不符。已将版本检查改为 Bash 内置正则；保留标准 Unix 工具、
  Python 和完整 JDK 17 的无 rg PATH 下，`scripts/gradle.sh --version` 转为 PASS。

修复源码提交：`bd3eeadaf5cc5ea5b5d45b2a4d624352aa8adaf2`。相对审查起点仅
修改 app 锁文件、工具脚本和构建说明；产品行为及原方案未改变。实现者修复后
重跑 `:core:domain:test --rerun-tasks`，6 任务实际 executed；XML 为 3 tests、
0 failures/errors/skipped。根验证证据位于 `/workspace/d00-root-evidence-bf45623`。

独立审查者从该固定修复 SHA 再建全新源码快照，证据隔离在
`/workspace/d00-readonly-review-bd3eead`。无 `--write-locks` 的严格模式下，上述
三个配置均解析 PASS，无 FAILED 标记，原运行时锁版本未改变；无 rg 环境的
脚本返回 0；JVM 单测再次有 6 个实际 executed 任务，3 testcase 且无失败、
错误、跳过。三个修复文件与固定提交字节一致，两处原缺陷在当前范围内关闭。
后续仅更新本状态记录，不改变已被独审验证的源码；D00 仍未完成。

独审未发现 CI 中有 SDK 安装或许可接受步骤；AGP 自动下载关闭。Wrapper、
Gradle 和 JDK 的固定值与官方校验文件一致；原方案与起点字节一致。
`LOCAL_ONLY`、严格记录策略、同意判定、三入口、中性素材及备份排除符合
当前 D00 范围；38 个 tracked 文件和 40 个可达历史 blob 的秘密模式扫描零
匹配，不能将模式扫描声称为绝对无秘密。Android 平台上的备份行为、未来
同意仓库和持久化记录的边界仍需对应阶段的真实验证。

本云环境 SDK 始终未安装、协议未接受；没有真实模型调用或网络/凭据/证书变更。

## 当前下一步与交付边界

当前可先用托管 runner 的既有 SDK 做只读预检和完整构建；缺包或匹配记录就
失败，不安装或接受。本云环境若要完整本地构建，最小批准动作是开发者阅读
[具体 SDK 协议](https://developer.android.com/studio/terms)，明确接受并授权安装
当前 Platform37.0 revision2 / BuildTools36.0.0；CLI 安装版本/校验仍须另核，
历史的 CLI19/android36/35 三包候选不能当作当前批准。获准前仅执行不依赖
本地 SDK 的工作。最终源码冻结并独审，记录实际 CI/APK/设备限制；D01 未开始。

源码快照提交 `248b8735b0019ac3566d24f6a2e68b3d8d165e19` 已正常推送，
`git ls-remote` 核实远端分支 SHA 一致；显式 fetch 后 ahead/behind 为 `0/0`。
之后仅另行修复上述构建锁与工具脚本，并对固定修复提交独立复核。本轮在
`codex/d00-native-bootstrap-20261009` 保存提交并正常推送；准确交付 SHA、
远端核验与未提交/未推送差异以最终报告为准。未更改 main、强推、部署或上架。

原始方案 SHA-256（用于复核未修改）：

- Master Plan：`a9c647e0555cd57a4511e58506a4ad8516d5c329ada7bc5329cdd61839a54e9b`
- Product Architecture：`2d28784231148af980512bd87115bb1c1d243be0c3825f61b441934f76727c94`
