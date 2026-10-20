# D07 R2a：两项P2持久合同补充（待复审）

设计基线：`3cacbe62dd428bbc26e2b7e10b7a0d731cf61deb`；生产基线仍为已ACK的
`26d50166436a71240265b14d9716a153e6447504`。
父级已ACK R2a方向，但明确要求本补充独审后才实施。本轮不修改生产代码。
本文覆盖[R2原设计](D07_R2_RUNTIME_CAUSAL_DESIGN.md)中仅按物理mutation ID核对系统结果的不足；
保留PENDING真回调原子受理、锁态漏报边界、共同执行门和至多一次发布尝试的既定方向。

## P2-1：独立、可认证的event／claim／结果回执

### 身份和规范化请求

系统回执是`ManualTaskCipher.Payload`内独立于R1 UserReceipt的有界RuntimeLedger；
不加入`ManualTaskSnapshot`或`TaskReminderMutation.task`的公开请求字段。
R1的`reminderRequestProof`、`reminderUserProof`、用户requestProof及decisionHash输入字节保持原样。
运行时的新增摘要有自己的命名空间，不拿R1用户mutation ID充当event或claim身份。

每种ID采用`e1:<nonce32hex>:<digest64hex>`、`c1:...`、`r1:...`，各100个ASCII字符，
nonce为一次生成的128位随机值；digest是该种请求的完整规范化字段及nonce的SHA-256。
编码采用版本化命名空间、明确操作、严格UTF-8长度前缀、固定大端整数／0或1布尔与显式optional标志，
禁止字符串拼接、JSON顺序依赖、宽松Unicode或浮点时间。摘要不递归包含整个ID，包含ID种类与nonce。
ID是不可变请求绑定，**不是访问授权或平台触发证明**。
三种摘要命名空间分别固定为`AIyou/task-runtime/event/1`、`AIyou/task-runtime/claim/1`、
`AIyou/task-runtime/result/1`，操作分别为ACCEPT、DECIDE_POST、ACK_POST；按下表列出的字段顺序编码。
摘要前缀具体为text(namespace)、i32 codecVersion=1、i32 policyVersion=1、text(operation)、
text(nonce32)，随后是表中其余持久输入；版本为i32，其余时间／行版本为i64，布尔为u8。
slotCodec=1的解码器重建这些固定前缀，不从未编码默认值或当前全局策略猜测旧摘要。

| 请求 | 参与摘要、保存并精确核对的全部持久输入 |
| --- | --- |
| EVENT／ACCEPT | 编码／策略版本、e1 nonce、完整token(task ID／reminder ID／scheduleVersion)、expectedTaskVersion、明确schedule(date／time／IANA zone／offset)、mode、observedAtMillis、能力观测checkedAtMillis及platformConnected／notifications／exactAlarms／channelEnabled四字段 |
| CLAIM／DECIDE_POST | 编码／策略版本、c1 nonce、event ID及event请求摘要、相同完整token／schedule／mode、expectedTaskVersion、claimAtMillis、该次新能力观测的时间和四字段、固定通知规格GENERIC_V1 |
| RESULT／ACK_POST | 编码／策略版本、r1 nonce、event ID／摘要、claim ID／摘要、相同完整token、expectedTaskVersion、observedAtMillis、实际调用观察结果枚举、脱敏原因枚举；仅NO_ATTEMPT_CAPABILITY_LOST时另有该次能力观测的时间与四字段 |

时间均为非负整毫秒；真实时钟变化不由格式校验伪造成严格递增，行updatedAt仍取安全max。
token、日期和模式沿用领域校验，expectedTaskVersion沿用有界正版本。
通知规格不带标题／正文／联系人；通知tag/id由token和规格版本确定。
能力快照仅证明代码当时观察到了这些值，不证明用户曾点击拒绝或OS一直保持资格。

