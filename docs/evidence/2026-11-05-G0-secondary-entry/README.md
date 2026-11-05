# G0：二级入口、独立确认与问题收藏视口

批次开始于 2026-11-05、归档于 2026-11-06。基线为
`89575f44299e0937c0960878591ea934f9b8ec6b`，对应 `CORE-01`、`UX-03`、
`UI-03/04/05` 的回归保全；完整任务和 G0 仍未关闭。

## 实际改动

- 有查询时直接显示现有的“问一问·选取本机出处”，不再必须先打开“更多”。
  只改善现有本地出处入口的可达性，发送资格、连接、来源和本次授权门槛保持。
- 备份返回测试跟随实际停靠编辑框，不再在记录列表里找它；夹具在装入草稿后
  才构建真实编辑控制器，使其按既有初始化逻辑绑定文字。保留正文、同一草稿
  引用、未知／旧回调拒收和无隐式读写的断言，没有改产品来掩盖空夹具。
- 零条草稿说明在真实“更多”后验证；未组合的空状态通过实际列表滚动寻找。
  正式任务与待确认草稿、未知读回／队列失败状态、稳定确认请求和原文均继续检查。
- 问题收藏弹层在短屏／两倍字体集成流程中持续触发文字测量，随后
  `ShadowTrace.endSection` 队列增长并 OOM。相同预算单例亦失败，活体栈落在
  `AndroidDialog.internalOnMeasure`。改为显式的安全屏幕／IME 视口和滚动高度后，
  同一完整用例通过。没有增加堆、超时或关闭 Trace。

## 历史诊断边界

一次性问题诊断已消费且禁用，其原测试由诊断脚本固定 hash。为了让正式产品
测试能适配停靠区，把原始授权测试逐字节归档于
`scripts/tests/fixtures/saved-question-diagnostic/`，来自基线 Git blob，保留
`2a39d75e…939f7` 原 SHA-256。脚本测试核对历史字节；正式测试只调整通用定位器，
四个授权测试正文保持一致。

`scripts/diagnose-saved-questions.py`、原 workflow、固定来源／预算／分支／nonce
均未改变，执行仍被禁用。归档不能授权诊断，当前新测试不会因归档而被 runtime
接纳。完整脚本类 5 项在 Windows 有 1 个符号链接权限错误，保留原错误；本次
涉及的固定来源／预算／禁用合约单项实际通过，不把全类记为 PASS。

## 实际测试

| 最终完整相关类 | 项数 | 失败／错误／跳过 |
| --- | ---: | --- |
| FullBackupPresentationTest | 7 | 0／0／0 |
| TaskProposalPresentationTest | 6 | 0／0／0 |
| QuestionKeywordPresentationTest | 3 | 0／0／0 |
| SelectedQuestionConsentPresentationTest | 4 | 0／0／0 |
| SavedQuestionsPresentationTest | 2 | 0／0／0 |
| CurrentImageSearchPresentationTest | 16 | 0／0／0 |
| LocalOriginalSearchPresentationTest | 14 | 0／0／0 |

准确合计 **52 项**。沟通中曾误报 54／53，归档求和检查发现后已修正；不改
原始 XML 或测试结果。原有方法清单不删改。OOM 及连带失败、单例失败、活体
线程栈、视口修复后的单例通过与最终 52 项均在 `local-results.zip`。

四项规定 wrapper 检查通过；AAR/domain:test 为 UP-TO-DATE，assemble、lint、
52 项 UI 实际执行。另跑当前完整 Windows UI：**413 项、27 失败、0 错误／跳过**，
退出 1。原始 XML 和日志在 `full-windows-ui.zip`；与前一批 Windows 39 失败比较，
本批恢复 12 项，剩余失败没有改记通过。

基线 Linux CI 为 **413 项、21 失败**，Host contracts 和 444 项／444 PNG Preview
通过。artifact 已校验并实际解析；不同环境与代码不能相减推算最终 Linux 结果。
原始数据在 `base-ci-xml.zip`，来源和失败清单在 `base-ci-status.json`、`report.json`。

## 真实模拟器范围

API 35 / Android 15、480 dpi：在合成查询中直接进入本机出处模式并返回、清空，
没有选连接、确认网络授权或发送。问题收藏分别在普通竖屏、两倍字体竖屏、
两倍字体横屏中，实际滚动至关闭按钮完整可见，触区至少 48×48 dp 且位于窗口内。
局部露出的文字不算通过；旧驱动的部分可见／滚出标题误判已按实际窗口修正。

截图：[查询入口](entry-with-query.png)、[本机出处页](local-source-entry.png)、
[普通弹层](history-normal.png)、[两倍字体](history-large.png)、
[横屏两倍字体](history-landscape-large.png)。没有保存、删除或使用任何问题，
原记录和任务不变；原字体／旋转设置恢复并读取确认。模拟器已退出，设备为空后
关闭本轮 adb。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，哈希见报告。

截图只核对交互和视口，二级页面仍有旧视觉和冗余说明，尚未完成 Skyglass 视觉
签收。没有实机中文 IME、TalkBack、OEM 提醒、16 KB 或帧时间验收，也没有启用
真实模型、OCR 或 ASR。剩余 UI 回归、数据设备专项和整体验收继续开放。
