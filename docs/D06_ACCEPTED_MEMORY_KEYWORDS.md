# D06 已采纳事件、当次情绪及人工备注：关键词找回设计

设计基线／受测产品：`2010bffd816356e456891cce38ff09d4cf58a19d`。
本提交只增加设计，未实现、未测试新增类型、未推送或启动 CI。
遵循两份原方案 §5.2、§8、§10.1–10.4，以及既有
`D05_SOURCE_MEMORIES.md`、`D06_UNIFIED_KEYWORD_LOOKUP.md`。
原四类限定 ACK 及 CI39760393071 不覆盖本片。

## 已核现状与最小范围

`SourceMemoryContent` 已有 EVENT／OCCASION_EMOTION、人工 assertion、逐字 reference 和
最多200码点的 note；每条最多8个内容版本，纠正后新版本重新 PROPOSED。
`SourceMemorySnapshot.current` 是最后内容版本，不能搜索历史 ACCEPTED 或 priorReview。
`SqlSourceMemoryRepository` 使用既有 profile2 独立用途密文，写入每来源最多20条 READY；
inbox 每批20条认证后再筛审阅状态，不是关键词全量结果。
统一找回目前只有 ORIGINAL／AUDIO／IMAGE／PREFERENCE，确有产品缺口。

本片在现有范围菜单增加“已采纳事件与当次情绪”，ALL 纳入这一类。
仅用户主动、区分大小写的逐字查询；沿用现有64码点／Unicode验证，不分词推断或改写。
不增加模型、SDK、索引库、网络、权限、存储表、密钥用途或备份格式。
不自动分类情绪，不把它扩成稳定偏好／人格／关系，不创建任务或通知。

## 当前资格、命中与身份

只有 profile2、物理 READY、来源当前有效且 payload 全部认证通过的条目可参与。
当前版本必须是 ACCEPTED；PROPOSED／REJECTED／REVOKED／SUPERSEDED、INVALIDATED、
来源旧版本、AI_NONE、回收站／任意阶段永久删除及 generation/hash 失配不产生结果。
旧 ACCEPTED 被纠正后，即使其文本仍匹配，新 PROPOSED 未单独采纳也不得命中。
eligibility 沿现有来源与状态规则在读取前过滤；当前资格内的损坏条目使整个所选查询失败，
不能跳过损坏或非匹配行，以部分结果或假空列表替代完整认证。

分别计算 `reference.quote.contains(keyword)` 与 `note.contains(keyword)`。
不把分类名、assertion、来源全文的其他段落或历史版本当作本类型的命中字段。
同条两字段都命中仍是一项，保留 QUOTE／NOTE 两个命中标记；字段不是两份记录。
证据稳定身份为 `M:memoryId`，不按文字或 kind 去重；不同线索ID、原文O、偏好P仍各自保留。
纠正可能改变 kind，但身份不转移；行版本／内容修订／ACCEPTED 审阅及全文摘要绑定新证明。

新增不可变、仅含当前版本的匹配投影：memoryId、rowVersion、contentRevision、kind、assertion、
reference、note、命中标记以及完整来源绑定。不得把8版历史全部留在每个搜索结果中。
捕获时仍调用既有 payload 校验，认证全部历史和最后操作回执，再复制当前投影。
投影及私有证明的 toString 继续 redacted；不把 query、note、quote 写日志、Bundle 或状态恢复。

## 分批认证、容量与联合证明

不把其他 profile 的128条或全备份的4096表行上限变成 profile2 的写入／查询总行上限。
数据库中合法非匹配行变多，不应使某个唯一匹配永久无法搜索。
采用已有主键 id 的 keyset 扫描，不建索引，也不循环 inbox 页面后拼成假完整集。
同一 counted SQL 事务内按 id 全序读取当前资格集合，每批最多20条 metadata；排序和
keyset 比较使用同一 SQL BINARY collation，不在 Kotlin 中用另一字符序自行推进游标。
先只读 metadata／长度，再取已验长度的单行 BLOB，不先加载任意大密文再检查。
每条预检 payload 在29..32797字节，来源正文在 SQL 取全文前预检 UTF-8 <=400000，
取后核 UTF-16 <=100000、Unicode、真实 hash、当前修订、政策、捕获时间／时区和 generation。
每次仅保留一份来源正文及当前解密行；释放上一行／批的 ByteArray，避免全库正文缓存。
20行密文批上限655940字节，另有单个原文、解密明文及有界匹配投影；
这只是显式缓冲容量证明，不宣称 JVM 峰值、延迟或设备性能已测。