接受事务前固定原EVENT请求；未知COMMIT不得重读能力后改写同一请求。
后续能力变化是新的CLAIM观测，不能更改原event的时间／版本／能力字段。
同ID携不同token、observedAt、expectedVersion、计划、能力快照或其他字段，一律CONFLICT，不写库。
ID的digest必须从完整请求重算，因此即使旧槽已按规则回收，借同一ID修改token／时间仍先被拒绝；
不能在另一任务行把它当成全新event。前缀、大小写、长度与命名空间也严格核验。

已有同ID同摘要时，只读返回认证的当前回执状态；不能因物理行版本已被合法系统ACK推进而误判“从未提交”。
新请求首次写仍要求自己的expectedTaskVersion命中CAS。重放不能把旧expectedVersion换成当前版本，
也不能绕过后来的用户撤回／完成／计划变化。不同event命中同一已观察token只返回ALREADY_OBSERVED。

### 槽内内容及关联不变量

每个槽只对应一次token代次，保存共同的token／schedule／mode一次，并有：

1. 必选EventReceipt：完整EVENT请求、其摘要和不可变接受决定(ACCEPTED或TRIGGER_BLOCKED)。
2. 可选ClaimReceipt：完整CLAIM请求、其摘要及不可变决定(GRANTED或BLOCKED_CAPABILITY)。
3. 可选ResultReceipt：完整RESULT请求、其摘要及不可变调用观察(POST_RETURNED、CALL_FAILED、
   CALL_OUTCOME_UNKNOWN或NO_ATTEMPT_CAPABILITY_LOST)。BLOCKED_CAPABILITY的claim禁止有发布结果。
4. 分开的单向lifecycleClosed和tokenRetired标志，各有首次封闭时间与原因；它们不覆盖上述事实，
   已因能力受阻封闭后再被用户撤回，也保留原封闭原因，不把UNKNOWN改写成POST_RETURNED。

claim引用的event ID／摘要必须精确属于同槽；result同样必须引用该槽的原claim及event。
所有引用都与重算摘要比较，token、日期、mode必须一致；禁止孤儿claim／result、重复ID、重复token槽、
不可能的阶段、错误类型交叉使用ID或尾随字节。验证只在整个认证payload完整解码后进行。
每槽task ID必须是当前行ID。当前token槽与当前计划／阶段相符；旧代次槽必须已退休，保留原计划，
不能强迫其时间等于用户后来修改的日期。旧v1–v5已经存在的TRIGGER_ACCEPTED可以无运行时槽地读取，
但不能因此制造event／claim或发布资格；新的DELIVER／POSTING等运行时阶段必须有相应认证槽。
只有GRANTED claim能有RESULT；POST_RETURNED只来自原活跃协调器观察到的那一次平台调用返回，
不是OS签名的送达凭证，更不能由扫到通知列表或时间到推断。

每个阶段最多填入一次子回执。首次RESULT写入须同时满足原活跃调用对象、当前token、POSTING、
原claim ID／摘要及CAS；后续只有**同一完整RESULT请求**可以只读重放，另一结果或另一个result ID冲突。
用户已退休该token或生命周期结束后，不能再补一份此前未提交的成功结果；只可读取已有结果，
或把无结果的事实保守封闭为未核实。这样不会因迟到结果重启旧状态或复活待办。

### 不依赖lastMutationId，不从持久证明重建资格

`lastMutationId`继续只用于物理写／AAD。原event、claim、result身份在独立槽中保留。
RM_ACK、旧代次CANCEL ACK及其他无关系统写必须按原值携带整个RuntimeLedger，不能覆盖／清空子回执。
v6重新seal不得通过默认空ledger参数遗漏历史；生产写路径须显式携带或按下述规则退役原ledger。
认证读回按槽内完整ID+请求摘要核对，不要求最近物理操作仍是RM_FIRE／RM_CLAIM／RM_POST。

