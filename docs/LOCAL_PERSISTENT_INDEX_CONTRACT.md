# 持久化本机原文检索：最小合同

对应两份原始方案 F05、§10.4、D06 的有限本机检索切片。基线为
`07ef76f4193e76095203bf308d8cd622ce7dcc8f`。原方案保持原样。当前字面检索仍是
内存扫描，LOCAL_INDEX 仅 PENDING；本次补持久化索引和原文定位，不实现模型问答、
embedding、自动别名或摘要召回。所有处理 LOCAL_ONLY，只使用合成回归数据。

## 唯一加密边界与版本

使用现有 Room + SQLCipher records.db 和同一 DrainingRecordsSqlDatabase owner。
不创建未加密的独立索引数据库、普通文件、DataStore 或公共索引导出。中文词元、
英文词、数字、手工标签、FTS shadow tables、索引元数据与作业都在同一加密数据库
及其 SQLCipher WAL 内。标识、查询、正文和词元不进日志或公共产物。查询、正文、
词元、proof、运行 profile/hash 和新定位 range 不进 SavedState；既有导航可保存
非秘密源 ID 以重新读取当前记录，不保存旧正文或索引/定位授权，不能靠恢复 ID
复用旧 proof。该既有导航行为的范围没有扩大。
host SQLite 仅为合成、测试专用 SQL 驱动，不作生产加密证明。

Room v7 增加三张受管理表：FTS4 词元表、每原文索引版本表、单槽全局索引 epoch。
6→7 只增加可重建派生索引及失效触发器，保留原文、历史、草稿、标签密文、任务、
附件、授权和所有非 LOCAL_INDEX 完成态回执。不启用 destructive fallback。
实际 v7 Room/KSP 导出通过已有许可 CI 产生，保留 v2–v6 原始 fixture。首次生成
只能作为 bootstrap；提交实际生成字节后必须再跑同 HEAD CI，不能手写假称 KSP。

FTS4 使用 simple tokenizer；应用层预先产生仅 ASCII 字母/数字的独立词元，避免
Unicode tokenizer 差异和 MATCH 注入。依据为 Android Room Fts4 与 SQLite FTS3/4
官方说明。无需新依赖、SDK、许可证、凭据或权限。

## 规范化和真实原文核验

tokenizerVersion=1。按 Unicode code point 遍历，沿用现有 locale-independent
simple case fold；不去声调、不做 NFKC、不改原文或高亮偏移。汉字连续段产生重叠
双字词元；非汉字字母/数字段产生完整词词元、数字段词元及补充双字符词元，以保持
现有英文子串字面查找的召回。词元使用固定格式 code-point 十六进制编码；其编码
可逆，所以仍视为原文敏感数据，只存同一加密数据库。原文与手工标签分别列。

Character 的 Unicode 表会随运行平台更新，所以固定规则版本不足以证明旧词元
兼容。tokenizerProfile 在首次 IO 索引/查询前（进入 SQL 事务前）缓存计算，遍历
0..0x10FFFF，对实际 fold、fold 后字母/数字/Han 分类、原码点类别和空白规则做
完整 SHA256 行为指纹；只含规则元数据、不含用户文本。epoch 与每个文档均绑定
profile。UNKNOWN 或运行 profile 变化时所有可见当前源回退，下一批先清派生表、
单调递增 epoch 并绑定新 profile 后有界重建；旧查询 proof 不可复用。此一次性
计算的原生延迟/功耗还未实测，不以 host 时间冒充设备性能。

查询与索引使用同一规则。只有可安全表达为有限个双字符词元的关键词查询才走
参数绑定的 FTS4 MATCH；中文单字、短名称、标点、精确短语和过长词元表达式使用
有界当前原文候选回退。索引只召回候选；现有线性字面 matcher 再按当前原文核验
所有词/完整短语及日期/准确手工标签，纠正 n-gram 误召回。手工标签用于明确标签
筛选；不把标签、摘要或推测伪造为原文引文。不调用 FTS5 的 bm25 等函数。

