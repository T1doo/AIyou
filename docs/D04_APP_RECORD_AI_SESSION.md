# D04 原文页 AI 会话归属

本切片接续 `3c3c6d5cc225e619798550b666c3316ea0490fd3`，把独立 AI 工作归属接入实际 `LocalRecordsViewModel` 和原文页。完整 D04、真实模型设置与 D02 生产重启原生验收继续 **OPEN / NOT RUN**。原始主计划及产品架构文档保持不变；此前 transport 关闭证据见 [D04_TRANSPORT_CLOSE.md](D04_TRANSPORT_CLOSE.md)。

## 产品与归属

- 原文页的“AI 整理”只打开一份已保存原文当前版本的内存会话。每次打开均有新身份，包括同一 ID／版本重复打开。会话、预览和生命周期状态不存入 Bundle，也不覆盖原文或自动创建待办。
- 公共 App 构造入口不提供模型能力。`LOCAL_ONLY`、`AI_NONE` 和尚未开放的模型连接会显示原因，并禁用“开始整理”；打开入口不初始化客户端、不解析密钥、不排队发送。此切片没有新增 `INTERNET` 权限、凭据或真实模型调用。
- `SourceBoundRecordAiCapability` 是 App 层对实际 source-bound transport 的适配器，借用已经准备好的记录仓库。模型能力工厂仅在独立 IO 工作者首次需要时调用；本地记录队列不等待它。测试注入合成能力，不开放产品隐藏发送开关。
- 一个会话最多开始一次。再次打开、切换原文／版本、离开页面或 ViewModel 清理会立即撤销旧身份。停止立即关闭预览门禁并异步取消；已经提交的候选不会撤回，仅同一仍有效会话的提交回执可更新保存状态说明，不能恢复迟到正文。旧回调、结果和延迟返回的模型工厂不得写入新会话；延迟创建的能力由原工作者关闭。旧会话 ID 的清理不能关闭后来打开的会话。
- AI 事件只更新内存预览；wire completed 仅进入“正在确认”。只有实际 source-bound 结果返回匹配原文版本的持久化 annotation 才显示成功。候选保存在独立 annotation 表，仍待用户确认，不自动成为事实或任务。
- 所有本地及 AI 状态写者在同一次 StateFlow CAS 中核对记录、版本和策略，避免旧预览与新原文版本一起发布。原文修改／历史编辑会先离开 AI 会话。本地捕捉、草稿和保存继续沿用原记录队列。

## 关闭顺序与局限

ViewModel 清理先撤销 AI 身份，在独立 IO 上关闭 transport，等待已有工厂、执行和取消工作，再清理附件及共享记录仓库。正式 `EncryptedEntryRepository.close()` 在关闭 Room 前封闭新增 remote SQL，并等待已借用 SQL 的工作，包括 source watch。界面线程不调用同步取消 SQL、transport close 或数据库 close。

等待实际 SQL 排空可能阻塞；没有硬性关闭时限，也没有通过强制关闭共享数据库伪造完成。重复 `close()` 仍不是首个关闭工作已经完成的回执。已发送字节不能撤回，跨连接轮询也不是撤销提交时刻的硬截止保证。取消后的既有 job 绑定不能通过偷偷更换 prompt 版本重新发送；此切片没有自动重试或背景队列发送。

## 验证边界

新增契约编译实际 App ViewModel、控制器、能力适配器及生产 remote 事务代码。Android opening、生命周期和图片解码以最小 JVM 平台壳代替；本地记录操作通过 test-only SQL driver 在同一真实 SQLite 文件执行，授权、job 及 annotation 规则仍来自生产 `RemoteTextTransactions`。HTTP 仅经过既有测试注入到 `127.0.0.1` 的真实 OkHttp/SSE 流，原有 3 秒总时限／1 秒 idle 预算不变。

这些验证支持实际 VM 调用和清理顺序，但不等于正式本地 Room 事务、Android 生命周期／Compose 设备交互、SQLCipher 跨连接时延、真实模型或生产重启验收。CI 只检查已编译 APK 的实际合并权限；27 个 native 业务方法及两个 probe 的编译／DEX 清单仍为设备 **NOT RUN**。

本地使用既有 JDK／校验过的 JVM Gradle Wrapper；未安装 SDK、接受新协议、运行 ADB／模拟器或生成认证密钥。已有依赖缺少缓存时，通过正常 Maven 网络解析固定版本，不更换镜像、不绕过被拒路径或权限。

权限修正后，本地唯一写者完成新的实际 `--offline --rerun-tasks` 全量重跑：262 个唯一方法（AI 178、domain 13、remote／实际 VM 71），15 份新 XML，失败／错误／跳过均为0；另有旧记录队列138方法通过。最终输入指纹 `3dd0da52b08b0eacebbe94560ab594a2f685b00d5b8da2b7c9d7c4d96b076a38` 绑定121项上游源码与构建输入；该轮日志 SHA256 为 `8b5fc9c2912ee5f78abf6cfc48be506da8c232deb830a50aff4b1228592b0b4c`。独立只读复核未发现剩余 P0/P1。首次新增 VM target 的12方法和最初完整262方法也独立通过，旧指纹 `2cca488461e7005620e5d9cd05a05cf92ee3821851ab0b6993c644f5c000db65`、旧全量日志 `f4f0cf895ba2aa18ea7e03eeb36da1d3e68211b6e4f3e1612dc473822bc46431` 仅属修正前轮次；重复执行不计作新增方法。

