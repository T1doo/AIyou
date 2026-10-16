# F08：正式待办与已保存提案开放格式导出

2026-10-16，从已核本地/远端 `98d5465b0c3c82487c7c7c7f8e863b0fd43895ea`
建立 `codex/f08-open-task-proposal-export-20261016`。实际工作区干净、单 worktree、
唯一活动写入者，基线 ahead/behind 0/0。已重新阅读两份主方案的 F08、模型与
§14.4；原 Master Plan 与 Architecture 文件保留不改。

父级已核该基线的 [run 38881443812 / job 116908880900](https://github.com/T1doo/AIyou/actions/runs/38881443812/job/116908880900)
于 09:55:43 UTC 全部 45 步通过：日志报告 Compose 210 项，失败/错误/跳过均 0，
前次两项失败方法各执行一次通过；Preview 394 tests/394 PNGs，lint 0 错误、
0 警告、1 hint，APK 与 instrumentation 编译通过。此为父级 job 日志核实，
未独立重数 XML，不代表真机或视觉验收，也不覆盖本批。f0c2ccf 的原失败历史
及旧 94/96 SQLite 初始化/退出超时未知原因继续保留；未重查被拒 Actions 路径。

本批复用原文导出入口、内存快照、SAF 系统选择器、UUID/epoch 回调所有权、明文
风险确认及完整文件读回。Markdown/JSONL 增加默认关闭的明确范围：“全部正式
待办与已保存待确认草稿”。包含 OPEN/COMPLETED/CANCELLED 正式待办及已保存
DRAFT；不含未保存表单、已处理提案历史、附件、历史版本、AI 派生、提醒、密钥
或联网授权。可不选原文只导出任务/草稿；全空范围明确失败，不建立文件。

用户先看到实际分类、条数、正式状态、明确日程/偏移、草稿原日期/时刻/时区/
重复时刻输入和未完整包含的来源数，再另行确认明文、云盘及覆盖风险，之后
才启动 SAF。优先提示使用独立口令加密包。切换格式撤回同意；切换图文 ZIP
清除活动范围和快照，保留原 ZIP 范围及认证协议。原纯文字导出未选活动时仍
使用原格式、文件名和字节契约，没有强制捆绑任务导出。

活动读取复用现有任务/提案 owner 的独立 AEAD 解密和有界台账读取，并在同一
SQL transaction 内核对选定原文、标签、generation 与全部任务/DRAFT 快照。
不调用媒体认证、不读取排除的图片，因此损坏图片不阻挡明确的文字范围。
正式任务与 DRAFT 各最多 256 项；提案物理台账允许最多 4096 行，用 LIMIT 4097
探测超限后整体拒绝；超限或损坏
整体失败，无截断、跳项或用空清单掩盖失败。整体 UTF-8 文件最多 2 MiB。

新开放格式 `AIyou-originals-and-activities`，formatVersion/schemaVersion 均 1。
JSONL 首行为 manifest，实体类型严格分为 `currentOriginal`、`manualTask` 与
`taskProposal`；末行为 end，记录分类计数和所有规范实体行的 SHA-256。
Markdown 按三类分节，嵌入同一 JSON 元数据及可变长度代码围栏中的字面正文/
标题，防止反引号、HTML 或链接逸出。普通文本/Markdown/JSONL 工具即可读取；
不提供导入、恢复、执行或通知能力。

任务与草稿保留 ID/version、创建/更新时间及创建时区；原文保留 ID/revision、
捕获时间与捕获时区。正式任务保留状态、日期、
可空明确时刻、IANA zone、原 offsetSeconds 与可解释的 FIRST/SECOND；日期
不会自动补时刻。DRAFT 保留未完成原始 date/time/zone/occurrence，不 parse、
trim 或推断成正式日程。来源保留 ID/revision/hash/generation/Unicode 范围；
仅与所选当前原文完全匹配且当前有效时，`includedOriginalIndex` 指向从 0 开始
的原文行，原文另带 sourceGeneration。null 表示未完整包含或当前来源不可用，
不伪造链接，也不声称未选来源已删除。独立手工任务的 source 为 null。

导出前、实际写入前、写入及完整读回后核对全部活动与原文快照。活动变化整体
失败，不沿用旧纯原文导出允许“较早快照已完成”的分支；读取未知走 FAILED，
不误报为确定变化或成功。取消/迟到回调不能写到新会话。失败保留所选文件与
残件提示，只在用户另行确认同一目的地后删除，不自动删文件或未知锁/进程。
导出不修改来源、任务、草稿、捕获输入、处理范围或任何授权。

新增 10 项主机专项走实际 SQL、任务/提案 AEAD、codec、App controller 与文件
IO，使用独立标准 JSON parser 核对分类、原始输入、来源映射和 end 哈希；覆盖
全部正式状态、DRAFT/拒绝历史区分、task-only、AI_NONE、generation 变化、伪造
映射、排除图片损坏、空范围、257 项拒绝、密文损坏、真实 2 MiB 上限、完整文件
读回、显式 scope/风险门、迟到回调、写前/写后变化、取消、close/read/mismatch
失败及明确删除；写后破坏活动密文核对未知分支不会误报成功或变化。

新增 2 项 Compose 源码方法走真实 App/dialog/controller/codec/SAF intent 与受控
只读存储端口，覆盖无原文入口、双倍字体分类/原始时间预览、单独明文风险确认、
私密状态不进 SavedState、格式切换撤回同意、旧回调拒绝和变化后不发起选择器。
本机 SDK 预检失败；这两项本地 NOT RUN，不能用主机端口验证代替 Compose 执行。

独立只读审查无阻断。首轮专项失败为两个 Kotlin 表达式方法推导成非 void，
原日志/XML 保留；修正为 Unit 并修正正式标题/SourceTasks.id 测试输入约束。
第二轮 9 项中 8 过、1 失败，为损坏注入 UPDATE 未递增 version 触发原 guard；
修正注入的 version/mutation 元数据而非修改 guard，原日志/XML 保留，另补
第 10 项写后未知读回断言。
最终执行统计与输入指纹见下方交付核验。

证据目录：`/workspace/aiyou-evidence/f08-open-task-proposal-export-20261016/`。
仅合成 LOCAL_ONLY/AI_NONE 数据；无新后台、依赖、schema、费用、凭据、安全
权限、模型调用或 SDK 条款接受。main 保持不变，只正常开发分支 push 触发现有
25 分钟 CI，不额外 dispatch。Android AAR/APK/lint、Compose/Preview、原生
instrumentation、SAF/Keystore/SQLCipher、全新安装/设备重启、视觉/TalkBack/
性能验收本地未执行，没有本批 APK；CI 不能代替设备验收。

最终可用检查：

- PASS：`python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --offline --rerun-tasks`，716/716（AI 178、domain 46、SQL/VM 492）。新增 10 专项均实际执行通过；失败/错误/跳过均 0。
- PASS：`python3 scripts/verify-ai-protocol-results.py`，精确源码方法清单与真实 XML 一致，最终输入 SHA-256 为 `edf9304717695f6a94d7e2b8df7646ce3ad203cbc06de2f927169edd32dbbdf5`；在队列重跑共享测试前保存 XML、执行前输入记录和校验报告。
- PASS：`python3 -m unittest discover -s scripts/tests -v`，123/123。`git diff --check` 与 HTTP 依赖校验通过。
- PASS：`python3 scripts/jvm-gradle.py -p checks/record-queue test --offline --rerun-tasks`，149/149，失败/错误/跳过均 0；`python3 scripts/verify-test-results.py --queue` 核实实际 XML。队列完成后再次核验完整协议报告，输入指纹一致。
- FAIL：本机 SDK 预检，`ANDROID_HOME must point to an SDK with developer-accepted licenses.`；未安装、接受条款或重试已拒路径。
- NOT RUN：新增 2 项 Compose 的本机执行及上述 Android/原生/设备验收；旧 98d 基线 CI 不覆盖本批。新分支正常 push 后本批精确 CI 仍须另行核实，不声称 GREEN。