原有每来源20 READY 是保存规则，不是“只取前20”的搜索截断。
新增查询结果预算至多1000个 profile2 当前匹配投影，沿用原文1000命中规模作为交互预算；
最多520000码点的 quote+note（最坏2080000 UTF-8字节），元数据按现有ID等字段限额验证。
1001个匹配必须整组失败，提示“已采纳线索命中过多，请使用更具体的关键词或切换范围”；
不能显示前1000或改变数据库。该预算只限制一次完整结果，不拒绝保存，不按非匹配总数拒绝查询。
现有四类最多1384项，新结果最多1000项，聚合最多2384项／组、绑定至多2384个来源，
仍先取得完整匹配集，按 captured 降序／entryId 升序分组、组内稳定证据键排序，每页20组。

扫描中维护长度前缀规范编码的滚动 SHA256 与行数，覆盖完整资格集合，包含不命中的
PROPOSED／REJECTED／REVOKED 等 READY 行，避免漏掉审阅或备注变化。
每行纳入 id、profile、物理状态、sourceId/revision/hash/generation、rowVersion、mutation、
created、密文摘要，以及当前来源元数据；另绑定同事务的 local_search_epoch。
不会把任意调用者提交的摘要或 ID 列表当作证明。摘要是同 owner 私有 proof 的集合回执。
不匹配行不长期保留其明文、历史或无界来源列表；匹配投影来源加入公开 bindings 供现有 UI 失效检查。
全部非匹配来源资格仍由私有完整集合回执／epoch覆盖，不能因不在 bindings 就免于最终验证。

初次扫描事务完成后，再与原四类已认证集合进入既有同一最终 SQL 写事务：
逐批重算 profile2 完整资格集合回执、epoch，并重新核匹配投影／来源；不逐行重新保留全历史。
最终扫描也必须读取全部当前资格行的真实来源正文并重新计算 hash（同样先验长度），
包括非匹配行，不能只信 SQL 中存储的 contentHash 或只重验命中来源。
其他类型的媒体认证仍在最终事务前完成。集合不一致、空变非空、外部提交、认证失败、
最终提交失败或 ACK 未确认均不发布部分结果。既有私有发布票据仅在共同事务提交确认后可见。
翻页和点击先进行新鲜完整认证，再在同一最终事务比对原 proof 与其他类型，不能只核当前20组。
外部非协作文件／数据库修改的瞬时边界仍按现有 reader 再认证及来源校验处理，不宣称文件锁原子性。

扫描工作随总行数线性增长，不能承诺与库大小无关的延迟。
给 repository search／resolve 增加显式、只读的当前请求检查参数（默认调用兼容保留）；
真实实现传入 profile2 每批、每条解密前后及最终集合复核，检查 owner/session/request 和退休状态。
每次 search／resolve 传入本次新请求的检查；不能把捕获时的检查闭包封存在 snapshot／current
中，否则翻页／点击推进 request 后会误拒绝自己的旧 proof。集合 current 接收调用方本次检查。
controller 的检查只读取当前状态／代次，不取导航锁，不在 SQL 内调 UI 导航。
旧 A→B→A、关闭／后台／dispose 在下一检查点退出，不靠只禁用按钮或事后丢弃无界旧扫描。
检查抛取消／过期后，由实际 SQL finally／回滚确认释放租约；不能把检查或协程取消当作资源 ACK。
`resolve` 的当前 UI 检查止于最后的 private admit 之前；admit 后可能有意清空 UI session，
不能再用旧 `owns(session)` 检查误杀正常 handoff。提交和后继确认继续用既有票据／lifetime，
沿用 navigation→lifecycle 仲裁及实际 commit ACK，不改 a447f39 已闭合的交接窗口。

## 界面、来源失效与回跳

卡片标题分别为“已采纳事件线索”“已采纳当次情绪线索”，标注人工分类／assertion；
明确“采纳是人工审阅决定，不证明事件发生或人格特征”。捕获日期不叫事件发生日期。
QUOTE 命中展示“逐字依据命中”；NOTE 命中展示“人工备注命中 · 非原文”，各自高亮各字段。
两字段同时命中展示两块、只保留一个动作；绝不把 note 拼进原文摘录或分配伪原文偏移。

动作沿用“查看精确原文”。QUOTE 命中定位已验证 reference 内首次逐字命中的码点范围；
只有 NOTE 命中则定位其已验证 reference 全段，并明确“原文定位的是依据，不是备注文字”。
精确行／内容／ACCEPTED 证明通过后才走既有导航 handoff；原文 reader 再核来源。
跳转不采纳、不写备注、不创建任务。纠正／撤回仍在既有详情审阅页单独确认。
返回统一找回重新认证并从第一页查询，不恢复旧证明或旧人工备注。

