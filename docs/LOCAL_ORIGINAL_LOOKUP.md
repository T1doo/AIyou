# 回忆页本机原文查找

此小切片接续 `301e010a67e494dc1b476ab850795501f07a889a`，落实两份原始方案中“未配置模型仍能找回原文”的本机入口。复用当前可人工浏览的原文快照和既有全文阅读／返回导航。原始主计划和产品架构文档保持不变。

回忆页提供中文、Unicode、英文关键词、精确片段及可留空的日期范围。关键词按 Unicode 空白拆分，全部词须出现，使用与系统语言无关的单码点大小写映射；标点、正则及 SQL 通配符均按字面查找。精确片段保留大小写、空白及换行。不做 Unicode 规范化、同义词、分词或自然语言理解。日期按每条记录保存的 IANA 时区换算，起止两天均包含；错误日期、无命中及原文不可读分别显示。

仅扫描当前保存原文，保持既有列表顺序，包含 `AI_NONE` 原文；不检索捕捉／编辑草稿、旧版本或 AI 派生内容。摘录是原文的精确子串，偏移为 Unicode code point 半开区间，最多240码点，省略号位于引文之外。结果复用原文卡片和完整原文页，不生成或修改事实、待办、原文、版本及附件。

匹配在独立 `Dispatchers.Default` 工作者执行，字面匹配用可协作取消的线性 KMP；每个词仍分别扫描文本，不宣称整条多词查询为单次线性扫描。UI 不执行关键词匹配、SQL 或索引写入。源快照引用、当前输入快照和单调请求身份共同约束发布与结果打开；真实 Activity 还直接读取 ViewModel 当前状态，防止重组前的修订／删除／读取失败窗口打开旧结果。改词再改回、同页重开、切页和销毁后的旧计算／回调不能取得新归属。

查询、日期、模式、结果和返回锚点只保留在当前 composition 的内存。字段直接构造 `TextFieldState` 并以 `remember` 持有；不使用具有保存恢复行为的 `rememberTextFieldState`／Saver，不写普通日志、设置或 Bundle。相关查询／结果诊断字符串脱敏。离开回忆页、设置／演示或清空时同步撤销任务、清空字段及 Undo，并替换字段实例；打开原文仅暂停任务并保留当前查询。返回时仅复用同输入和同源快照的缓存，其他情况重新匹配；实际 key、index、offset 对应同一列表条目，返回过程中改词或再换源即丢弃旧锚点。

