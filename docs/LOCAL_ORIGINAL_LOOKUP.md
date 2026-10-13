# 回忆页本机原文查找

此小切片接续 `301e010a67e494dc1b476ab850795501f07a889a`，落实两份原始方案中“未配置模型仍能找回原文”的本机入口。复用当前可人工浏览的原文快照和既有全文阅读／返回导航。原始主计划和产品架构文档保持不变。

回忆页提供中文、Unicode、英文关键词、精确片段及可留空的日期范围。关键词按 Unicode 空白拆分，全部词须出现，使用与系统语言无关的单码点大小写映射；标点、正则及 SQL 通配符均按字面查找。精确片段保留大小写、空白及换行。不做 Unicode 规范化、同义词、分词或自然语言理解。日期按每条记录保存的 IANA 时区换算，起止两天均包含；错误日期、无命中及原文不可读分别显示。

仅扫描当前保存原文，保持既有列表顺序，包含 `AI_NONE` 原文；不检索捕捉／编辑草稿、旧版本或 AI 派生内容。摘录是原文的精确子串，偏移为 Unicode code point 半开区间，最多240码点，省略号位于引文之外。结果复用原文卡片和完整原文页，不生成或修改事实、待办、原文、版本及附件。

匹配在独立 `Dispatchers.Default` 工作者执行，字面匹配用可协作取消的线性 KMP；每个词仍分别扫描文本，不宣称整条多词查询为单次线性扫描。UI 不执行关键词匹配、SQL 或索引写入。源快照引用、当前输入快照和单调请求身份共同约束发布与结果打开；真实 Activity 还直接读取 ViewModel 当前状态，防止重组前的修订／删除／读取失败窗口打开旧结果。改词再改回、同页重开、切页和销毁后的旧计算／回调不能取得新归属。

查询、日期、模式、结果和返回锚点只保留在当前 composition 的内存。字段直接构造 `TextFieldState` 并以 `remember` 持有；不使用具有保存恢复行为的 `rememberTextFieldState`／Saver，不写普通日志、设置或 Bundle。相关查询／结果诊断字符串脱敏。离开回忆页、设置／演示或清空时同步撤销任务、清空字段及 Undo，并替换字段实例；打开原文仅暂停任务并保留当前查询。返回时仅复用同输入和同源快照的缓存，其他情况重新匹配；实际 key、index、offset 对应同一列表条目，返回过程中改词或再换源即丢弃旧锚点。

保存状态选择依据 [Android 官方 TextFieldState 文档](https://developer.android.com/develop/ui/compose/text/user-input)；列表锚点遵守 [Compose LazyListState 内边距语义](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt)。现有手账样式、宠物与减少动效设置继续沿用，不新增外围入口。

这只是本机原文字面查找，不是自然语言搜索、完整 D06 或已完成 `LOCAL_INDEX`。没有数据库迁移、索引／FTS、网络权限、凭据、真实模型或后台重试队列。未来 D06 的中文索引、别名、证据问答、纠错及性能验收仍 **OPEN**。

新增8个实际生产 matcher／controller JVM 契约和5个实际 `AIyouApp` Compose 契约；门禁要求这些明确方法各出现一次且失败、错误、跳过均为0。验证包括 Unicode 摘录回算、精确空白、捕捉时区／DST、错误日期、旧计算不响应取消、源快照更新、同帧 ABA、读取失败、内存查询恢复边界、大字窄屏可达性，以及确实制造内边距与首可见索引不一致后的完整原文打开和两种返回。具体执行数字、输入指纹、提交 SHA 与同 SHA CI 见交付回执，未执行不记 PASS。

本地使用既有固定 JDK17 和校验过的 JVM Wrapper；未安装 SDK、接受新许可、运行 ADB／模拟器、生成认证密钥或更改拒绝目录。Android 构建、Compose JVM 模拟与 lint 使用既有免费 CI；这些不代替 D02 生产重启、真实设备 UI／可访问性或性能验收。最小后续是合法现有 SDK 与已授权设备条件具备后继续 D02 原生验收；当前仍 **NOT RUN**。
