# F08：任务与待确认草稿加密迁移

后续可选 v4 原文完整历史范围见 [历史加密迁移](F08_ORIGINAL_HISTORY_PACKAGE_STATUS.md)；
本文 v1/v2/v3 及默认当前版本范围保持原契约，未把旧包解释成带历史。

2026-10-16，分支 `codex/f08-task-proposal-package-20261016`，从已核本地及远端
`c883cdffa49a38f899a32fd82570c7645a957d79` 正常建立。开始前唯一活动写入者为
本任务，工作区干净、单一 worktree、ahead/behind 0/0；不恢复 UNKNOWN 旧任务。
main 保持 `7383e502418f5f9fc0c540ef2072742359343d40`。

已阅读原方案 F08、§8.1、§13.1、§14.4 及 Architecture 对应模型、迁移和授权
边界。原两份设计文档未修改。本批扩展现有 RecordPackage、SAF 和记录 owner，
没有另建迁移工具、后台、依赖或 schema，没有扩大成全库备份。

已核 c883cdf 的 run `38868596891` / job `116867098844` 全部 45 步成功，208
Compose（包括 5 项 TaskProposal、4 项 SourceTask）、APK、instrumentation 编译、
394 Preview、lint 通过，lint 0 错误/0 警告/1 提示。未为确认旧门额外重跑 CI。
原证据归属见 [F06 精确 CI 记录](F06_SOURCE_TASK_CI_FIX.md)；这些结果不覆盖本批。

现有页面新增明确勾选“包含全部正式待办与待确认草稿”，默认不勾选；可与选定
原文/原图一起导出，也可零记录只迁移任务。预览逐项展示正式状态、明确日期/
时刻/时区/UTC 偏移、草稿原始未完成输入和重复时刻选择，分别计数，并显示来源
未映射数。先确认完整范围、两次独立恢复口令，再选择保存位置；恢复仍先完整
认证和预览，再由另一真实按钮确认。来源说明不把认证等同于作者身份。

新包 magic `AIYOUPK3`、外层格式 3/内容 schema 1、活动分块 schema 1。复用已有
PBKDF2-HMAC-SHA256（600000 次）和平台 AES-256-GCM，不复制旧设备包装密钥。
随机 salt、nonce 前缀、帧序号和 AAD 规则沿用 v2；完整元数据、每幅原图、终帧和
精确 EOF 均认证后才释放预览。导入时重新认证所选文档，防止认证后替换/截断。
原文分块仍用 v1 描述/哈希，v3 外层范围额外包含 manualTasks/taskProposals。
活动包含版本、旧 ID、输入/状态/时间、source binding、显式来源索引及所选原文
generation。正式日程另外保留原始 UTC offset，并与解析结果逐值核对；设备时区
数据不同导致偏移不一致时整体失败，不静默改动明确时刻。不存在可解释为文件
路径的用户控制条目。

v1/v2 读取、字节契约和排除范围保持；它们仍拒绝零原文包。只有 v3 在任务或
草稿非空时允许零记录，未伪造空原文。原文/原图仍最多 20 条/20 张，单图 16 MiB、
图片合计 64 MiB，元数据沿用约 2 MiB 上限。正式待办和 DRAFT 各最多 256 条；
活跃草稿导出另对现有物理台账做 4096 行有界核读，超限/损坏整体失败，不 `take`
截断。仅纳入已保存 DRAFT，已拒绝历史、捕获/编辑表单草稿、派生 AI、提醒配置、
服务密钥、设备密钥和联网授权均不在本批范围内。

来源必须同 ID/revision/hash/generation/Unicode 范围完整匹配所选原文，并通过
当前本机来源校验，才登记映射。恢复时映射到新 UUID 原文的 revision 1/generation 1；
记录初始 AI_NONE、connection/consent 为 NULL。不能完整匹配的来源保持私密输入，
改用独立 PACKAGE_UNAVAILABLE 命名空间，UI 与 SQL 均禁止其确认/来源跳转，即使
目标本机恰有相同旧 source ID/正文也不绑定。旧代次草稿不会借恢复获得新授权。