排序由应用实现：完整连续查询短语优先，再按原捕获时间降序、ID 稳定排序。
位置和摘录始终为当前原文的 code-point 范围；原文定位不读取历史文本。
日期（使用捕获时区）及准确手工标签先核验后累计候选；累计第 1001 条才明确要求
收窄范围，不让无关候选挤掉符合筛选的结果。候选分页读取，每次至多 100 条；
独立扫描预算 10000 条，预算耗尽且仍有未检查源则明确失败/要求收窄范围，绝不
静默截断或报告“没有找到”。
一次查询另有累计预算：最多 1000000 UTF-16 单元及 4000000 UTF-8 编码字节，
包含读取的正文和标签；每页也受剩余累计预算约束，不把预算因换页重置。完成所需
预算不足就整体 FAILED/要求收窄，不能缓存或发布部分 READY。
query/日期/标签完全只驻内存。检索不参与 AI 授权，也不把 AI_NONE 记录发送模型；
用户主动查找自己保留的当前原文仍可用。

## 作业、失效和有界恢复

每个索引文档绑定 sourceRevision、正文 hash、手工标签 version、visibility
generation、tokenizerVersion、tokenizerProfile。索引 epoch 单调递增且溢出失败；
查询回执绑定 epoch、根 profile 和当前运行 profile。
索引成功只确认当前 LOCAL_INDEX 作业，绝不运行 PACKAGE_RESTORE/PACKAGE_ORIGIN、
ORIGINAL_EDIT_COMMIT、RECYCLE 等完成态回执。

原文新增/编辑、手工标签插入/替换/删除、visibility/授权 generation 变化、回收站
和恢复均在同一真实写事务触发索引失效：删除旧 FTS 和元数据，递增 epoch，只
重置已存在的同源/当前 revision/operation=LOCAL_INDEX 作业为 PENDING；已删原文
不排入可见索引。触发器不得提前 INSERT canonical job，否则会与普通保存或包
恢复随后执行的 DAO insert 冲突。缺失作业仅由迁移或 worker 在其事务补登记，
worker 同样识别 missing metadata 为待处理，不能因 missing job 漏掉原文。
原文 hash/版本和
标签变化不等后台索引完成才失效。索引排队不成为保存成功的前置条件。

actor 每次最多处理 8 条、最多 400000 UTF-16 单元；读取下条原文前通过数据库
UTF-8 字节长度（含 NUL）加最大标签长度保守预检累计预算，避免读取越界正文后
才停；因此可能提前退回队尾。批次事务内读取当前可见正文、
真实标签 AEAD、版本/hash/generation，写 FTS 和元数据，再确认该源当前作业。
出错整体回滚并保留 PENDING，停止自动重试；用户明确重试本机读取/重建才恢复。
每批退回已有队列尾，捕获/编辑不等全库完成。停库先封闭并 drain 同一 owner。

启动和源变化后自动排入有限批次。索引缺失或 tokenizer 版本不符只重建派生表；
不删除原始数据或未知文件。批次期间，查询合并仍未索引的当前原文候选，所以不因
索引尚未完成而返回假空列表。显式重建只清索引并重新登记当前可见源，随后有限
批次；事务失败保留原索引或完整回滚，不破坏原文与完成态回执。
未索引回退包括缺失元数据、缺失/重复 FTS 行、任一 revision/hash/tagVersion/
visibility generation/tokenizerVersion/tokenizerProfile 不符；已确认 job 不代表文档有效。
坏正文/hash、标签 AEAD 失败整体失败，不跳过坏源后返回假空。
重建递增 epoch 而不归零；epoch 行缺失、非法或溢出失败。多个当前 LOCAL_INDEX
作业均在同一批次正确收口；所有非 LOCAL_INDEX 完成回执逐字节保留。

## 查询/定位所有权

查询在同一 SQL 事务取得 epoch、候选当前原文及标签快照，严格核正文 hash、
sourceRevision 和 visibility，排除 tombstone。最终 epoch/源绑定验证与实际
owner/StateFlow CAS 发布必须处于同一个 counted SQL 写事务/lease 边界，阻止
独立连接在验证和实际发布之间提交，不采用“检查后释放事务再发布”。SQL 与完整
验证均在 IO，不能在主线程做阻塞查询。结果还受现有
输入 generation、entries identity、当前标签 snapshot、存储 owner identity 和
composition/session owner
保护。修改查询/标签/日期、同条件 ABA、导航、取消、读库失败和 dispose 都拒绝
旧结果；不把未知或尚未确认索引状态缓存为成功。
事务 callback 后 commit/lease 退出失败时撤销该 request 已发布的结果和缓存，
但不撤销后续新 generation。失败/条件验证结果也保留对应 owner，避免自动重试循环。

