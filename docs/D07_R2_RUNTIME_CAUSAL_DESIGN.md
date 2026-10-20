# D07 R2：触发事实、锁态边界与共同执行门（待独审）

代码基线：`26d50166436a71240265b14d9716a153e6447504`。
父级已对R1实施作限定ACK，无确定P1/P2，精确提交的CI39380840683完整success。
本轮仅设计与隔离基线探针；以下拟议类型／状态／组件尚未实现，也未获实施ACK。
本文件细化并覆盖旧运行时设计中关于触发恢复、DELIVER自动重放和owner排空的笼统描述。
原两份方案§13.1–13.3、§14继续约束：原生Kotlin／Compose、LOCAL_ONLY、无自建后台，
单次提醒需明确确认；保存、安排、通知发布与用户看到分别记载。

## 需要独审的具体取舍

1. 到点真实回调可以在同一事务重新认证**当前PENDING或SCHEDULED**计划并持久受理；
   PENDING必须同时有完全匹配的当前SCHEDULE命令。受理消费该SCHEDULE，不补调过去的alarm。
2. **不新增应用侧持久opaque触发队列，不承诺锁态触发可恢复。**
   锁定无会话、进程死亡丢WorkItem、重启未入库的触发均可能丢失；解锁后只标“未核实”，不补发。
   已入库的触发事实可恢复展示，但失去原活跃执行资格后不会自动发布过去的通知。
3. 执行门覆盖当前计划核对→离开SQL→平台调用→结果核对，也覆盖撤回／完成和owner交接／排空。
   notify前持久认领一次调用；结果未知不自动再次notify。因此可能漏报，不能声称恰好一次或百分百到达。

这是保守的首版可靠性取舍，明确舍弃“崩溃后自动补通知”，避免把不可证实的外部结果当成成功。
若独审要求锁态可靠补交付，须另审持久最小调度账本及其隐私／过期策略，不能在实施中暗加。

## 当前代码与真实基线

`TaskReminders.canAccept`只认SCHEDULED；`TaskReminderDispatcher`对系统结果未知保留命令。
现有`DrainingRecordsSqlDatabase`只计SQL事务，不覆盖两个事务之间的外部调用；
`LocalRecordsViewModel`的串行命令队列只覆盖当前前台owner，不协调冷后台owner。
`AppAccessRetirementBarrier`等待旧owner关闭；不能据此推断冷owner或外部Binder调用已经排空。

在未改动R1跟踪树的精确基线上，用生产SQL／JCA与真实SQLite做两个隔离探针：

| 对照 | 控制驱动与数据库事实 | 冷重开后唯一到点触发 | 实际结果 |
| --- | --- | --- | --- |
| ACK入库之前丢失 | schedule返回成功1次；ACK端口在进库前抛错；DB仍PENDING | 只调用acceptReminderTrigger，不重新drain | 预期失败，当前代码返回false |
| ACK已COMMIT但返回未知 | schedule返回成功1次；真实ACK事务成功后端口抛错；DB为SCHEDULED | 同上，重复回调另断言不再受理 | 对照通过 |

共2项方法、1项预期失败、1项正对照通过，错误／跳过0；不计为R2功能通过。
失败精确落在`ReminderPendingTriggerDesignProbe.kt:46`的受理断言，非环境或编译失败。
外部init script只加入ignored探针，不改生产／正常测试源／CI方法清单。
[原始日志、XML、探针与基线收据](evidence/d07-r2-pending-trigger-baseline-20261020.zip)。
R1的1267项主机结果和完整CI仍以其原有不可变证据为准；本次探针不冒充重跑整套。

## 一、PENDING唯一触发的认证与原子受理

拟新增内部`ReminderTriggerRequest`：完整token、此次实际入口nonce及完整请求摘要绑定的event ID、观测时间，
以及仅在内存有效的入口／owner执行资格。它是系统观察，不是另一份用户决定。
生产入口只接受本应用显式、不可变PendingIntent指向的非导出alarm receiver；
UI、普通恢复扫描、备份和任意旧WorkItem不能自行构造“已触发”。

