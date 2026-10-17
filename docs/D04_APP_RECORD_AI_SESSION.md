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

## 2026-10-16 确认阶段的实际 UI 标签补正

父级对 `c6fdbe86263613f6ccc2bc19a54697a960514e04` 的只读审查指出直接相关
漏点：虽然 controller notice 已表达待确认，`RecordAiSection` 仍根据
`annotation == null` 固定显示“预览 · 尚未保存”。真实 SQL 已提交但 ACK 暂停
时，`CONFIRMING` 仍有预览正文、尚无返回的 annotation，标签因此与事实矛盾。
本批从上述精确祖先建立 `codex/fix-ai-confirming-label-20261016`。

先将原标签表达式无行为变化地提取到实际 `RecordAiUiState.contentLabel`，让
生产 Compose 直接使用；真实 VM / SQL 提交前暂停及提交后 ACK 暂停两个契约
据此实际 **2/2 红测失败**，原日志、XML、红测源码及差异保留。修复后，有真实
annotation 显示“AI 整理候选”；仅有正文的 RUNNING 流式阶段显示“预览 · 尚未
保存”；CONFIRMING 以及其他无回执状态统一显示“预览 · 保存状态待确认”。
生产新作业先同步发布 Completed / CONFIRMING，之后才调用 SQL commit；已提交
结果重放不产生预览正文。因此标签不以“annotation 尚未返回”推断“尚未写入”。

新增 2 项纯 JVM 生产映射测试，强化实际 VM 的流式、wire 完成、SQL 已提交 ACK
暂停和已返回 annotation 断言。现有 `LocalRecordsPresentationTest` 新增两项
真实 `RecordAiSection` 渲染测试：320dp / 2 倍字号的待确认标签及停止快照隐藏，
以及已保存 annotation 的候选标题。没有用假组件替代实际 UI；合成状态只验证
展示，持久化和停止门禁继续由真实 VM / SQL 契约证明。controller、停止后预览
清空、session / Run / owner / 版本 / 策略及销毁门禁不改。
既有 `verify-test-results.py --ui` 逐项门禁要求这两项新 Compose 方法的真实
成功 XML，不用合成 XML 冒充执行或以总数通过替代新增用例。

本地 Android SDK 及协议状态不变，新增 Compose 测试、assembleDebug、AAR
metadata、lint 和原生设备验收均 **NOT RUN**，无本批新 APK。两项 Compose
用例已编写但未在本地运行；实际可执行范围与新 SHA CI 状态以交付证据为准。

最终实际离线全量 **634/634**：AI 178、domain 42、remote / 实际 VM / UI 映射
414，0 failure / error / skip。逐项 XML、源码方法清单及本批输入指纹校验通过，
包含既有 16 项会话门禁契约与本批 2 项映射方法；Python **123/123**。
只读独审无阻塞意见。上述计数不包含未执行的两项新增 Compose 渲染测试，也不
把祖先的 191 Compose、394 Preview 或旧队列 148 项算作本次重跑。

## 2026-10-17 逐记录授权管理端口（独立主机片；配置草案持久化仍阻塞）

存储方案选择同一加密 Room 库的正式受管草案目录，不以明文显示设置、备份回执、虚构记录或旁路文件代替。
当前只有逐记录完整 `ProviderConnection` / `ConsentGrant` 快照，不能伪造 `secretRef` 装成无密钥草案。
新增正式目录会需要 12→13；真实 KSP11 导出/fixture 及随后 KSP12 对尚未存在，原 schema 门未过。
本片不加表、不写假导出/fixture、不升版本，也不增加不可保存的配置/诊断页面。
**无密钥草案持久化、配置目录和真实用户配置流程尚未实现，完整生产接线未完成。**

独立完成的是 `RecordAuthorizationRepository` 元数据管理端口，借用既有加密记录所有者。
预览冻结来源版本/哈希/代次；确认只引用库中已有的完整配置，绑定用途、明确起止时间及不可变 mutationId。
没有完整配置时不能确认；预览和保存记录不授权。凭据状态始终 NOT_CONFIGURED / UNVERIFIED，
`executionReady=false`，授权元数据有效不等于可发送。生产 `bindAi=null` 和 INTERNET 移除保持不改。

实际权限仍在原 `remote_source_access` 行；`local_jobs` 只存 `RECORD_AUTH` 操作的哈希证明，**不是配置库或发送作业**。
确认/撤回、既有来源 CAS/失效与回执在同一所有者的外层事务中提交，复用原事务实现和嵌套事务管道。
存储异常可能发生在 COMMIT 后：调用者须保留同一请求，重试不改授权时刻/到期时间或重新生成 mutationId。
已提交请求的旧回执在后来撤回、配置变化或到期后只报告当前范围及原决定已非当前，不重新授权。
删除、版本/哈希变化、永久删除门及回执冲突拒绝；普通旧来源不能借首次读取升级代次。

十一项实际 SQL 测试覆盖预览取消/缺配置、本机默认、明确确认与重开、真正 COMMIT 后 ACK 丢失的重开/精确重试、
SQL 回滚、撤回/迟到 ACK、配置/策略及真实来源版本 1→2→1 ABA、配置变化/nonce 冲突、最终时钟到期或取消、独立连接竞争、来源及删除门。
合成完整配置与引用只在测试中经既有接口植入，没有真实秘密材料；各新 fixture 的发送作业和注释表始终为空。
复用真实既有 v7→8 主机合同，不是 Room11/12/13 假 fixture。初次跨模块 nullable grant 智能转换编译失败在测试前发生，
改为不可变局部后十项通过，首次完整回归 1029 项通过。复核后补一项真实来源版本 ABA，重新针对新输入验证；
两轮原始日志和输入均保全。新增 API 在基线不存在，没有宣称基线业务红灯。
最终针对新增用例的定向测试 **11/11 PASS**；完整 **1030/1030 PASS**（178 AI + 71 domain + 781 remote-text），
零失败、错误和跳过，4 分 34 秒。旧 1019 方法完整保留，新增 11；149 队列 / 151 Python 仅保留历史记录，未重跑。
最终原始 XML、输入和唯一方法闭包见 [端口证据ZIP](evidence/d04-record-authorization-port-host.zip)。

