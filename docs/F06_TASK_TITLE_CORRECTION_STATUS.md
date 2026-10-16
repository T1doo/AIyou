# F06：正式待办标题纠错

基于本地、远端一致且干净的 `e5399c2971f4e7331e6f5a58f3ca218b89f01b6d`，
开发分支 `codex/f06-task-title-correction-20261016`。根任务是唯一集成与构建所有者；
协作代理分别负责数据实现/专项测试与只读独审。原 Master Plan 与 DOCX §13.1
要求更新明确目标 ID 和标题、由用户确认；现有正式待办可改日期、完成、取消，却
无法纠正标题。本批补齐这个实际使用缺口，不重复提醒模型，不修改两份原设计。

未完成正式待办的“修改标题”先显示原任务、原日期及版本；用户编辑后核对原、新
标题，再单独确认保存。取消确认保留本次输入，关闭放弃未保存修改。输入、撤销
历史及确认请求不写 Android SavedState。标题使用既有 240 code point、严格 UTF-16
及控制字符规则；外侧空白规范化后未变不写库，日期文字保持字面文本，不推断时间。
同名任务仍按 ID/版本隔离，旧会话、返回修改前的确认、旧路由回调不能提交新编辑器。

沿用同一加密 `manual_tasks` 行事务与 CAS，只改变 title/task.version/updatedAt。
身份、原创建时间、时区、日期/时刻/偏移、状态、原文来源回执以及 reminder 的
token、scheduleVersion、phase、outbox 完整保留。改名不调用调度服务，不取消或
重新安排提醒，也不修改原文或已确认草稿。来源文案改为“由原文摘录创建”，当前
标题可为后来人工修改；来源定位仍读取原始认证摘录，创建回执重试返回当前任务。

认证操作 `RENAME` 沿用既有 v2/v3/v4 封装，无新 schema、表、密钥域或依赖。
仅现存 OPEN 任务可更新；缺失/终态/旧版本拒绝，不 UPSERT。精确请求重放核对
mutation、operation、expectedVersion、新标题、原请求时间，不重复推进版本或重加密。
未知 COMMIT/读回不显示成功，保留原请求对象重试；待办写入互斥，未知期间提醒
ACK/前台核对继续延后，避免覆盖原重放身份。确定版本冲突保留输入、要求重新读取
与明确重选，不自动换用新版本。

前置 e5399c2 的父级精确 CI 及原件独审见 [提醒模型状态](F06_REMINDER_MODEL_STATUS.md)
最新附记，不计为本批新 SHA 的证据。最终适用测试、原始 XML/输入指纹、失败记录、
逐文件来源与独审归档在 `/workspace/aiyou-evidence/f06-task-title-correction-20261016/`；
实际结果收尾记录。本机 SDK 预检仍缺开发者已接受许可的 SDK，未接受或安装条款。

继续保持 LOCAL_ONLY、BYOK、无后台。未增加权限或网络、未接受新 SDK 条款、没有
真实模型/费用/凭据/安全权限/部署。系统提醒平台仍未接入：Android 13+ 普通通知
需 `POST_NOTIFICATIONS`；面向 Android 12+ 的精确闹钟需适用的精确闹钟授权路径，
本产品将来应优先评估 `SCHEDULE_EXACT_ALARM` 特殊授权，不能擅自假定具有受限
`USE_EXACT_ALARM` 资格。重启恢复如需接收启动广播，还涉及 `RECEIVE_BOOT_COMPLETED`
声明；均不在本批授权与实现内。来源为 [Android 通知](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
及 [闹钟](https://developer.android.com/develop/background-work/services/alarms)官方说明。

本批本地 Android AAR/APK/lint、Compose、Preview、instrumentation 与视觉 NOT RUN，
无本地新 APK，新精确 CI 仍 UNKNOWN。设备通知/AlarmManager、重启/时区/真实送达、
SAF、全新安装恢复及密钥重连、OEM、TalkBack、16 KB、性能未验。完整 D07 仍 OPEN；
旧 94/96 SQLite 初始化/退出超时根因继续 UNKNOWN。

最终适用检查（最终产品源码，无放宽断言）：

- PASS：`python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --offline --rerun-tasks`，首轮完整 797/797（AI 178、领域 68、SQL/控制器 551），零失败/错误/跳过；新增 SQL 14、实际控制器 11 各 actual 1。
- PASS：`python3 scripts/verify-ai-protocol-results.py`，完整源码方法清单与 76 份实际 XML 一致；三份执行前输入指纹为 `496efd30de90c7b363917c687ac648f3e5ee7745118fc2417212e83a7077a3be`。队列前复制原件，队列后同一指纹再核通过。
- PASS：`python3 scripts/jvm-gradle.py -p checks/record-queue test --offline --rerun-tasks`，149/149；`python3 scripts/verify-test-results.py --queue` 核实际 XML 和必需方法，零失败/错误/跳过。
- PASS：Python 123/123、Token 生成检查、HTTP 依赖校验、`git diff --check`；新增三项 Compose 方法与必需清单静态比对一致，仅为源码门。
- PASS：独立只读完整源码审查；独审直接解析 76 XML 的 797 唯一实际方法、新增 25 方法各一次及每份 XML 字节哈希，并独立重算 328 项执行输入与三份捕获/报告一致。队列由根实际执行与核验，不混称由独审验过。
- FAIL（环境前置）：SDK 预检，原始 `sdk-check.log` 保留。上述本地 Android/Compose/设备门仍 NOT RUN，新 SHA 精确 CI 仍 UNKNOWN，无本批本地 APK。

本批首轮完整主机即通过，没有删除或隐去失败尝试；前置 e5399c2 的两项初始
失败及旧 94/96 未知超时未被当前绿色结果替换。`host-1/`、`queue-1/` 保存原始
XML、执行输入、方法/哈希清单，`commands-results.json`、`provenance.json` 及
`independent-review.json` 标明执行、来源与证据边界。最终源码逐文件副本、Git SHA/
远端/未提交及未推送差异与可用 Library 回执随交付证据包记录。