第一次claim SQL调用前，原活跃协调器创建内存`LiveClaimAttempt`，绑定原event意图、owner lease、
进程／准入epoch和claim意图；其一次调用标志在进入notify之前消费。
claim COMMIT未知时，认证GRANTED回执只能激活这一个**已经存在且未消费**的内存对象，
不得从DB重新new一个发布资格。对象在操作结束、锁态更替、owner退休或进程死亡时永久销毁。
相同持久claim即便可被精确读回，冷owner、重放WorkItem、新会话和ALREADY_OBSERVED都拿不到新对象。
持久回执不保存可恢复的LivePostPermit／lease，也不把nonce或摘要当能力凭据。

原操作内平台结果已知、仅结果COMMIT未知时，可先读回，再对同一完整RESULT做有界持久核对；
不会第二次notify。操作结束仍未证实时，保留POSTING／UNKNOWN事实，不开启后继发布尝试。

### 保留、退休、容量与恢复

当前token的槽始终保留，包含已发布／受阻／未知终态。用户撤回、完成或换计划使旧槽退休：
保存原event／claim／result事实，加退役原因，不让旧DELIVER继续存在；不能把旧未知结果伪造成已取消或已送达。
owner退休时，未认领DELIVER按OWNER_RETIRED封闭；已认领但结果不明按OUTCOME_UNCONFIRMED封闭。
若退休写结果未知，后继只作认证状态核对并保守封闭；绝不重建内存资格或补发。

只有同时满足以下条件，才允许在单独的门内清理事务回收旧槽：旧token已被用户后继决定退休，
其生命周期已结束且没有活跃核对引用，没有该token的SCHEDULE／DELIVER／CANCEL未决命令，
也没有原活跃操作尚可提交的结果。当前槽和仍需CANCEL／结果核对的槽不得驱逐。
普通ACK不顺便回收槽；尤其CANCEL ACK成功／返回未知时，event和claim仍可认证读取。
回收后旧token请求返回RETIRED／STALE，不声明“从未发生”；只读核对不能变成执行入口。
不承诺永久保存全部平台审计历史；ID自绑定仍保证改字段重用同ID冲突。

拟定固定上限：每任务最多4槽，每槽编码最多3072字节，ledger总编码最多16384字节。
EVENT阶段最多占1536字节，另预留1536字节供claim／result／退役，不可先产生DELIVER／POSTING再发现不能记结果。
确认新代次前须保证一个槽可用；无合规可回收槽则事务不提交新确认，保留用户输入并报状态待核对，
不能挤掉未核对历史来假装已安排。撤回／完成只封闭既有槽、不分配新槽，仍可执行。
32条命令的原限额不变；最大合法ID／标题／时区、32命令与4槽组合必须仍能撤回且不突破65536字节密文上限。
具体编码必须以最大边界构造验证预算；若不成立，不可扩大密文上限或静默减断言，须先调整设计复审。

v6在既有字段后编码UserReceipt presence／原字段、ledger count和每个有界长度槽；严格计数／长度／枚举／EOF。
v1–v5原解码／AAD不变；旧v5 UserReceipt不能被系统槽替代。新增运行时槽只在v6编码。
完整备份可保全认证数据，但恢复后清空全部RuntimeLedger、DELIVER及执行回执，提醒REVOKED；
原平台身份不转成新设备执行资格。开放选定包仍不携执行状态。

### v6确定的字段顺序

沿用原密文framing／nonce／tag和原v4公共明文字段顺序。其后为u8 UserReceipt presence，
存在时按原v5回执字段顺序编码；再为i32 ledger count(0..4)，各项为i32长度和最多3072字节槽体。
文本均为i32 UTF-8字节长及原字节，数字为大端i64，枚举为严格白名单文本，所有presence是u8的0或1。
槽体按下列顺序，不能通过遗漏字段的默认值解码：

