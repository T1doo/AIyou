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