首次 offline 编译因未缓存既有 coroutines 1.9.0 停止，没有开始 JUnit；随后通过普通 Maven 网络解析同一固定版本，编译及上述测试通过。失败日志仍保留，没有安装 SDK 或改变安全配置。Android 依赖新增 App→core:ai 后，App 锁由既有许可 CI 环境实际生成、正常入库，再移除临时写锁步骤并验证锁定构建；不猜写 Android 依赖图。

首轮正常 push `f7cae574882d0c8efb6346f2be8adbe9157cf2ee` 的 [CI38458941492](https://github.com/T1doo/AIyou/actions/runs/38458941492) 实际 **FAIL**：APK编译通过，但 `20:06:16.1000317Z` 的实际 APK 门禁报告 `Actual merged APK unexpectedly requests INTERNET permission`；后续 Compose、native 编译／清单与 lint 五步被跳过。该轮38/44步骤成功不等于全套通过，原始日志 SHA256 `1b307c3b5877b78412c73e891393f3738bef80bfe2fa5e2927d81a45d92860bf` 保留。

实际 Android 图选择 `okhttp-android:5.5.0`；[官方版本 manifest](https://raw.githubusercontent.com/square/okhttp/parent-5.5.0/okhttp/src/androidMain/AndroidManifest.xml) 含 INTERNET 声明，仅检查 App 源码未请求该权限不足以保证合并产物无权限。修正按 [Android 官方合并规则](https://developer.android.com/build/manage-manifests#node_markers) 在 App manifest 使用无 selector 的 `tools:node="remove"`，明确删除库导入的 INTERNET。源码检查按 XML 区分移除标记和请求；实际 aapt2 原始输出门禁仍拒绝任何残留 INTERNET，不以标记代替 APK 验证。本任务没有安装或运行首轮 APK。

App锁取自上述实际CI生成字节：82985 bytes，SHA256 `8c8b721cc8efb05708211a868fb1599a1f80dbd6e08c5b4c38a017dc162a094b`；其他六份 schema／lock 逐字节未变。临时 `--write-locks` 已移除，最终正常 push 的同 SHA CI 才用于锁定构建及实际无 INTERNET APK 证据。

具体测试 XML、提交、同 SHA CI 与 APK 摘要在交付回执中核对。此前 transport 首回调 barrier 未到达的失败日志仍保留，原因未知；本切片不把后续通过解释为该历史原因已解决。

最小后续工作是：在已有合法 SDK／许可证和已授权设备可用时完成 D02 生产重启原生验收。真实模型发送仍需独立产品设置、密钥解析和当前授权设计，并在获准权限范围内验收；当前产品停在明确禁用的原文入口。

## 2026-10-16 停止与持久化回执一致性

从已核验的 `46a880834b02706d5073bfee3e23fb7870394c12` 建立
`codex/fix-ai-stop-receipt-20261016`。按两份主方案 §4.4 / §10.6 / §14.1
检查保存、删除传播及恢复边界后，实际复现一个停止竞态：生产 SQL 已写入
annotation 并将 job 设为 COMMITTED，回执返回暂停期间点击停止，旧控制器却
持续显示“预览未保存”。数据库取消正确地只处理未完成 job，不能为迎合 UI
撤销已提交候选。

修复使停止立即清空预览、关闭 accepting 门禁并排入原有 IO 取消队列；当前
页面使用“已请求停止；已完成本机保存的候选不会撤回”。只有同 Session / Run /
storage owner、仍 live、当前原文及策略仍有效的真实已提交回执，可以补充
“停止生效前候选已保存”的说明。该分支不显示迟到正文或 annotation，不恢复
SUCCEEDED，也不影响关闭、换页、改版、撤权或销毁的失效规则。确认阶段改为
核对本机保存状态，避免把“回执未到达”误称为“数据库尚未保存”。重复停止和
成功后停止保持既有结果。schema、授权、默认 LOCAL_ONLY 和任务确认规则不改。

新增 4 个实际 ViewModel / controller / source-bound SQL 契约方法，复用两种
协议的合成 loopback HTTP；无真实模型、服务密钥或新增产品网络入口。覆盖提交
前停止、真实提交后暂停 ACK 且本地保存继续、关闭/同源重开/改版/销毁/同版本
撤权/撤权后重新授权后旧 ACK 不得发布、成功后停止。独立 SQL owner 核对提交或
撤销后的持久数据。旧生产代码
运行新增回归实际失败，失败消息为 `Stop cannot deny an already durable candidate`；
原失败日志与 XML 保留。只读独审无阻塞意见，实际执行范围以最终交付回执为准。

最终离线全量实际执行 **632/632**：AI 178、domain 42、remote / 实际 VM 412，
0 failure / error / skip；包含当前 16 项 AI 会话契约。旧 ACK 放行后先限时 join
真实工作，再断言并销毁测试 VM，避免以 dispose 掩盖关闭/撤权门禁；同版本策略
往返使用既有本地保存命令作为队列屏障，避免刷新合并造成调度歧义。实际 XML、
源码方法清单及当前输入指纹均通过验证。Python **123/123**，`git diff --check`
通过；重复运行不累计方法数，旧队列 148 项不作为本次重跑结果。

Android SDK 条款仍待答复，本批未安装或接受；Compose、assembleDebug、lint、
原生 SAF / Keystore / SQLCipher、设备重启、TalkBack、视觉与性能验收均未在
本地执行。祖先 CI 成功不能替代本批同 SHA CI，更不能替代真机验收。