| 顺序 | 槽体字段及上限 |
| --- | --- |
| 1 | u8 slotCodec=1；token(task ID文本≤512字节、reminder ID恰64 ASCII、scheduleVersion i64)；按既有非空schedule编码；mode文本≤16字节 |
| 2 | EVENT：event ID恰100 ASCII；expectedTaskVersion、observedAtMillis各i64；能力快照；接受决定文本≤32字节 |
| 3 | u8 claim presence；存在时：claim ID恰100 ASCII、eventHash恰64 ASCII、expectedTaskVersion与claimAtMillis各i64、能力快照、GENERIC_V1规格文本≤16、决定文本≤32 |
| 4 | u8 result presence；存在时：result ID恰100 ASCII、eventHash与claimHash各恰64 ASCII、expectedTaskVersion与observedAtMillis各i64、结果文本≤32、原因文本≤64；u8能力快照presence，仅NO_ATTEMPT_CAPABILITY_LOST为1并跟随快照 |
| 5 | u8 lifecycleClosed；为1时跟closedAtMillis i64及首次封闭原因文本≤64；u8 tokenRetired；为1时跟retiredAtMillis i64及首次退役原因文本≤64；严格EOF |

能力快照固定为checkedAtMillis i64、platformConnected u8、notifications文本≤16、exactAlarms文本≤16、channelEnabled u8。
各ID的nonce与本请求摘要从严格ID结构提取，不另写第二个可矛盾副本；共同token／计划由槽体首部重建摘要输入。
claim的event ID取同槽原event ID，result的event／claim ID同理；保存的关联摘要必须匹配重算值。
slotCodec=1固定摘要命名空间、操作、策略版本与字段顺序，未来改变规则必须另起codec，不悄改既有digest。
物理操作仍有8字节上限：新增RM_EVENT、RM_CLAIM、RM_POST、RM_END、RM_GC分别用于事件、认领、
结果、生命周期封闭和独立清理；旧RM_ACK／RM_FIRE继续解码。所有系统操作仍须保留R1回执与其他运行时槽。
槽体3072字节上限包括其所有文本长度前缀与presence；ledger 16384字节上限另包括count和各槽长度前缀。
4个最大3072字节槽体含外层前缀为12308字节，不误记为12288字节。按当前固定字段上限静态计算，
公共字段加UserReceipt至多28674字节，ledger预算16384及密文framing29共给出45087字节保守上界。
单槽的字段上界为1841字节，其中EVENT前缀980、其余可选回执／退休861，落在1536+1536预留内。
这是设计字段预算，不是v6实际writer／reader已验证；实施仍须执行最大边界构造回归。
[静态字段预算收据](evidence/d07-r2a-p2-field-budget-20261020.json)记录逐项求值及一次人工汇总校正，
不将该算术检查计作生产测试。

## P2-2：受理后、认领前能力丢失的原子终态

原EVENT可已记录ACCEPTED+DELIVER。协调器仍在原活跃执行门／lease内，重新观察能力并固定CLAIM请求；
SQL事务自身不调用平台。在事务内重新认证event、当前token／计划、OPEN和原DELIVER，再据该次能力决定：

| 能力决定 | 同一事务的持久变化 | 发布资格 |
| --- | --- | --- |
| 可用 | 保存GRANTED ClaimReceipt，消费DELIVER，写POSTING | 仅原活跃LiveClaimAttempt可继续一次 |
| 不可用 | 保存BLOCKED_CAPABILITY ClaimReceipt及认证的原观测快照，消费DELIVER，写DELIVERY_BLOCKED终态；封闭该次交付 | 没有LivePostPermit，notify为0 |

DELIVERY_BLOCKED属于**这次token的交付终态**。原event的“真实触发已观察”事实不删除；
它与“入口受理时已受阻”的TRIGGER_BLOCKED由ClaimReceipt明确区分。
保留R1 UserReceipt、旧CANCEL及原模式，既不降级模式，也不重加SCHEDULE或DELIVER。
事务回滚时保持原状态，但未确证GRANTED之前无论如何不能notify；COMMIT未知按原claim ID／摘要读回。
负面能力观察同时先封住原内存LiveClaimAttempt，不能在受阻写回滚后重新观察到可用，就换一个claim ID继续发布。
可有界重试原封不动的BLOCKED请求来确证退役；持续存储失败则结束该活跃资格，遗留DELIVER只能保守核对／清理，
不能被新会话认领。未持久确证受阻原因时只显示未核实，不把内存中的负面观察冒充已认证历史。