在共同执行门内，先拿到有效owner和实际平台能力，再执行一个短SQL事务：

1. 必须有现存任务行，认证密文、task ID、reminder ID、当前scheduleVersion；任务为OPEN，
   任务日期与提醒日期／时刻／IANA时区／偏移完全一致，回调不早于明确到点时刻。
2. SCHEDULED可受理。PENDING另要求其当前SCHEDULE命令的token、完整时间及模式逐字匹配；
   缺命令、撤回、旧代次、终态、已触发／发布、缺行或认证失败均拒绝，不UPSERT。
3. 通知与渠道可用且原精确模式资格满足时，原子写TRIGGER_ACCEPTED，移除当前SCHEDULE，
   增加同一token的DELIVER；保留旧代次CANCEL和R1独立用户回执，任务行版本加1，提醒代次不加。
4. 若真实回调时缺通知／渠道／精确资格，仍记录此真实触发已观察，但写TRIGGER_BLOCKED，
   消费当前SCHEDULE且不建DELIVER。历史只记“触发受阻、未发布”；当前通知／渠道／精确资格分别显示，
   不从当前能力反推当时的拒绝原因。恢复权限不会自动发布这个过去的提醒。
5. 同一event的COMMIT结果未知时，在门内用独立运行时槽中的原event ID及完整请求摘要读回认证结果；确证后才继续。
   不同event对同一token只得到ALREADY_OBSERVED；不得再造DELIVER或发布资格。

区分返回结果：ACCEPTED、EXACT_EVENT_REPLAY、ALREADY_OBSERVED、STALE／MISSING、
TOO_EARLY、BLOCKED、NO_ACCESS、UNKNOWN；不能用一个false同时表示“已消费”和“尚未入库”。
原event的读回重放只在原活跃入口、同owner租约且执行门仍持有时允许继续交付。

交错：schedule在门内成功→ACK写入失败→释放门→唯一回调入门→认证PENDING并原子受理。
回调在schedule调用中到达时，只排队，不能同步重入SQL／门；它等该调用和ACK处理结束后受理。
随后迟到的schedule ACK发现对应SCHEDULE已消费，只读返回过时，不回滚TRIGGER_ACCEPTED，
不复活、重加命令、增加提醒代次或重写用户决定。已COMMIT的ACK返回未知走同一受理路径。

任何SCHEDULE执行前重新检查due>now；due<=now不能借“重试”再次调用AlarmManager。
普通前台观察到已过时刻，不能证明旧alarm丢失：保留可能的真实大约迟到回调资格，UI显示未核实。
已知调度丢失事件（如重启）才可将过去且无触发事实的计划记TIME_PASSED，退役SCHEDULE，
不自动notify。真实当前大约迟到回调与“扫描发现已过时刻”始终是两种输入。

## 二、锁态、WorkItem、死亡与重启

### 持久事实的唯一来源

可执行的确认、触发事实、DELIVER及发布认领／结果只存在现有加密任务行。
不加明文／device-protected任务账本、触发token文件、新Keystore alias、长期租约或凭据。
不将WorkItem队列、NotificationManager活动通知列表或“时间已到”当成数据库触发证明。

| 边界 | 允许的持久变化与恢复行为 |
| --- | --- |
| 设备首次解锁前 | 不取DB key、不打开DB、不接受触发、不发通知；不承诺保存此回调 |
| 应用锁开启、无准入会话／设置读取未知 | 不开冷owner、不解密任务；完成／丢弃当前短期WorkItem，不持久接受触发 |
| 已准入会话在世 | 只借用该owner的队列；按第一节认证受理，不能并开完整记录owner |
| 应用锁明确关闭、设备已解锁、没有前台owner | 可经broker取得最小冷owner，处理此次真实入口；不能创建新库／新key |
| WorkItem丢失或进程在触发COMMIT前死亡 | DB没有触发事实；后续启动扫描显示时间已过／结果未核实，不自动补发 |
| 触发已COMMIT但交付认领前死亡／锁态改变 | DB事实仍可认证展示；遗留DELIVER退役为TRIGGER_UNDELIVERED，不自动notify |
| 发布已认领后死亡或返回未知 | POSTING读为发布结果未核实；不再次notify，不伪造POSTED |
| POSTED已认证持久化 | 只表示平台发布调用已返回并记录；不表示通知可见、用户看见或只看见一次 |