点击命中后通过 IO 在同一 counted SQL 写事务/lease 中重新验证 query epoch 和
该源完整版本绑定、读取当前可见原文并执行实际定位发布；释放事务前检查同一活跃
结果/输入/owner。失败/编辑/回收站/替换拒绝旧摘录，
不通过旧 UI lambda 直接打开旧正文。生产队列内源修改同步刷新查询所有权；独立 SQL
写入由真实 epoch/resolve 检查挡住。保证仅截至实际发布的线性化时刻；此后独立
提交由下一次查询/resolve 或源失效观察处理，不承诺一次校验可保持永久新鲜。
回到结果列表也重新核所有权。测试必须在 epoch 读取后→实际 CAS 前注入独立连接
更新和导航/同输入 ABA：已提交的独立更新拒绝旧结果，lease 内被阻止的独立写只能
在发布结束后提交；导航/输入失效仍拒绝定位，且没有主线程 SQL。
定位 callback 后 commit/lease 退出失败也必须撤销：controller 核对实际 resolve
返回/异常；Root 只按该次位置对象 identity 与发布时的导航 generation 撤销，
不能清除之后的新导航。搜索条件留在当前内存中以便重查，不伪称失败定位已确认。
真实命中另有独立 matchStart/matchEnd code-point 范围（与上下文摘录范围分开）；
只在当前 ID/revision/hash 相符时装饰原文并根据实际 TextLayout 行位置滚动。
源版本变化后旧高亮消失。查询使用同一个直接创建的 memory-only TextFieldState，
保留 Foundation 的真实 Undo。实际文本变化前，锁内仅更新非响应式 epoch 与
opaque 输入 identity，不改 StateFlow/Compose/TextFieldState；与发布 CAS/定位
共用 controller 锁，拒绝发布中的重入。提交前的 sync 不能以新 epoch 查询旧文本，
后续真实输入 identity 变化才解除 pending。Undo/Redo 键在 transformation 外先
退役 owner，再调用实际 undoState，完成后显式唤醒查询（含 no-op group）。
不依赖 InputTransformation 覆盖 Undo，不把此私有同步设计说成官方 API 的保证；
须取得实际 publication/输入 ABA/键盘 Undo 证据。Root 还核精确字段 owner identity，
拒绝清空替换后的旧编辑回调。输入框明确退出 SaveableStateRegistry，清空时换
编辑 owner，避免旧 Undo 内容重现。必须实际核 raw SavedState 与真实键盘 Undo/Redo。
键盘回归还须从稳定 READY/BROWSE 的实际 observer epoch 取基线，并在真实 View
Ctrl/Shift 键事件后要求 epoch 增加，不能只靠文本历史变化推断退役门已执行。
该单个键盘/恢复隐私 JVM case 仅为 Magnifier 的 show/update/dismiss 使用视觉 shadow，
避开已保全日志中 popup Surface=null 的 JVM teardown 路径；不替换编辑、Undo、
查询 owner、导航或 SavedState。此 case 不证明设备放大镜绘制/销毁。

## 必须取得的证据与边界

生产 tokenizer / SQL / 触发器及查询代码由 host 编译；实际 FTS4 SQLite 执行中文、
英文数字、单字/精确/标点回退、Unicode 摘录、误召回核验、版本更新、标签密文、
回收站/恢复、新副本恢复、并发事务、cold reopen、失败回滚、owner drain、旧查询
与迟到定位回归。控制器和 Compose 检查真实生产入口和有界排队行为。

正常开发分支提交/推送，冻结同 HEAD CI、实际 XML 门禁、源码指纹、APK 摘要和
独立审查。没有设备执行则原生 Room FTS4/SQLCipher、Keystore、生产重启、性能
和完整 D06/D08 都保持 OPEN / NOT RUN。编译、host SQLite 和 APK 不能冒充原生。

官方参考：
- https://developer.android.com/reference/androidx/room/Fts4
- https://www.sqlite.org/fts3.html