正式任务和 DRAFT 使用目标 owner 既有独立 AEAD purpose 重新加密；新身份绑定
backup ID 和旧实体 ID，版本从 1 开始。正式状态、原创建/更新时间、明确日程与
草稿原始输入保留。受限内部 IMPORT 操作支持 version 1 各正式状态，不走多事务
create/change。草稿只写 DRAFT，未调用 confirm；不会创建提醒、推断时间、发起
模型或恢复授权。只有用户以后另行确认且来源仍有效，才可创建正式任务。

媒体暂存、记录副本、任务、草稿、活动映射/初始摘要回执、包回执和媒体 DONE 共用
既有最终 SQL 事务。任何后段失败整批回滚，既有注册文件恢复协议只处理该批
意图。活动回执使用 FK-free record_package_receipts 的独立 operation；零记录时
只写稳定哨兵，不造 entries/local_jobs。包 digest、原文摘要、稳定新 ID 映射和
活动摘要全量比对后才能重放；重试只核对首次结果，不覆盖当前任务、不复活已
确认/拒绝/永久删除的草稿，也不恢复已删除原文。原来源删除仍保留独立正式任务。

导出前、写入前后核对完整任务/草稿快照，变化整体失败。零图片路径仍重新认证
完整包/终帧/EOF 后才执行同一最终 SQL，不获取不需要的附件文件锁；含图路径
保持原锁、失败和明确同包重试。并发读到 PENDING、另一 owner 已提交时，转为
严格校验已有回执；未将任意存储异常变成成功。恢复后读取真实任务/草稿清单与
processing proof，成功计数明确为首次导入映射，当前内容/状态以本机清单为准。
现有输入、取消、迟到回调、读回未知和同包确认门继续保留。

新增 13 项主机专项，使用实际 JCA、SQL、媒体、controller 和独立 SQLite 进程：
Node/OpenSSL v3 独立样本及 12 个已认证畸形包、KDF/文档 close 异常、混合原图/
任务状态/DST/未完成草稿、多 key 重加密、task-only、碰巧同 ID 来源隔离、旧
generation、丢最终 ACK 后冷重放、真实永久删除后重放、后段草稿 INSERT 失败
整批回滚、文档认证后替换、完整清单变更/超限、同 backup ID 异内容冲突、确定性
双 SQLite owner 并发，以及实际 controller 读回失败后不重复恢复和清单刷新。

新增 2 项 Compose 源码回归走真实 App/Screen/controller 与受控认证/存储端口，
覆盖零记录选择、双倍字体、逐项时间语义、显式范围/恢复确认、Bundle 私密状态、
未知读回同包重试和不确认草稿。没有用 UI 测试端口替代生产 SQL 认证。
另新增 1 项原生 Room/SQLCipher 多 owner/key、冷重开、状态保留与 task-only
回归源码；精确方法清单 50→51。所有新增 Android/Compose/原生方法本地 NOT RUN。

独立只读源码审查未发现阻断；审查建议已闭合 metadata 异常清零、文档 close 前
密钥所有权、DST 偏移/重复时刻展示及首次回执计数文案。字符串仍遵循现有 JVM
不可原地清零限制；受控密码/字节缓冲按所有权清理，不宣称绝对内存擦除。

保留 `compile-1.log` 的跨模块 smartcast 编译失败（局部引用修正后 compile-2 通过）。
首轮 12/12 专项通过；补资源回归后 13 测中 1 项并发失败，原 activities-2 log/XML
保留。随后 race-diagnostic-1 以确定性交错重现旧 PENDING 前置检查失败，保留
原异常栈；修正上述窗口与零图借锁后 activities-3 13/13 通过。没有删锁、杀未知
进程或把失败归为偶发；原始 94/96 SQLite 初始化/退出超时原因仍未知。