现有 `canRetainUnified` 已与 sourceMemories／memoryInbox 等修改页互斥；实施核所有入口，
在已采纳线索的编辑／审阅准入处先走实际 unified retirement，推进 lifetime 并清 returnSeed，
不能只把 unified UI 清 null。不要在可重复执行的 StateFlow CAS transform 内调用退休副作用。
本 VM 已知任一来源列表／政策／generation／可用性变动，选中本类型时保守退休整个查询，
覆盖未保留明文的非匹配源；仍不声称能在观察前感知另一个非协作 SQL 连接的修改。
观察事件按观测到的 session/request/lifetime 交付退休：旧 UI 已清 null 时仍可取消同次 handoff，
迟到事件若已归属旧会话不得清掉新查询或新 seed；纯 CAS 内只计算变化，不执行副作用。
后者在任何结果发布、翻页、点击及返回重新查询的共同 SQL 复核拒绝；已观察变化立即清内容。
迟到 IO、比较、导航 offer、旧关闭回调不能恢复备注、seed、已采纳状态或 B 路由中的 A 内容。

## 格式与现有容量合同

profile2 密钥用途、AAD、payload、表结构及完整备份 v12 字节格式不变；查询和 proof 不备份。
保留完整备份原有4096候选表行／64MiB媒体边界，不借此限制正常关键词扫描，也不扩大恢复额度。
恢复后的来源政策仍按原路线规范化，owner 更换后旧 proof 必须拒绝。
选定记录包原本不包含这些线索，本片不扩张该导出路线。

源码显示 profile2 full validation 当前逐行认证，但未聚合验证写入规则“每源20 READY”。
为免本片依赖不成立的集合合同，实施同时补复用该既有20规则的共享集合校验：
创建、关键词读取、整库导出／库验证、portable恢复准入均按 (entryId,sourceRevision,profile2)
计 READY 行；INVALIDATED 不挤占该额度。不是新业务限额，也不改变4096备份上限。
portable恢复在写恢复意图前拒绝21条；目标不变，不静默删第21条；现存违约库返回明确不可用，
保留全部原始字节。不得只在 UI 或新查询路径补限，所有实际全备份恢复入口必须复用同一检查。

## 实施与必要验收

最小生产变更为 domain 的第五类 scope／当前投影和请求检查、profile2内部完整集合读取、
SqlUnified 联合 proof、原加密 owner 注入、controller 来源定位／退休、原 Dialog 的展示分支；
检查 sealed when 的 MainActivity、实际 Preview 及受控 UI 端口，同步两套 host 源集。
不先做全局样式重构或新搜索架构，不修改四类既有内容语义；沿用已整合视觉 token 和焦点样式。

实现后才执行以下真实定向验证，并给精确 SHA、XML、脏树及未推送差异交独审：

1. 中文单字／短语、Unicode／换行、非ASCII／补充平面ID跨批游标、否定引用／含义未确认保留；quote-only／note-only／both，
   仅 current ACCEPTED；纠正后待审、撤回、拒绝、历史 SUPERSEDED 不命中，再采纳新版本才出现。
2. 同文不同线索／类型保留、同条双字段一项、跨原 inbox 旧页、21组前后页稳定；
   稀疏匹配跨129及4097条 profile2，证明不复制128或备份4096总量限制。
3. 新投影1000成功／1001整组拒绝且不写源；至少两个批次，峰值批大小与单行读取大小可观察；
   20每源成功、21在保存／恢复合同边界拒绝，恢复前目标／意图不变，失效行不占额度。
4. 非匹配行和屏外行的 ACCEPT／REVOKE／CORRECT、空变非空、source revision/hash/generation、
   AI_NONE、回收站、永久删除；认证后的独立连接提交、共同提交失败／丢 ACK，所选全组不发布。
5. 损坏当前资格内的密文、历史 payload／引用或来源正文，整组失败、不显示旧 note、不过早报空。
6. 批次／解密／最终复核屏障下换词 A→B→A、取消／后台／dispose／owner排空；旧扫描及时退出，
   SQL 实际释放前不假 ACK；admit 之后正常原文导航不被新检查误杀，旧offer不复活seed。
7. 原文页纠正／撤回与重返查询，旧 reader／页按钮／迟到回调无效；note-only 不伪高亮原文词，
   200%窄屏／横屏滚动、实际键盘模式下焦点／Enter、准确原文与零写入断言。
8. 真实 profile2 加密冷重开、v12全备份往返／所有恢复入口，8版与审阅关系保持、旧owner proof拒绝；
   原有超过备份总量的库仍按旧边界拒绝备份，不借新功能绕过或提高额度。

设计独审通过后才实现，运行候选再独审，随后正常开发分支推送并只启动一次必要的原40分钟 CI。
本设计不重跑无变化套件、不改 main、不接受新 SDK 条款；设备／真实OCR／TalkBack／性能仍未验。
