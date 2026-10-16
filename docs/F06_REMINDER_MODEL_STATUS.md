# F06/D07：明确确认的提醒模型与持久命令

从实际本地/远端一致且干净的 `7e8c2168e524ce4123046739727e6eafb32ac881`
建立 `codex/f06-reminder-model-20261016`。默认网络仅常规 Git 核对；main 仍为
`7383e502418f5f9fc0c540ef2072742359343d40`。当前任务是唯一开发所有者，协作代理
按文件分工实现模型、数据和测试，另有独立只读审查，不恢复旧实例写入。
已读两份原主方案与既有日期、来源待办、草稿、加密包实现；不重复已有日期解析、
CAS、草稿确认功能，原主方案/DOCX 保持不改。

## 用户行为与当前边界

只有 OPEN 正式待办的未来明确日期、时刻、IANA 时区和原偏移可单独确认提醒。
仅日期、标题中的时间表达、保存草稿、确认提案、导入包均不会自动安排。用户另行
选择精确/大约并点击真实确认按钮，才能加密保存提醒计划；撤销也有独立确认。
清单分别显示精确/大约与真实模型状态，不静默把拒绝的精确提醒改为大约。

当前生产 driver 固定未接入，前台处理只保存 `PLATFORM_UNAVAILABLE`，不会调用
通知、AlarmManager、Worker、申请系统权限或发起后台任务。界面明确说明保存确认
与系统安排、通知送达不同；适配器确认安排和接纳触发的模型状态也不证明送达。
本批不新增任何 Android 权限、SDK/协议接受、依赖、凭据、远端模型、后台或费用。
真正的通知/闹钟适配器、权限申请、重启/时区变更后的系统重建和设备演练仍未实现。
完整 D07 仍 OPEN，设备通知/AlarmManager、OEM、省电、16 KB、性能、TalkBack 均 NOT RUN。

## 身份、原子撤销与命令回执

沿用 `manual_tasks` 同行版本 CAS 和独立 AEAD；新增 nullable reminder 在 v4 封装
内认证，不增加 Room 表或迁移。旧 v1–v3 继续原 AAD/密钥域/严格 EOF；无提醒的
普通新写仍采用原 v2/v3。v4 有完整旧快照/操作/时间/mutation 的请求 proof，
未知 COMMIT/读回保留同一个不可变请求，精确重放不重新加代次或重复加密。

稳定 reminder ID 与独立 scheduleVersion 绑定精确任务身份；外部 token 不带标题。
确认、撤销、日期 SET/POSTPONE/CLEAR、完成和取消任务，在同一事务写新版本与
有界命令 outbox。日期改变撤回旧确认，新时刻需再次单独确认。旧命令变为针对
精确旧 token 的 CANCEL，保留到驱动明确确认撤销；旧 cancel 不宽泛删除新版本。
最多 32 个命令，超限整体拒绝，保留为终态操作预留的撤销容量，不截断命令。
超限的确定未写拒绝释放 UI pending 后可重新核对；未知存储结果继续保留原请求。

前台 dispatcher 每轮最多处理 16 项，新的 SCHEDULE 优先，避免被失败旧撤销永久
挡住；调度前核对当前真实任务/命令，并检查平台、通知授权、渠道及精确授权。
拒绝/未申请/未接入/失败均保留准确状态；未知异常不消费命令，取消信号继续传播。
旧 schedule ACK、撤销 ACK 只能改变精确现存命令，不得启用新代次或终态任务。
手工待办与提醒写入口互斥；手工状态/日期提交结果未知时不消费提醒 ACK，保护
原请求的精确重放身份。提醒写回执确认之前也不推进 dispatcher。

触发只在事务内读取现存 OPEN 任务、匹配 reminder ID/scheduleVersion、明确时刻、
已安排状态和当下权限；早到、重复、旧版本、缺失、已完成/取消/撤销均拒绝。
接纳仅 UPDATE 为单次 `TRIGGER_ACCEPTED`，不 UPSERT、不重建任务/原文，也不发送
通知。源原文回收或永久删除继续保留独立正式待办，未擅自改成连带删除规则。