不用额外的持久“需核对”bit来冒充每条触发事实。每次正常准入均从加密状态重算：
未有触发／发布证明且已过时刻的计划显示“时间已过，通知结果未核实；未补发”。
纯前台核对是显示状态，不改变可能仍有效的大约迟到触发资格；已知丢失事件才退休旧计划。
锁态发生过的单个回调如果未入库，解锁后不能知道它具体发生过，文案不得说“待交付已恢复”。

### 短期入口容量、去重与清理

JobScheduler／JobWorkItem只作为同进程IO交接，统一用非persisted job；API26可用不等于跨重启持久。
每个WorkItem有单独有界token封装、event ID、接收进程随机nonce；默认同一进程，不配置多进程。
进程内最多64个未完成唯一event，每批最多16；同token的同次在途回调合并，多个token不共用可覆盖extras。
超容量／enqueue失败／JobService停止时，不宣称保存成功或自动重投过期alarm；后续准入仍显示未核实。
每项payload上限2048字节，禁止正文、标题、联系人、原文、key及日志中的原始payload。
设备绑定的最小token封装须在准入后才能解开并核对数据库，不能把调用者自报task ID当授权。
确切Android封装实现仍属R2b平台独审，不能为了查找身份在锁态扫描／解密全库。

在途集合只存内存：提交／拒绝／锁态／停止均清理，进程退出自然清空；不迁入SavedState或文件。
旧进程nonce的系统重投WorkItem只退役并触发后续核对，不自动notify；同进程交付次数最多2次，
第二次只作认证读回／清理，不能产生第二个发布认领。入口nonce不算用户认证，不能绕过会话。
新的真实AlarmReceiver在新进程收到当前合法大约迟到回调，可取得**新的**入口资格再认证受理；
这区别于重放旧WorkItem或从DB中过期日期伪造回调。接收时无访问资格的入口立即退休，
不可排到解锁以后再自动执行；资格检查／退休与owner准入更替必须在broker中原子决定。

入口同步登记当时的owner lease／admission epoch，不能在Job稍后执行时改绑到新解锁会话。
新进程无owner时，登记有界cold-candidate并保留其准入顺序，随后在IO经执行门→偏好操作门核对锁设置；
该核对与后来的解锁／禁用锁／前台owner建立串行，后来的授权不回溯授权这个旧入口。
偏好未能确证为关闭则退休；明确关闭才可继续最小冷owner。此reservation不能在主线程阻塞，
也不持登记guard等待IO；前台OPENING在必要交接完成前不能发布canReadRecords。
R2b须实测“LOCKED收回调→等待→用户解锁→Job才执行”仍零发布，而非只测试静态LOCKED分支。

## 三、执行门、撤回与owner排空

### 门的范围及锁顺序

拟新增进程级broker／执行门，持有owner lease与有界入口状态，不持有Activity、明文或SavedState。
执行门跨越整个逻辑操作：**认证当前行的SQL事务结束 → 外部调用 → 独立结果事务**。
不得只把一次SELECT放进门，之后放开再schedule／notify；平台调用绝不在SQL事务回调内。

前台任务变更及后台工作必须走同门：提醒确认／撤回、完成／取消、SET／POSTPONE／CLEAR、
相关重命名、正式任务导入／恢复、冷owner交接和关闭。`Command.ManualTasks`覆盖来源／提案及手工路径；
`Command.RecordPackage`内的完整备份恢复／任务导入也须在最外层取得门，不能在createWithin等SQL内部取门。
提醒控制器原有直接dispatcher也须改经同一协调器，不能遗留第二条不受门约束的生产调用路径。