本片未新增秘密解析器、client/exchange、发送队列、真实模型、权限、依赖、费用、SDK 条款接受或 Actions 访问。
真实 Room/SQLCipher、Android 构建、Compose/Preview、关闭/重启及设备验收仍 NOT RUN，无新 APK；主机端口测试不能替代。
前置 8a 文案及其证据原字节不改。本片只是可独立核验的后端部分，仍待父独审；生产目录/UI 需先解决上述真实迁移链。

父线程原始 GitHub 邮件已确认祖先 75e130d / 8e45ea4 / 016d03e / 8a295f9 的 Android CI 均 FAIL，
原因仍 UNKNOWN，不推断 SDK、代码或额度；与对应主机通过记录分开保存。详情见 ZIP 内原始邮件核对回执。
本片新提交 CI 仍 UNKNOWN，未读取被拒绝的 Actions/log/API 或替代路径。

## 2026-10-17 原文详情的授权查看与明确撤回（原对话框）

正常新安装当前没有创建连接/远端授权的用户写入链；本机范围查看和 LOCAL_ONLY / AI_NONE 切换早已存在。
本片闭合原处理范围对话框“已有其他授权，请先撤销”却无操作的错误引导，不增加配置目录、诊断页或新的授权入口。
库内无连接时如实显示未配置；已有连接元数据也只能显示未核验、不能发送。授权有效性只表示对应来源/配置/用途/时间的元数据匹配。
没有新建连接或 secretRef，没有调用 RecordAuthorizationChange.confirm；生产 bindAi=null 与 INTERNET 移除原样保留。

沿用原文详情的“处理范围”按钮、LocalProcessingController 和同一 ViewModel IO 队列。
按钮渲染时冻结来源版本/原文哈希/source generation 和实际 RecordAuthorizationRepository owner；首次读取不能升级旧代次。
确认前再次校验相同快照与身份。撤回需要单独确认，将该记录改为 LOCAL_ONLY，清除记录级连接和授权快照，原文与人工内容保留。
既有本机策略切换不改变合同；实际写入仍调用 aa616583 已准入的同库端口，不修改 schema、codec、SQL 事务或密钥提供者。

未知提交结果保留原不可变请求，代次刷新后仍可精确核对，不换 nonce 重做。
重放回执以端口返回的当前范围为准；后来已有新授权时不重新撤回，也不显示“当前已撤回”。
关闭、来源删改、仓库 owner 替换或 ViewModel 销毁后，旧按钮、确认和迟到读取不得回填新页面。
现有 Room 11/12 导出/fixture 及 SDK 门仍保留；正式无密钥草案目录/12→13/配置页面仍受阻，不能用旁路存储替代。

本片的产品入口已接线，Compose 渲染和 Android/Room/SQLCipher/Preview/设备验收仍 NOT RUN，无新 APK。
实际 ViewModel/SQL 主机测试使用既有 v8 合同与合成完整配置，不代表生产有可用模型或已经真实配置。
本片完整主机门未通过，实际计数、失败及证据见后附记录；父独审待进行。

本片首轮定向 24 项有 2 个超时：测试使用健康状态 retry 误当 owner 刷新，及把无保证的 StateFlow 发射当计数通知。
改用实际元数据刷新并同步 owner、以真实 IO 队列草稿 ACK 等待迟到读取后，第二轮暴露末尾验证查询已关闭 driver；
改为独立 SQLite 连接重开持久文件。第三轮定向 **24/24 PASS**；三轮日志、失败 XML 和对应源码保全，未放宽超时/断言。

首轮完整执行了 **1039 个唯一方法，其中 1 FAIL**：
`MetadataSourceGenerationViewModelTest.committedDateWithLostAckCanBeConfirmedAfterCloseTrashRestoreReopenWithoutAnotherWrite`
第 105 行索引 epoch 期望 10、实际 11；此前日期版本断言通过，新增 9 项全部通过。
**原因 UNKNOWN**；没有归因 SDK、代码、额度或环境，没有改动该用例、日期/索引逻辑或时限。
同源单例复核 1/1 PASS，不能解释或抹去首次失败；仅再做一次有限完整复核，原始失败闭包与同源指纹保存。

有限第二次完整回归仍 **1039 项 / 1 FAIL**，同一 epoch 断言仍期望 10、实际 11，新增 9 项再次全部通过。
主机完整门明确 **FAIL / 待独审，不能准入**；停止更多重试，不修改或跳过既有断言、用例、时限及日期/索引逻辑。
失败归因继续 UNKNOWN。正常开发分支保全最小实现和失败证据，等待父独审定位，未宣称完整回归或完整 D04 通过。
[失败证据 ZIP](evidence/d04-record-authorization-view-withdraw-host-failed.zip) 包含两次完整原始 XML/日志、同源单例通过、
定向 24 项通过、所有输入与 Git 绑定辅助程序；它不是通过主机验证门的绿色闭包，原验证器按预期仍拒绝失败套件。