活动 capture/import 显式剥离 reminder；开放 Markdown/JSONL 和加密包仍不导出或
恢复提醒/outbox。恢复创建新身份副本和 AI_NONE，不重新连接密钥、不恢复远端授权、
不调用 driver 或执行草稿。原有加密、版本、永久删除/重放及迟到 AI 安全门保持。

## 验证与证据归属

仅使用合成 LOCAL_ONLY/AI_NONE 数据。实际 SQL/AEAD/控制器与领域测试和受控平台
端口分别验证，不能用 Fake driver 或 Compose JVM 代替原生设备。
主机数据编译与新增领域初轮通过；最终主机/队列/Python、失败尝试、XML、执行前
输入指纹及逐文件来源清单归档至
`/workspace/aiyou-evidence/f06-reminder-model-20261016/`，最终结果在收尾追加。

前置 7e8c216 的精确 CI 由父级日志独立核实，见 F08 状态附记；不覆盖本批。
本机无开发者已接受许可的 SDK，新增 Compose 三测本地 NOT RUN，保留到必需 XML
方法清单；正常推送仅触发现有有界 CI，不另 dispatch、不自行查此前拒绝的 Actions。
此前 host 原件在当前环境能读取的归档会原样保留并尝试可用 Library 出口；此前
其他环境原件缺口单列，不将资料交接作为功能前置，不以当前测试覆盖旧未知失败。

最终适用检查：

- PASS：`python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --offline --rerun-tasks`，772/772（AI 178、领域 68、SQL/控制器 526），零失败/错误/跳过。新增 52 项实跑（领域 22、SQL 14、控制器 14、导出恢复 2）。
- PASS：`python3 scripts/verify-ai-protocol-results.py`，完整方法清单与 74 份实际 XML 精确一致；输入 SHA-256 `8e3e3c8b6e9635b17c64369c5f9d42602afb01f9589f0e2ba9c028eee1f70d7b`。队列前保全 XML/输入/报告，队列后再核同一指纹。
- PASS：`python3 scripts/jvm-gradle.py -p checks/record-queue test --offline --rerun-tasks`，149/149，`python3 scripts/verify-test-results.py --queue` 核实际 XML，零失败/错误/跳过。
- PASS：Python 123/123、Token 与 HTTP 依赖校验、三项新 Compose 方法的必需清单静态比对、`git diff --check`。
- PASS：独立只读源码审查及 host-2 原始 XML、全部方法清单、74 XML 哈希、执行前和当前源码指纹核验。独审未运行构建/网络；队列数由本任务实际执行和核验，不混称独审已核。
- FAIL（环境前置）：SDK 预检缺开发者已接受许可的 SDK；未安装或接受协议。
- NOT RUN：本机 Android AAR/APK/lint、Compose 三项、Preview、instrumentation，以及真实系统权限、通知、AlarmManager、全新安装恢复/重连密钥/提醒演练。没有本批本地 APK；本批新 SHA 的精确 CI 仍 UNKNOWN。

`host-1.log` 和 `host-1/` 保留首轮 772 项中的两项失败与完整当时源码：损坏注入
先撞原版本 guard，修正测试 version/mutation 后才验证认证拒绝，guard 未改；
旧 LocalSearch 生命周期测试实际在 assertSame 失败，源因为新增提醒控制器只在
actor finally 释放，未在 onCleared 同序释放且重复释放再发布。现已补接线和同步
幂等释放，未改旧测试、放宽断言或加 sleep。host-2 两失败方法各 actual 1 通过，
最终 772 项全部通过。原旧 94/96 SQLite 初始化/退出超时根因仍未知，不混为本次原因。

独审还闭合：手工任务旧回调互斥、手工提交未知时不越过 ACK、容量确定拒绝的可恢复
路径；对应真实控制器测试已实际通过。`host-2/`、`queue-1/` 为最终原件，来源说明
在 `provenance.json`。Library 交付结果与最终 Git/文件哈希另随证据包记录，未冒称
不可访问的旧环境 716 项原件已经找回。