执行日志、XML、输入指纹、独立样本和 CI 归属保存在
`/workspace/aiyou-evidence/f08-task-proposal-package-20261016/`。
SDK 只读预检仍失败，未安装 SDK、接受新条款或绕过 Actions/已知路径拒绝。
AAR metadata、assembleDebug、lint、Compose/Preview、instrumentation、本机原生
SAF/Keystore/SQLCipher、全新安装/设备重启/视觉/TalkBack/性能验收均本地未执行，
没有新 APK。新分支只正常 push 匹配既有 25 分钟 CI，不另行 dispatch；CI 不等于
真机验收。仅合成 LOCAL_ONLY/AI_NONE 数据，没有新增费用、凭据、安全权限、
SDK/模型调用、自建后台或部署。

最终实际回归（最终生产源码与独立样本，非旧基线报告）：

- PASS：`python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --offline --rerun-tasks`，178 AI + 46 domain + 482 SQL/VM = 706 项；69 份 XML，失败/错误/跳过均 0。含最终 13 项活动专项及全部 12 个认证畸形样本，`full-host-3.log` 与 `host-3-xml/` 保留实际原件。
- PASS：`python3 scripts/verify-ai-protocol-results.py`，精确源码方法清单与实际 XML 一致；最终输入 SHA-256 为 `18af9810477ce53ab764e4b0e9ab62fe965e782df22af71af0eeb1aa5fa0369c`。队列运行前后均再次核对该输入指纹。
- PASS：`python3 scripts/jvm-gradle.py -p checks/record-queue test --offline --rerun-tasks`，149/149；`python3 scripts/verify-test-results.py --queue` 核对 16 份队列 XML，失败/错误/跳过均 0，保留 `queue-2.log`、`queue-2-xml/` 与核验日志。
- PASS：本批 `scripts/tests` Python 回归 123/123（`python-1.log`）、Compose token 一致性与 HTTP 依赖校验；未因最终仅格式字段/样本变更重复不受影响的 Python 回归。
- PASS：最终独立只读源码审查与 `git diff --check`。原设计、workflow、依赖版本、Android 权限和数据库 schema 没有变化。
- FAIL（环境预检）：`ANDROID_HOME must point to an SDK with developer-accepted licenses.`；未接受协议或安装 SDK。
- NOT RUN：本批 2 项 Compose 与 1 项原生新方法、Android AAR/APK/lint/Preview/instrumentation、原生 SAF/Keystore/SQLCipher、全新安装/设备重启/视觉/TalkBack/性能。51 项原生清单只证明源码枚举，不能替代执行。

旧中间 94/96 两项 SQLite 初始化/退出超时原因未知且未被本批通过结果覆盖；本批
smartcast 与并发前置检查失败及其修复验证原件也全部保留。新提交只由普通开发
分支 push 触发现有免费 CI；未通过本地 Actions 访问限制取结果，交付时本批 CI
状态仍为 UNKNOWN，不能沿用 c883cdf 的 GREEN，也不能宣称 F08/真机整体验收完成。

后续精确 CI（父级独立回传，非本机绕过访问限制取得）：`f0c2ccfb97af23eade4867a9eafdbecd42f776b9`
的 [run 38878767475 / job 116900120139](https://github.com/T1doo/AIyou/actions/runs/38878767475/job/116900120139)
于 2026-10-16 09:26:27 UTC 失败。APK 通过，Compose 210 项中 2 项失败；
instrumentation 与 lint 跳过。两项均在新 RecordPackageActivityPresentationTest
摘要断言处失败：`assertTextContains` 默认完整匹配，原测试传入了片段。实际完整
摘要包含正确的 1 项正式待办、1 项草稿及 1 处未映射来源。

最小修复将两处改为 `assertTextEquals` 完整摘要，并将后续同类重试回执断言改为
完整回执文本（包括删除/删除中各 0 条），没有改生产代码、跳测、加 sleep 或删除
任何逐项预览、范围/独立确认、UNCERTAIN 同包重试、草稿不提升与输入隐私断言。
独立只读审查无阻断。原生产输入指纹及 706 项既有 XML 重新核验一致，只证明
生产源码未变，不计为本次 Compose 实际执行。SDK/Actions 边界保持；修复提交
的新精确 CI 结果仍待取得，在门通过前不推进下一批开放导出。