保存状态选择依据 [Android 官方 TextFieldState 文档](https://developer.android.com/develop/ui/compose/text/user-input)；列表锚点遵守 [Compose LazyListState 内边距语义](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt)。现有手账样式、宠物与减少动效设置继续沿用，不新增外围入口。

这只是本机原文字面查找，不是自然语言搜索、完整 D06 或已完成 `LOCAL_INDEX`。没有数据库迁移、索引／FTS、网络权限、凭据、真实模型或后台重试队列。未来 D06 的中文索引、别名、证据问答、纠错及性能验收仍 **OPEN**。

新增9个实际生产 matcher／controller JVM 契约和5个实际 `AIyouApp` Compose 契约；门禁要求这些明确方法各出现一次且失败、错误、跳过均为0。验证包括 Unicode 摘录回算、精确空白、捕捉时区／DST、错误日期、旧计算不响应取消、源快照更新、同帧 ABA、读取失败、内存查询恢复边界、大字窄屏可达性，以及确实制造内边距与首可见索引不一致后的完整原文打开和两种返回。具体执行数字、输入指纹、提交 SHA 与同 SHA CI 见交付回执，未执行不记 PASS。

本地使用既有固定 JDK17 和校验过的 JVM Wrapper；未安装 SDK、接受新许可、运行 ADB／模拟器、生成认证密钥或更改拒绝目录。Android 构建、Compose JVM 模拟与 lint 使用既有免费 CI；这些不代替 D02 生产重启、真实设备 UI／可访问性或性能验收。最小后续是合法现有 SDK 与已授权设备条件具备后继续 D02 原生验收；当前仍 **NOT RUN**。

首轮 [CI38485383837](https://github.com/T1doo/AIyou/actions/runs/38485383837) 在提交 `d151ec270d78fa6a3a3a5da01c9e5f89f8556691` 的 Android 编译停止：固定 Foundation 版本对三处直接 `undoState` 调用要求实验性标记。已移除多余调用，继续清空旧字段并替换全新 `TextFieldState`／Undo 实例；生产代码不新增实验性 Foundation 使用或依赖。首轮没有生成本任务 APK，也没有运行新 Compose 用例；失败日志保留。

第二轮 [CI38486833072](https://github.com/T1doo/AIyou/actions/runs/38486833072) 对提交 `02828d04bb7fb4e6612213a9c6229f2aaa497400` 实际构建 APK 且无 INTERNET 权限，执行107项 Compose 用例，其中9项失败。修正空条件浏览时短暂隐藏原有列表导致的锚点丢失；另以实际 host 工作者重现并修正“新来源拒绝完成结果后仍卡在 RUNNING”，只允许仍属当前请求的拒绝结果触发空 WAITING，交由下一次已提交界面同步重新计算。测试通过真实 LazyColumn 滚入离屏状态项，Undo 使用显式 Ctrl 元数据的原生按键并增加有效 Undo 正控制。两处 READY 等待超时保留原条件和2秒上限，新增脱敏状态诊断；不能仅由超时断定原因或预记修正通过。原失败日志完整保留，新提交须由同 SHA CI 验证。

第三轮 [CI38490272450](https://github.com/T1doo/AIyou/actions/runs/38490272450) 对 `860d4790a083784759b13a0a8b395a9e529e40f6` 执行107项 Compose 用例，失败降为3项；原有列表锚点与离屏状态用例已通过。两处恢复仍呈现门禁生成的 WAITING，未据此判定后台原始状态。补充只含枚举、计数和布尔值的 observer 诊断，区分源／输入／归属不一致；每字段只读取一次文本快照，同一引用派生查询字符串与身份。原生 Ctrl+Z 正控制仍被此 JVM 模拟环境当作普通字符，不重试按键组合；改由实际界面传入的三 `TextFieldState` 验证真实 Undo／Redo 管理器，真实用户键必须生成可撤销修改并逐字还原，清空后实例更换、Undo／Redo 均为空，保存注册表与旧回调断言保持。实验性标记仅限测试方法，不将其冒充硬件快捷键或真机验收。恢复问题仍由新提交的实际 CI 裁定。

本地协议复验另保留一项既有传输隔离测试的 `Completed`／`TIMEOUT` 失败。该测试在阻塞独立流尾部期间执行真实 SQL 和新请求，超出夹具1秒空闲期限；仅该活跃独立流增加实际 SSE 心跳，合法关闭定界并在响应结束关闭 socket。其1秒空闲、3秒总期限／服务器截止及全部隔离断言保持，其他默认阻塞流不变；不修改生产传输配置。复验须确认客户端仍消费完整结果，不以发送心跳本身记 PASS。

第四轮 [CI38493938644](https://github.com/T1doo/AIyou/actions/runs/38493938644) 对 `26e8c914343d1cfb47e0e03843b11cb902f2a2de` 仍为107项／3项失败。诊断明确：读取失败恢复停在旧的不可读 committed frame；阅读返回的后台已 READY 且匹配新来源，render 来源仍旧；输入和归属匹配。测试在外部 Atomic observer 的轮询前补 `waitForIdle`，同步 `runOnIdle` 动作之后待提交的界面；原2秒轮询、成功后同步和所有正断言保持，修改／ABA后的旧打开动作仍先在同一 action 内执行。实际 Undo 已执行，但把语义准备输入和随后的用户字符合并撤销为空；仅正控制准备阶段清空实际查询字段历史，再验证独立真实用户修改的 Undo／Redo。产品来源／输入门禁不变，不能把尚未执行的新 CI 记 PASS。

第五轮 [CI38495445948](https://github.com/T1doo/AIyou/actions/runs/38495445948) 对 `5c78ffa4d70b62f4d8c462cbbc0480e884dc4746` 的107项实际 Compose 测试全部通过，5项本机查找方法均实际出现一次且失败／错误／跳过为0；原生27方法及两探针仅编译／DEX清单通过，未运行设备。44步中仅 Android lint 失败：新增测试两个返回 Unit 的私有 Composable helper 用了小写函数名。已统一大写命名及全部调用，不改行为、不忽略 lint 规则；最终交付仍以修正提交的完整同 SHA CI 为准。
