# 所选出处问答：持久化单次发送授权

本片推进两份原方案的 §4.1（改变目标/范围重新确认）、§8.1–8.2（ConsentGrant、Job、版本）、§9.2（持久化幂等/提交重检）、§10.3（最小上下文发给已授权模型）和 §14.1（执行前再次核授权）。原 Master Plan 和 DOCX 保持原样。

交付是**持久化多来源授权/撤回代码及本机内存传输验证**。真实生产问答仍关闭：Activity 的 service 默认 null、LOCAL_ONLY 默认和无 INTERNET 包边界不变；没有新增网络、权限、schema、依赖、密钥配置或真实模型调用。该片不能标为 §5.2/§10.3 正式问答或 Android 原生验收完成。

## 复用既有权威

`SqlSelectedQuestionAuthorizationRepository` 借用同一 `DrainingRecordsSqlDatabase`。它不创建/提升逐条记录的授权；全部所选记录必须已经拥有与完整 `ProviderConnection`、当前原文版本、CONVERSATION 用途匹配且当前有效的 `ConsentGrant`。这些既有 grant 授权的是原文版本和用途；本次问题的单次发送另被限制到明确选定片段，不能把本机选择确认描述成整记录外发授权。

只读 `review` 冻结问题、选择身份、问题时间/IANA 时区、精确 Unicode 码点摘录、原始日期/时区、完整服务配置（含 model/version/secretRef 等配置引用）、promptVersion，以及既有逐源 `RecordAuthorizationSnapshot`（hash/generation/policy/grant）。此元数据不证明凭据已配置。未来界面必须展示问题、完整接收目标及最小发送范围，并由用户明确调用独立的 `authorizeReviewedSend`；现有本机选择按钮不调用它。

复用 `remote_source_access` 和 `remote_text_jobs`；`local_jobs` 只保存独立 `SELECTED_QUESTION_SEND_V1` 操作的状态和规范化 SHA256 proof。每个成员的 prompt 命名空间为 `QS1:<完整proof>`；question/request/selection/time/target/quote/prompt/逐源快照任何改变都无法复用旧组。没有新权限表或内存权限来源，也不把完整原文作为多源请求 payload。

## 单次请求生命周期

授权时所有成员 queue 与组记录都在同一个外层事务中；任何成员的拒绝或存储异常必须抛出到该事务边界，整体回滚。`QUEUED→ADMITTED` 只允许一次，所有成员及组状态同时消费。已消费请求、丢失准入 ACK 或旧回执不能再次发起执行。授权 ACK 不确定时拒绝重建/重发；需要重新审阅的新请求身份。没有自动重试模型请求。

`accept` 在同一事务中重查全部来源、完整权限和精确摘录，再检查有界完整回答、请求身份及仅选定引用；将各成员和组的结果摘要原子提交。返回须等待真实 SQL COMMIT。答案正文不写 annotations、永久事实、待办或缓存。丢失结果 ACK 仅能凭完全相同的回答摘要且授权仍当前时恢复同一回执，不能重新准入；不同结果冲突。引用机械有效仍不等于自然语言被原文支持。

请求级 `withdraw` 原子撤销整次请求，包括完成后的回执；它不扩大/修改记录 grant，也不影响其他请求。任一来源撤回、过期、删除、版本/hash/date/zone 改变或 access generation ABA，整次检查和迟到接受即失败。沿用永久删除 fence；成员或组记录缺失不能重建权限。

复用单个 `PersistentSourceBoundTextSubscription`，每次对整个请求做同事务检查。100ms 是调度间隔，不是检测时限；SQL 锁和调度可能延迟观察。回调在 SQL 结束后只取消自有 exchange，绝不在观察线程做持久化清理。每次执行边界及接受事务仍做新检查，不能把轮询快照当授权。存储关闭立即封住新 SQL，并使用打开 owner 的 session 身份阻止旧 review/permit/receipt 跨锁定后的新 owner 使用。

## 验证入口和剩余工作

`PersistedLocalQuestionExchange` 仅接受 final `LocalProtocolQuestionService`，用于 `.invalid` 地址描述的内存 SSE：先订阅/核查、原子准入、创建及注册后重查、完整解析、最终持久接受和回执重查。同步失效、创建 exchange 中途停止、无事件时撤回以及不合作迟到返回均不得返回有效结果。取消不持有 SQL 或对象 monitor；未完成作业由 IO 执行 owner 清理。该对象不是 Activity 可注入的真实 `SelectedQuestionService`。

剩余真实接线：用户可见的独立外发审阅与目标配置 UI、受保护 secret resolver，以及在真正写入 wire 前受该持久化组权威约束的认证 transport/provider。不能仅把内存 fixture 换成 OkHttp 或开启 INTERNET 就宣称完成。真实网络发生后，已经发送的数据和服务商费用不能靠本地撤回追回；本片没有验证真实网络/收费。

主机回归使用实际 Kotlin 事务/codec/provider 与真实 SQLite，SQL 驱动为测试用 Python bridge，协议传输是明确的内存 fixture。Android Room/SQLCipher、设备锁/重启、SAF、无障碍、性能及真实模型仍未执行；SDK 新条款/通知权限未接受或配置。
