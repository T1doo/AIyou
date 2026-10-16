# 1b543e6 精确 CI 失败与来源待办流程测试修正

父级于 2026-10-16 提供已独立核实的外部证据：
`1b543e6d0d933a9b16ee5fe21840dcdb6df4b685` 的 push run
`38852119053` / job `116815432735` 于 04:54:41 UTC 失败，203 项 Compose 测试中
1 项失败。`SourceTaskPresentationTest.narrowDoubleFontLiteralFlowRequiresSeparateConfirmationAndJumpsToExactOriginal`
在第 112 行关闭来源表单后直接查找“待办”文本节点，未找到。
APK 构建通过；instrumentation 编译与 lint 被跳过。reports artifact `12025988437`
保留至 10 月 30 日。证据：
https://github.com/T1doo/AIyou/actions/runs/38852119053/job/116815432735

源码诊断：关闭 `SourceTaskDialog` 调用 controller.close，只关闭该表单，仍处于
`OriginalReadingScreen`。`MainActivity` 的底部导航明确仅在 `!imeVisible && !reading`
时组成，因此这一步没有“待办”节点与当前路由规则一致；不是已证实的偶发故障，
也不据此把产品关闭行为判为缺陷。本地未复现 Compose 运行，诊断边界为给定失败
信息与确定的源码路径。

测试现先断言对话框已关闭、当前精确原文仍可读，再点击已有“返回列表”，之后断言
“待办”导航可见并点击。保留原有用户编辑、独立确认/取消、48dp、日期、AI_NONE、
待办来源跳转与来源改版拒绝的全部断言；没有 sleep、静默 fallback 或测试放宽。

旧 `246ff5b3490dcf354d281be69caf4bbe83c848ed` 的 run `38849634310` /
job `116807760945` 已成功 45 步，仅属于旧 SHA，不能覆盖上述新版本失败。
SDK 条款未批准，本地 Compose/Android 构建未运行；Actions 拒绝未绕过，也未用
其他访问路径取回报告。修正后的运行结果仍需新 SHA 的实际 CI，不能提前声称通过。

## 5c5b6b9 后续独立证据更正

父级后续补充已独立核实的结果：修正提交
`5c5b6b932df45ac3bb8c5f424a88507da9468a1e` 的 run `38855242541` /
job `116825118552`，Compose 第 18 步仍失败，instrumentation 编译及 lint 被跳过。
05:30:16 获取原日志返回 `BlobNotFound`，具体新失败用例、异常和堆栈尚未取得。
因此该修正 SHA 的状态应为**已知步骤失败、根因未定**，更正先前交付时的“CI 未知”；
不能算通过，也不能将新失败直接归为旧用例、偶发或产品缺陷。
证据：https://github.com/T1doo/AIyou/actions/runs/38855242541/job/116825118552

功能提交 `89949bb26dc581ba6b18408569622a341baf2586` 的精确结果仍未知，不能
从上述修正提交或旧 246ff5b 的运行推断。原证据文件与旧交付回执保持原样，
本次更正单独保存在 `f06-ci-step18-followup-20261016/` 证据目录。

当前可诊断边界：该步骤依次执行 `scripts/gradle.sh :app:testDebugUnitTest` 与
`python3 scripts/verify-test-results.py --ui`。仅凭步骤失败还不能定位到编译、
具体断言或验收脚本。本地没有对应 run 的日志/JUnit XML，`app/build` 不存在；
SDK 只读预检仍因缺少 ANDROID_HOME 失败，未安装或接受协议，未绕过 Actions。

继续诊断需要由可用来源提供以下至少一项，优先保留原件及 SHA/run 归属：

- job `116825118552` 第 18 步完整原始日志，包含异常、堆栈及失败命令。
- 同 run 的 `d01-reports` 中 `app/build/test-results/testDebugUnitTest/TEST-*.xml`；
  如果测试尚未执行而没有 XML，则必须取得上述步骤日志。
- 若只有 HTML，可提供同 artifact 的 `app/build/reports/tests/testDebugUnitTest/`。

在取得这些材料前，不修改生产代码、测试断言、用例门槛或截图原件，不额外重跑
已通过的无关 JVM 合约，也不把主机 691/149/123 结果用于解释这次 Compose 失败。

## 2026-10-16 后续定位与两处修正

父级提供的新证据已将上述 `5c5b6b932df45ac3bb8c5f424a88507da9468a1e`
失败定位到同一来源流程测试第 118 行：跳到待办页后直接查找离屏来源按钮，
LazyColumn 尚未 compose 对应项目，所以 `onNodeWithTag(...).performScrollTo()`
找不到该节点。此前 `BlobNotFound` 和“根因未知”是当时的证据状态，原记录保留；
现在不再将此项称为根因未知，也不据此归为偶发故障。

同批新证据确认保存回执修复
`c8e05bc0a721ac58c2f67346522644c8cc5d9caa` 的 run `38859223699` /
job `116837464919` 在 Android 编译时失败：`MainActivity.kt` 第 889 行的
`key` 未解析。P2 保存提示源码已独审闭合，仍不能因此声称 Android 构建通过。
证据：https://github.com/T1doo/AIyou/actions/runs/38859223699/job/116837464919

当前修正仅补 `androidx.compose.runtime.key` import，并让来源测试先在现有
`manual-task-list` 上调用 `performScrollToNode(hasTestTag(sourceTag))`，待项目
进入组合后再核对按钮可见、启用并点击。沿用 `ManualTaskPresentationTest`
已有模式，创建/日期/AI_NONE/精确原文相等/改版撤下旧原文等全部原断言保留，
没有 sleep、过滤测试或降低门槛。

独立只读源码审查通过；SDK 只读预检仍失败，本地 Android 构建及 Compose 未运行。
本轮没有重跑不覆盖这两处的旧主机合约，也不将 c8e05bc 的主机计数用于证明本批
编译/Compose 通过。正常开发分支推送使用既有有界 CI，不绕过 Actions 拒绝、
不安装 SDK 或接受协议、不修改 main。问题仅能由**新 SHA 的实际 Android 编译及
Compose 结果**关闭；本批仍为“源码修正完成，实际验证待完成”。原始日志未在
本地取得，本节精确定位来自父级提供的已核证据。
