# F02：AI_NONE 人工编辑、草稿恢复与历史新版本

2026-10-16，开发分支 `codex/f02-ai-none-manual-edit-20261016`，基于已核验远端
`8f0794950e9ef6c3e81ab5a54f3c46d330638043`。`main` 保持
`7383e502418f5f9fc0c540ef2072742359343d40`。原始两份方案未修改。

原 Master Plan F02、§5.4、§14.1 的 AI_NONE 仍允许保存和人工浏览。之前
`LocalRecordsViewModel.openOriginalDraft` 与 `persistDraftSnapshot` 仅接受
LOCAL_ONLY，导致 AI_NONE 手工编辑、冷恢复、历史创建新版本被误判为冲突。
本次统一使用显式 AI_NONE / LOCAL_ONLY 白名单。编辑页对 AI_NONE 显示
“不要据此推断 · 人工编辑”，保存仍创建新版本，不改写旧版本或策略。

不改变新录入/导入/回收恢复默认，不新增 REMOTE 或自动整理。原先的草稿身份
和版本 CAS、独立加密文件、持久提交回执、来源版本检查及删除 fence 保持原样。
人工修改仍保留此刻草稿和冲突输入。SQL 版本推进触发 generation 递增、撤销旧
授权、清理派生结果及索引失效；人工原文的 LOCAL_INDEX 仍是词法查找作业。

新增六项主机专项，使用生产 ViewModel、AES-GCM 编辑文件及真实 SQLite 的
生产 schema/触发器：

- AI_NONE 人工编辑、保留草稿、关闭 owner、冷恢复、单次新版本与策略/历史/此刻草稿保留。
- AI_NONE 历史选择不写入，明确创建新版本后才保存，旧 history session 不再有效。
- 源版本变化后保留冲突加密输入，不能覆盖当前原文。
- 提交后读回失败，重试只确认已提交结果，不重复写版本或生成 AI 内容。
- 永久删除 PREPARED fence 拦截迟到草稿写入，既有密文保持不变。
- 先创建的真实本机候选经策略撤销和人工修订后，迟到 complete 与 AI_NONE
  候选读取均拒绝；SQL payload 已清空，状态保持 INVALIDATED。

主机正式 revision IO 明确使用 `SqliteVmEntryRepository` 测试适配器，不能作为
生产 Room / SQLCipher 事务运行证据。新增原生方法
`aiNoneManualAndHistoricalDraftReceiptsPreservePolicyAcrossNativeColdReopen`
直接走生产 `EncryptedEntryRepository`：AI_NONE 冷恢复、同 draft 持久回执重放、
旧 revision CAS、历史来源再建新版本、generation 与独立草稿保留。该方法已
注册至精确原生清单（47→48），同时同步库存断言与动态结果计数；仅收集源码，
原生运行仍为 NOT RUN。

两项新 Compose 方法使用实际 App 路由、ViewModel、编辑页及加密草稿，覆盖
320dp / 双倍字体下 AI_NONE 冷恢复保留/保存，以及历史选择明确创建新版本。
均加入 `verify-test-results.py --ui` 精确方法门槛，未作为本地通过数量。

原队列“来源不支持”用例曾将 AI_NONE 视为不支持人工编辑，与本次要求冲突；
现将该拒绝分支设为 REMOTE_ALLOWED，保留来源删除、冲突输入和密文不变断言。
另增一项精确登记的队列用例，验证编辑中切成 AI_NONE 后仍可人工保存且不改策略。

本地完整主机合约 **651/651**（AI 178、domain 42、SQL/VM 431），实际 XML 的
方法清单、执行输入指纹与 0 failure/error/skip 均通过验收。Python **123/123**，
队列 **149/149**（含新增一项精确方法）、tokens 检查与 HTTP 依赖校验通过。新增六项主机专项包含在 431 中；不把未运行的
两项 Compose 或原生新增方法计入本地通过数量。

独立只读审查通过；指出并同步了原生库存断言中旧的 2→3 方法数量，质量门
保持严格。故障与执行证据保存在当前环境
`/workspace/aiyou-evidence/f02-ai-none-manual-edit-20261016/`：

- `red.log` / `red-xml/`：旧实现下五项专项全部失败。
- `targeted-1.log`：修正后 5 测 2 失败；测试误将旧 session ID 传入新 VM，
  以及 ready 前刷新；改为调用已退出的旧 VM 并等待 ready，`targeted-2.log` 通过。
- `targeted-final.log`：新第六项误期望 AI_NONE 可读候选，6 测 1 失败；修正为
  断言拒绝并核对实际 SQL 的 INVALIDATED/空 payload 后，`targeted-final-2.log` 六项通过。
- `full-host.log`、完整 XML/输入指纹验收回执、`queue.log` 和 `python-complete.log`
  记录本次实际完整范围；`native-source-inventory.json` 仅是 48 方法源码清单。

SDK 预检仍失败：无 ANDROID_HOME 指向已接受条款的安装。本轮未重试先前已知
受限 `.android` 路径的插件初始化；未安装 SDK、接受条款或绕过访问拒绝。
Compose、AAR metadata、assembleDebug、lint、Preview、新 APK 与原生测试均未
在本地执行，须等待精确新 SHA 的 CI。Actions 拒绝未绕过；正常推送仅触发
现有有界 CI，不额外调度，新 CI 状态保留为未知。

旧 **94/96** 两项图片包 SQLite 初始化/退出超时原因未定，以及 c43561f 未知
步骤 CI 失败均继续保留。SAF、Keystore、SQLCipher、设备重启、TalkBack、真机
视觉与性能未验；主机合约或 CI 不构成真机验收。仅合成数据，无真实模型调用、
新费用、凭据、安全权限或部署。