固定顺序：执行门 → AppAccessPreferenceOperations（仅需读写启动保护时）→ 短SQL事务。
短owner-admission guard只做登记／封口，不在guard内await、开库、调用平台或等待VM队列。
broker有前台port时：先取得port快照，释放登记guard，入VM队列；命令实际执行时取得执行门并复核lease。
不能先持门再等待队列里的工作取得同一个门。冷owner不与已存在或正在建立的前台owner并存。

### 外部调用与关闭

每个外部操作持有计入owner lifetime的完整租约；仅SQL用户计数不足以排空两段SQL之间的Binder调用。
显式retire仍保留原“忙碌／未确认编辑不准退休”的规则。强制onCleared先封住新入队／新租约，
随后异步等待已经准入的操作、结果核对与清理完成，再seal SQL、awaitIdle、关DB、抹key，最后ACK关闭。
已有外部调用可能在封口请求后才返回；关闭／锁定完成不能早于它的真实结束。未知关闭保持sealed，
后继owner等待，不能忽略失败或直接再开库。锁定请求／按钮点击本身不等于关闭已完成。

平台端口为同步IO合同，不启动脱离owner的线程；协程取消／超时不证明Binder调用未执行。
取消发生时，已进入操作继续持门并在NonCancellable的IO收尾，库和key保持存活至收尾确认。
若调用一直未返回，只能显示关闭待核对；不能假称取消成功、提前释放门或销毁key后任其回写。
平台回调只排队，不同步进入当前SQL或递归取门。

### notify前的一次调用认领

为明确“结果未知”而非猜测恰好一次，拟增加DELIVER命令及系统阶段：
TRIGGER_BLOCKED、DELIVERY_BLOCKED、POSTING、NOTIFICATION_POSTED、POST_FAILED、POST_UNKNOWN、TRIGGER_UNDELIVERED、TIME_PASSED。
既有TRIGGER_ACCEPTED作为持久触发事实。DELIVER与已消费的同token SCHEDULE不同时存在；
仍为每token最多一条当前命令，旧CANCEL保留，并为当前代次撤回保留容量。
现有32条上限不放宽：PENDING的当前SCHEDULE和TRIGGER_ACCEPTED的当前DELIVER均可在撤回时
原位转为该token的CANCEL；没有可转换当前命令的阶段仍必须预留一个槽。更新revoke／cancellations
须同时处理DELIVER，不能把它原样带进REVOKED，或靠降低上限掩盖满槽撤回失败。

当前活跃入口在同一门和owner租约内继续：

1. 重新认证当前token、OPEN、原模式和能力；可用则SQL保存独立ClaimReceipt并把TRIGGER_ACCEPTED+DELIVER变为POSTING，消费DELIVER。
   不可用则同事务保存BLOCKED_CAPABILITY claim、消费DELIVER并进入DELIVERY_BLOCKED终态，恢复不再发布。
   认领COMMIT未知按原claim ID／完整摘要核对，不依赖lastMutationId，未确证GRANTED不调用notify。
2. 仅当前入口的既存、尚未消费的LiveClaimAttempt可以取得一次不可序列化的LivePostPermit。它绑定owner lease／进程／event／token／claim，
   不能由仅持有一个DB token、冷扫描或ALREADY_OBSERVED构造；离开当前操作／资格退休即销毁。
3. 关闭SQL事务后仍持门，调用一次通用通知发布。tag/id固定于reminder ID+代次，only-alert-once仅作系统提示配置。
4. 返回成功才在独立事务记录NOTIFICATION_POSTED；明确失败写POST_FAILED；未知写POST_UNKNOWN或保留POSTING，
   均不自动第二次notify。ACK未知时先按claim/token认证读回，不把重新发布当成读回。