历史原因采用认证的通用码CAPABILITY_UNAVAILABLE_BEFORE_POST，UI显示“发布前能力不可用，未发布”。
存下的四字段是当时观测，不推论具体的用户拒绝动作；当前能力面板另行读取。
能力后来恢复、普通刷新、event精确重放、不同新claim ID或旧WorkItem均不能重开这次终态；
同claim ID改能力快照也冲突。只有用户另外明确确认新的未来计划／代次才有新的安排资格。

若GRANTED之后、真正平台调用前再次观察到能力丢失，消费原内存一次标志而不调用notify，
按同claim记录NO_ATTEMPT_CAPABILITY_LOST结果及该观测，亦进入DELIVERY_BLOCKED终态，不补发。
OS资格可能在检查后再变化，不能由执行门冻结；实际调用失败／结果未知仍按既定失败／未知合同记录，
不假称能力观测提供了送达保证。

## 新增必须回归的交错（方案，尚未执行）

| 编号 | 实际SQL／JCA与受控协调器的因果安排 | 必须断言 |
| --- | --- | --- |
| R01 | EVENT COMMIT未知→旧CANCEL ACK→冷读／原活跃对象读回 | event请求／决定仍可认证；只按原完整ID重放，不以物理lastMutationId猜结果 |
| R02 | 存储层CLAIM COMMIT未知→其他系统ACK→认证claim核对；协调器另测ACK等待执行门 | 原claim关联完整保留；读回不创建发布对象，只有原活跃且未消费对象可继续，notify至多1 |
| R03 | 同event／claim／result ID分别改token、观测时间、expectedVersion、计划、能力或结果 | 全部冲突、密文不变；跨任务及旧槽合规回收后仍拒绝同ID改字段 |
| R04 | RESULT COMMIT成功丢返回→CANCEL ACK→精确结果读回 | 原event／claim／result均保留，结果不被改写，notify不增加 |
| R05 | claim已持久，操作结束／owner退休／重启后再读相同证据 | 只返回状态，不能重建LivePostPermit；未提交成功结果不凭空补写，notify不增加 |
| R06 | 4槽仍有待核对身份，第5次确认；最大字节预算；撤回 | 不驱逐活跃／未核对槽，不提交新确认；已有任务可撤回，32命令／4槽组合不越界 |
| R07 | v5原请求／摘要冻结字节→v6系统推进；坏引用／重复槽／错误枚举／EOF；备份恢复 | R1摘要原字节与重放合同不变，非法ledger拒绝，恢复无运行时槽和执行资格 |
| B01 | 受理时可用→claim前不可用→恢复；重开、event重放、新claim再试 | notify始终0，DELIVERY_BLOCKED及原历史原因可认证，DELIVER永不复活，R1回执保留 |
| B02 | 上述受阻claim事务回滚／COMMIT未知，随后能力恢复；持续写失败后冷开清理 | notify0；原内存尝试已封口，只核原完整BLOCKED请求；未核实残留DELIVER不转成新认领，清理后无复活 |
| B03 | GRANTED后、调用前能力丢失→恢复 | NO_ATTEMPT_CAPABILITY_LOST认证保留，notify0，无第二个内存发布资格 |

这些是待实施断言，不是已通过测试。已有两项PENDING基线探针与R1完整CI均保留原证据，未重计。
存储层的回执保留用依次结束的真实事务测试，不在SQL回调内调用另一个系统写。
协调器层另外断言执行门不允许ACK插入仍活跃的外部调用；两层证据不能混作可重入调用的许可。
本轮没有新CI或APK／设备／权限／真实提醒执行。复审通过后仅实施R2a模型／领域／真实SQL／JCA／
受控协调器；R2b Android入口、权限、通知与冷owner接线继续单独独审。
