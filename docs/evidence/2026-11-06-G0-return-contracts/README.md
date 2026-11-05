# G0：按当前原生入口验证搜索、来源与编辑返回

起始源码为 `39225e99d018e4ccfb420014c046a8077dfb38fa`，工作分支为
`codex/g2-native-skyglass-20261105`。本批属于 `CORE-01`、`UX-03`、
`UI-03/04/05` 的回归核对；G0 与这些完整任务仍未签收。

## 改动与保全要求

本批只调整正式界面测试及其操作辅助函数，没有改产品、存储、主题、动效、
权限或协议。原有测试方法保留，断言继续核对实际控制器与原始数据。

- 日期条件在筛选弹层编辑并明确应用之后再查询，继续验证关键词删减与确认、
  日期保留、精确原文回跳及没有隐式读取。
- 宽屏居中测试测量现有搜索输入框；空页面验证“还没有回忆”，不恢复已移除
  的“0 条本机记录”说明文字。
- 此刻页的输入框已经停靠在独立滚动区，不再假定它在最近记录列表里。
  测试通过“更多”打开真实处理范围、通过“回到输入”关闭弹层，再展开编辑。
  保留两倍字体、横屏、焦点、同一草稿、无写入和记录为空等断言。
- 噜噜按当前小型 48 dp 入口验证，保留偏好尚未读取／关闭时不展示、主动打开
  与关闭、实际栅格资源含透明度、紧凑标题高度和文字不溢出的检查。
- 来源事实从真实“更多”展开入口；正式任务先展开自身详情再返回来源。
  候选与正式对象的分别确认、纠正／撤权及版本准确性断言保留。
- 历史原文通过当前可点击整张记录进入，不再寻找已移除的独立“阅读全文”按钮。
  该用例在 Windows 随后的真实加密草稿读取中失败，不能记为通过。

## 本机专项

`targeted-results.zip` 保存原始 JUnit XML 和完整 wrapper 日志。
`targeted-counts.json` 由 XML 生成：30 项，29 通过、1 失败、0 错误／跳过。
6 个完整相关类共 29 项全部通过；另一个只执行单方法的类不算完整类通过。

失败为 `OriginalEditRecoveryPresentationTest.aiNoneHistoryRouteCreatesOnlyAnExplicitNewVersionAndKeepsNoInferencePolicy`：
`EncryptedOriginalEditStore.syncDirectory` 打开 Windows 的 `D:\AndroidDev\temp`
目录时报 `AccessDeniedException`。保留失败，没有更换假的存储实现、跳过同步、
删除断言或调整通过标准。此限制需要合适的 Linux／Android 实际执行证据。

完整当前结果、规定四项检查、方法清单、产物哈希及提交范围见 `report.json`。

完整 Windows 集实际执行 413 项：394 通过、19 失败、0 错误／跳过，退出 1。
相对起始源码的 27 个失败，8 个原有失败方法现已通过，没有新增失败方法。
原始 XML 与日志保存在 `full-windows-results.zip`。历史版本方法仍在失败清单中，
只是从旧入口定位错误继续走到目录同步错误，不能算第 9 项恢复通过。
剩余失败属于加密原文目录同步、应用访问／显示偏好写入与读回相关路径；本批
没有更改这些实现。当前 SHA 的 Linux 结果仍需实际执行，不把 Windows 的失败
直接标为 Linux 或 Android 已通过。

四项规定检查单独执行并退出 0：AAR、assemble 与 domain 为 UP-TO-DATE，
lintDebug 使用缓存分析与报告完成。检查了真实 domain XML：78 项，0 失败／
错误／跳过；没有说成本次重新执行 78 项。lint 报告没有 Error／Fatal／Warning。
`required-checks.zip` 保存原始检查日志、domain XML 与 lint 报告。
`method-inventory.json` 核对本批 8 个测试／辅助文件，原有 39 个测试方法不变。

## CI、设备与验收边界

本批查询 GitHub 时，起始提交没有关联 Actions 运行；现有 workflow 为 active，
但本机 `gh workflow run` 没有有效登录，返回 401。自动审批拒绝将 Git 凭据转用
到工作流派发，该命令没有执行；未创建认证、未输出密钥、未改 workflow。
本批通过正常开发分支提交／推送后核对新提交的实际运行状态。

产品源码没有变化，本批不重启模拟器、不新增或继承设备通过结论。先前截图和
Preview 是各自源码与场景的证据；不能代替本次未完成的 Linux、真实中文 IME、
TalkBack、OEM 提醒、16 KB 或性能验收。完整二级视觉、备份恢复设备闭环与真实
模型／OCR／ASR 能力继续开放。