持久阶段事实保留，LivePostPermit不持久。冷恢复不能因为看见POSTING或DELIVER就取得它。
该策略可能在“认领COMMIT后、真正notify前”崩溃时漏报；UI如实显示未核实，用户可明确设置新的未来时间。
稳定通知身份不证明用户只看到一次，也不能消除OS排队、用户手动清除或厂商行为的不确定性。

### 与撤回／完成的顺序

撤回／完成赢得门：先提交新用户决定，退休旧代次并写CANCEL，再在门内撤销旧alarm和旧通知。
其后旧trigger／schedule ACK／post ACK只可拒绝，不复活、不UPSERT。取消平台失败保留精确旧CANCEL，
UI区分“本机已撤回”与“系统撤销待核对”，不能假称全部清除。
发布赢得门：完整认领、调用及结果处理先结束；随后撤回提交并撤销这个精确旧通知／alarm。
用户在撤回持久成功之前可能已经见过通知，不能承诺“从没出现过”。禁止cancelAll影响别的token。
Binder返回也不能证明OS呈现／撤销时序，保证仅限应用自己的调用和持久状态顺序。

## 密文／兼容约束与分步实施范围

R2a拟采用有界v6密文：沿用原manual_tasks表、已有purpose key与AES-GCM，AAD仍绑定格式、id、行版本及物理mutation。
v1–v5解码／AAD与R1规范化用户请求摘要保持原字节，读取不重写；新增状态／DELIVER只由v6写入。
v6在既有提醒／命令字段后显式编码用户回执是否存在及原回执字段，再编码有界RuntimeLedger；没有可证旧用户回执时不伪造。
本阶段不增加TaskReminderSnapshot运行时字段；新的阶段／命令仍由原完整REMIND请求摘要绑定，
因此R1旧请求重放不因重新定义摘要而失效。扩展系统操作白名单及严格阶段／命令不变量，保留EOF／大小校验。
系统写保留R1用户回执；恢复清除用户执行回执、DELIVER和发布资格，提醒REVOKED，无旧通知自动发布。
新格式是拟议设计，具体编码仍须独审；不承诺旧二进制读v6。

父级已ACK R2a方向，另要求先补两项P2持久合同再复审实施。
独立event／claim／结果认证槽、精确字段绑定／保留退休规则，以及能力失效原子终态详见
[R2a持久回执补充](D07_R2A_PERSISTENT_RECEIPTS_ADDENDUM.md)，其具体约束优先于本文件概述。
新增DELIVERY_BLOCKED终态和运行时槽不更改R1请求摘要字节，不允许由持久claim重建发布资格。

独审后先实施R2a：纯模型／领域、实际SQL持久合同、平台端口协调器与受控驱动的三组因果回归。
这是运行时闭环的核心前置，不宣称系统已经接线。其后R2b再独审Android端口、receiver／JobService、
最小冷owner及前台会话接线，和重启／升级／时区／权限重建的稳定作业epoch去重。
R2b不得把此处的基线探针或R2a通过当成重建已完成；完整D07仍需这些集成与设备验收。
新权限声明、最小token封装及cold open的具体实现均须跟随R2b实际代码复核；本设计不授权真实系统grant或提醒运行。

## 必须实现的因果回归方案

所有主机时间、生命周期、权限与平台端口均受控；SQLite使用现有真实连接及生产SQL／JCA。
以latch／屏障安排交错，检查实际调用序列、事务边界、密文字节、行／提醒版本，不以sleep猜时序。

| 编号 | 故障／交错 | 必须断言 |
| --- | --- | --- |
| P01 | schedule成功→ACK进库前失败→冷开→唯一当前回调 | PENDING一次受理，SCHEDULE消费、DELIVER持久，schedule调用仍为1；不安排过去alarm |
| P02 | ACK回滚／COMMIT后丢返回；重复event／不同event | 分别认证受理或精确读回；只一个触发事实／DELIVER，R1回执不变 |
| P03 | 接受触发事务回滚／COMMIT未知→冷开 | 回滚保留原命令；未知可认证展示事实，旧入口不获自动发布资格 |
| P04 | 早到、旧代次、不同ID／时间／模式、撤回／终态／缺行／坏密文 | 不写入、不UPSERT、不调用平台；当前大约真实迟到可受理 |
| P05 | due<=now后前台drain／重建；无真实回调 | schedule与notify均0；普通扫描只未核实，已知丢失才TIME_PASSED |
| P06 | 回调时缺通知／渠道／精确权限；后来恢复 | TRIGGER_BLOCKED持久、没有DELIVER；模式不降级，恢复不自动补通知 |
| L01 | 首次设备解锁前／应用锁无会话／读取失败；旧入口排队后才解锁或禁用锁 | key load、DB open、触发写、notify均0；旧入口不改绑新会话，文案不称可恢复交付 |
| L02 | 锁态收到后失WorkItem／进程死亡／重启 | 冷开无伪造触发证明；过去计划只未核实／TIME_PASSED，无补alarm／notify |
| L03 | TRIGGER_ACCEPTED或POSTING时死亡／锁定，随后解锁 | 事实可读、遗留交付不自动执行；POSTING不猜POSTED，notify不增加 |
| L04 | 64个入口／第65个、重复、多token、enqueue失败、Job重投 | 有界、不覆盖另一token；清理可证，旧进程／旧资格不发通知 |
| G01 | 在“当前检查后、schedule调用前”暂停，提交撤回 | 两者只能按同门先后；撤回若先提交则旧schedule调用0，反序则精确撤销 |
| G02 | notify认领前／调用中／结果后并发完成／取消 | 完成先赢则notify0；发布先赢则随后撤销该token，迟到ACK不复活 |
| G03 | claim回滚／COMMIT未知、notify异常／成功后ACK丢失 | 未确证claim不notify；已认领最多一次调用；未知不自动重发，精确读回不写新用户决定 |
| G04 | 外部调用中onCleared／冷转前台／锁设置更改 | 无新租约，外部调用未结束前不关DB／抹key／ACK关闭，新owner不能重叠打开 |
| G05 | 驱动回调排队；驱动探测SQL事务深度 | 外部调用深度0，不SQL重入，不持门等同门VM队列；真实操作可完成、无死锁 |
| C01 | v1–v5冻结字节／旧R1请求→系统v6写；篡改AAD／EOF／过大；32槽含DELIVER时撤回 | 读兼容不重写、旧请求仍精确重放，非法输入拒绝；满槽仍可撤回，完整备份恢复不可执行 |

R2b另需实际Android桥Robolectric检查PendingIntent身份／不可变性、非导出receiver、JobService绑定权限、
真实能力分支和Compose确认／撤回状态；原生APK／instrumentation编译、设备执行分别报告。
目前这些全部NOT RUN；现阶段只有上述2项基线探针实际执行，不能将表格当测试通过清单。

## 官方平台依据与边界

已核对官方资料：WorkItem在API26提供；API34之前不支持持久化，且Intent不能作为持久WorkItem数据。
本设计统一用非persisted短交接，不借API34能力给minSdk26编造持久性。
[JobWorkItem](https://developer.android.com/reference/android/app/job/JobWorkItem)、
[JobScheduler.enqueue](https://developer.android.com/reference/android/app/job/JobScheduler#enqueue(android.app.job.JobInfo,%20android.app.job.JobWorkItem))。

不可变PendingIntent限制接收方修改，创建方仍可更新extras；故身份不能只靠extras或短requestCode散列。
[PendingIntent](https://developer.android.com/reference/android/app/PendingIntent)。
官方提醒文档要求精确资格检查，并区分安排、系统限制及重建；本设计的“不重投过去alarm”是额外产品约束，
不是平台替应用提供的恰好一次保证。[AlarmManager指南](https://developer.android.com/develop/background-work/services/alarms)。

本轮没有产品／manifest／依赖变更，没有申请或接受系统权限，没有安装SDK或接受新协议；
没有真实闹钟、通知、模型调用、网络后台或设备验收。下一步是此具体设计的父级独审。
