# D07 R2b 最小原生接线设计（待父级独审，未实施）

生产基线 `4d28c6f32ee9822a865be1ab2e9d552292576e8a`；[R2a封存收据](D07_R2A_CLOSURE.md)记载限定ACK及精确CI成功。
本文件仅设计，不启用receiver／JobService／权限／通知，不改生产源码、依赖、manifest或现有数据库。
父级设计独审通过后再实施；实际设备权限请求、闹钟及通知试运行仍未授权。
完整遵循两份原方案§13／§14，保留[R2触发与生命周期合同](D07_R2_RUNTIME_CAUSAL_DESIGN.md)、
[独立回执合同](D07_R2A_PERSISTENT_RECEIPTS_ADDENDUM.md)及[两项drain修正](D07_R2A_DRAIN_REVIEW_FIX.md)。
只接一次性、明确确认的本地提醒；LOCAL_ONLY、无自建后台、无真实模型调用、不增加费用／凭据。

## 1. 当前事实和待审取舍

- 实际minSdk26／target及compileSdk37，已有SQLCipher4.19.1。没有AlarmManager或通知适配、receiver、JobService及提醒权限声明。
- `TaskReminderController.reconcile`仍直接构造旧`TaskReminderDispatcher`，默认断开驱动；不能只换driver就称接线完成。
- `LocalRecordsViewModel`的ManualTasks与RecordPackage命令是可挂起lambda；R2a执行门却是线程绑定ReentrantLock。
  不能把门直接套在可跨线程挂起的lambda外，也不能持门等待同一VM队列再取门。
- `AppAccessViewModel.session()`目前在OPEN发布后才懒建完整记录VM；现有beforeOpen只等待旧VM关闭。
  R2b须把owner准备／登记纳入OPENING，不能用“UI已OPEN”冒充最小冷owner已排空。
- `EncryptedEntryRepository.open`可创建完整库、附件／编辑存储及密钥，不能直接用于冷路径。
- 拟增加一个加密库内的单行运行时元数据表（Room v15），只存重建进度和排序游标，不存触发／发布证明。
  这是本设计新增待审范围；不修改已封存v14导出、v6任务格式、R1摘要helper或32命令／4槽合同。
  新表避免在库外另建任务／触发token文件，也避免冷owner交接／停止后永久重试失败前缀。

## 2. Android组件和权限边界

| 组件 | 职责与限制 |
| --- | --- |
| `AndroidReminderDriver` | 同步AlarmManager／NotificationManager端口；平台调用都在SQL外、当前执行门与owner租约内 |
| `AlarmReminderReceiver` | 非导出、显式组件、非directBootAware；只登记真实alarm入口，不开库／取key／发通知 |
| `ReminderJobService` | 同进程、exported=true但受BIND_JOB_SERVICE保护、非directBootAware；固定非persisted JobInfo，无网络／充电约束、前台服务或手动wake lock |
| `ReminderRebuildReceiver` | 非导出，处理BOOT_COMPLETED、MY_PACKAGE_REPLACED、TIME_SET、TIMEZONE_CHANGED及精确权限授予广播；只交接重建提示，不伪造alarm入口 |
| `ProcessReminderBroker` | applicationContext级唯一实例；准入序号、owner、执行worker、64个短期票据及Job交接；不持Activity或SavedState |
| Activity权限／点击适配 | 仅当前UI明确按钮发起系统流程；通知点击直接到MainActivity并经过现有AppAccess，不用receiver／service跳板 |

实施拟仅声明POST_NOTIFICATIONS、SCHEDULE_EXACT_ALARM、RECEIVE_BOOT_COMPLETED；BIND_JOB_SERVICE是service的调用保护，不是uses-permission。
继续移除INTERNET，禁止USE_EXACT_ALARM、ACCESS_NETWORK_STATE、全屏通知、后台启动豁免或新SDK／协议接受。
不订阅LOCKED_BOOT_COMPLETED，不建device-protected资料账本，不配置第二进程。
BOOT／时间变化的manifest接收资格以[官方广播例外](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)为依据；
升级只监听本包MY_PACKAGE_REPLACED，不能把一般PACKAGE_REPLACED当成无限制全局监听。

## 3. 同一个执行门与可挂起前台命令

保留R2a门的非重入和真实调用排空性质，生产统一经一个进程级专用IO worker执行；不在主线程等锁。
现有VM仍串行处理UI命令，ManualTasks、RecordPackage和系统提醒命令把已选中的完整操作交给该worker并等待结果。
worker上对可挂起动作采用明确的阻塞桥接：门由同一worker线程取得、持有和释放，runBlocking自己的事件循环承接动作的恢复；
动作可以等待其他IO，但不能把持锁代码转给另一个线程来解锁。运行时协调器的同步handle／drain作为独立顶层门操作执行。
桥接不是“用超时取消Binder”：已准入操作在NonCancellable收尾范围内完成，不能因上游scope取消就提前ACK关闭。

必须同时改掉旧controller在提交lambda内部直接drain的路径：用户动作只提交／认证用户决定并刷新，平台排空是之后的独立命令。
初始化／备份恢复回调只排队核对，不在当前门里再调用coordinator。禁止动作enqueue同worker／VM工作后同步await它；
新增worker线程／递归断言及屏障回归防止自等。不得用runBlocking阻塞Activity主线程、登记guard或SQL事务。
桥接向挂起动作附加只读操作上下文标记，子IO继承；若其回调尝试同步重入生产运行时API，立即拒绝或改排队。
不能只靠线程局部depth发现跨线程递归，也不能让子IO等同一worker后再让worker等子IO。

门覆盖确认／撤回、完成／取消、日期／标题改变、提案／来源转正式任务及完整恢复的逻辑边界；
纯文件准备可在操作前做，但提交／恢复动作本身不能在门外改变任务状态。平台仍在短SQL结束后调用。
固定顺序为：非阻塞登记 → 释放登记guard → VM入队（若有前台port）→ worker执行门 → 偏好操作门（如需要）→ 短SQL。
不在登记guard内await，不持门等待VM队列，不在SQL回调内取执行门。

R2a执行门拟补“无owner时的准入操作”：仍持同一把锁，确认空闲，检查偏好／打开必要存储，成功才同锁发布owner。
失败只清理本次资源；若关闭未确认，保留sealed占位，不能继续开替代owner。受控测试的直接openOwner不变，生产只经broker。
拟补入站工作的退休请求与平台调用开始的有序竞争，不能仅靠JobService取消CoroutineScope推断平台未调用。
这些是内存／生命周期接线，不增加可序列化发布许可，不改变event／claim／result的持久语义。
Job工作退休与post开始在同一活跃尝试的原子状态转换上排序：退休先赢则不再开始平台调用；
调用开始先赢则允许其真实返回及原结果收尾，退休请求不能提前结束该操作租约或抹key。
onStop请求本身不立刻使已开始调用的ResultAuthority失效；有效期仍在原操作finally结束，不跨入下一操作。

## 4. 准入、锁态和冷前台交接

| 登记时状态 | 处理 |
| --- | --- |
| UserManager未确认首次解锁 | 立即退休，不读偏好／key／DB，不保留到解锁后执行 |
| broker为LOCKED／ACCESS_UNKNOWN／OPENING／RETIRING | alarm票据立即退休；没有新owner资格，不排到后来的解锁会话 |
| 已准入且就绪的前台owner | 捕获该lease与admission epoch，经其VM队列处理；执行时复核同一lease，不另开冷库 |
| 没有前台或待建前台owner | 同步登记有界cold-candidate和序号，在worker的空owner准入操作中核对偏好；此登记不算授权 |
| 偏好确认为关闭且设备已首次解锁 | 只为这次登记打开最小现存存储并建立冷owner |
| 偏好开启／损坏／读取未知 | 不取DB key、不解密、不发布；旧票据不能因稍后禁用锁或解锁而改绑 |

进程初始的IDLE_NO_OWNER只表示没有会话，允许有序cold-candidate检查；它不同于已经读取失败的ACCESS_UNKNOWN。
ACCESS_UNKNOWN必须经新的正常偏好核对恢复，不能让同一旧票据自己重试到授权状态。
首次解锁用[UserManager.isUserUnlocked](https://developer.android.com/reference/android/os/UserManager#isUserUnlocked())，
不把其含义混同于后来每次屏幕上锁。首次解锁后的普通设备锁屏可以显示通用通知；应用锁仍遵循现有startup／manual会话语义，
本片不擅自新增onStop自动锁定。启用应用锁但仍有有效OPEN会话时只借该会话；会话丢失后不冷开。

偏好使用现有FileAppAccessPreference的严格读取；已定义的完整缺文件状态是v0=false，读取失败或残缺文件不能降级为false。
cold-candidate与后来的偏好读写、认证完成／前台准备都在broker序号与同worker边界内仲裁：
先前LOCKED入口不能被后来的false写入或新OPEN会话回溯授权。不能先读一次false、释放准入边界，再无保护地开库。

前台准备流程改为：进入OPENING并登记reservation → 不持worker等待旧appAccessClosure／cold操作真正结束 →
worker准入并准备唯一store与lease → 把已拥有的store注入记录VM（禁止其再次open）→ 完成初始化和broker登记 → 才发布canReadRecords。
OPENING期间的alarm不保留为新会话工作。初始化失败／认证generation过期的已准备owner必须关闭，不交给替代会话。

显式锁定保留已有编辑／保存忙碌拒绝退休规则；系统工作计入该owner的忙碌与关闭追踪。
强制onCleared先封口票据和新任务，等待当前worker动作真实结束；再join AI／连接等旧生命周期使用者，
最后在worker经门关闭存储、awaitClosed及抹key／派生codec，之后才完成appAccessClosure。
不能让关闭任务在worker内等待一个还需worker完成的VM finally；旧屏障等待在外层协程完成。

## 5. 最小现存库cold owner

不构造LocalRecordsViewModel或完整EncryptedEntryRepository。新data工厂只暴露runtime repo、重建meta、封装codec和可等待关闭句柄：

1. 准入后查现存records.db及密文证据；必须是应用目录下现存普通文件，拒绝异常路径／symlink／仅残留sidecar。
2. 复用现有DATABASE purpose和alias；强制load(databaseExists=true)，不生成key、alias、目录或新库；缺wrapped key／失效即失败。
3. 以已有SQLCipher4.19.1的OPEN_READWRITE／WAL、**不含CREATE_IF_NECESSARY**的openDatabase打开，显式使用不删除文件的错误处理器。
   不能调用openOrCreateDatabase，也不能让默认corruption恢复删库。所用API与SupportSQLiteDatabase实现见
   [固定版本官方源码](https://raw.githubusercontent.com/sqlcipher/sqlcipher-android/v4.19.1/sqlcipher/src/main/java/net/zetetic/database/sqlcipher/SQLiteDatabase.java)。
4. 认证、核对当前schema版本、Room identity及生成定义／必要guard；cold不迁移、不补装guard、不走破坏性恢复。
   v14或未知版本只提示需要正常前台打开升级；首次R2b升级由前台14→15迁移，cold只接受已验证v15。
5. 抽出既有SupportSQLiteDatabase绑定／事务适配供Room和直接连接共用，加DrainingRecordsSqlDatabase追踪；
   只实例化SqlManualTaskRepository、meta仓库与purpose隔离codec。不读取原文、附件、编辑草稿、索引、模型配置或BYOK。
6. 同一broker绝不并开前台／冷连接。操作后保守核对遗留运行时事实，再await实际使用者结束、关连接、抹派生key与raw key。

存储错误只向UI／日志给脱敏状态，不打印路径、token、payload、原始平台异常或密钥。
schema／key不可用时可能漏报；不能通过完整库工厂兜底、接受协议或重建空库来假装成功。

## 6. 设备绑定的最小PendingIntent身份和封装

从现有DB key用标准HMAC-SHA256及固定不同purpose派生封装key和identity key；仅owner内存持有，不新增Keystore alias／凭据文件。
封装采用AES-GCM、随机12字节nonce、严格版本／长度／UTF-8／EOF，明文只有完整TaskReminderToken，禁止标题／原文／联系人。
alarm和click使用不同AAD用途；AAD还绑定applicationId、codec版本和稳定identity，解码后重算identity。
这是封装完整性和设备绑定，不是用户认证；打开后仍核对当前owner、DB行、OPEN、当前代次和R2a受理条件。

identity=HMAC(独立purpose,规范化完整token)的64字符十六进制文本；URI分别为
`aiyou-reminder://alarm/v1/<identity>`与`aiyou-reminder://click/v1/<identity>`，显式固定组件和action。
加密封装在受限ByteArray extra；整个入站负载上限2048字节，不携任意Parcelable／Serializable或URI授权。
规范化上限须执行真实JCA最大边界构造；超限／错用途／错设备／损坏一律拒绝，不能截断或宽松解码。

PendingIntent.getBroadcast创建使用IMMUTABLE|UPDATE_CURRENT，固定requestCode=0，以不同URI隔离完整token；
不依靠32位hash或extras判定身份。重新seal的nonce不会改变PI身份。
CANCEL用同URI、组件、action、IMMUTABLE|NO_CREATE查找，取消精确alarm／PI及该token通知tag，禁止cancelAll。
[PendingIntent官方契约](https://developer.android.com/reference/android/app/PendingIntent)说明immutable限制发送者填充，创建者仍可更新；
因此不可把immutable或creator包名当成用户认证证明。

## 7. AlarmManager、能力和实际发布

EXACT用RTC_WAKEUP及setExactAndAllowWhileIdle；APPROXIMATE仅在用户明确选定并单独确认后用setAndAllowWhileIdle。
这两个模式都不承诺Doze／OEM下可见或准时。API31+调用canScheduleExactAlarms，API26–30按平台规则处理，无listener绕权路径。
特殊权限不可用时保留精确选择，显示当前未获授权；不把“返回设置页”当授权，也不自动降为APPROXIMATE。
[官方精确闹钟说明](https://developer.android.com/develop/background-work/services/alarms)规定检查特殊权限，撤销会取消未来精确alarm；
[Android14默认拒绝说明](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)支持新安装不预设可用的选择。

先在已准入门内确保通用渠道存在，不覆盖已有用户渠道选择；能力观测包括全局通知开关、API33+POST_NOTIFICATIONS、
渠道importance及支持版本的group阻断、精确资格和平台端口可用性。
仅从当前false不能得知“从未请求／拒绝／后来关闭”；生产文案使用“当前不可用／尚未获得”，不伪造历史拒绝原因。
权限请求只在当前前台lease、路由／editor／request generation仍有效且用户点击时发起；
通知结果和精确设置返回都重新观察能力，过期结果只丢弃，不写任务、不发通知、不自动再次弹系统页面。
[通知权限官方说明](https://developer.android.com/develop/ui/compose/notifications/notification-permission)支持API33+单独处理。

协调器在能力查询后重查due；adapter在权限复核、封装和PI准备等可能阻塞的准备结束后，也在真正set*前重查due>now。
跨期则抛出／返回不执行结果，使原SCHEDULE保留，不伪造ACK；不取消可能仍合法迟到的既有PI。
最终比较不能保证物理时钟随后绝不前进，保证仅限应用调用前的受控观测与顺序。
schedule／cancel平台结果未知保留原命令，未来同token重试只覆盖同一PI；不重投过去alarm。

通知固定通用标题“爱忆有一条提醒”和通用打开提示，现有自有小图标，默认私密可见性／通用public版本；
不读取task.title，不带完成／延期等无需重新核对的写操作，不用全屏、气泡或额外角色素材。
notify(tag=identity,id=0)、onlyAlertOnce和稳定身份仅辅助系统呈现，不能证明恰好一次。
post之前仍按R2a持久GRANTED及原活跃尝试资格；API返回才记POST_RETURNED；异常／取消／死亡均可能未知，不重新notify。
CANCEL仅当alarm及通知两个精确撤销调用均返回才ACK；其中一项未知也保留命令，不能宣称系统已全部清空。

## 8. receiver → JobService，不建立持久触发队列

receiver同步完成尺寸／类型检查、接收时刻及broker lease／epoch登记，立即返回；不在主线程解码或扫描库。
goAsync只用于短交接，应用交接预算2秒，finish置于finally；失败退休票据，不宣称已持久保存。
预算用elapsedRealtime，不用可跳变wall clock；worker晚到或enqueue Binder晚返回也须核对票据仍有效。
超时不证明enqueue没有执行；晚来的WorkItem因票据已退休而零库读／零发布，不能借重试另造资格。
[官方广播生命周期](https://developer.android.com/develop/background-work/background-tasks/broadcasts)说明异步交接仍须快速结束，不能把随意后台线程当存活保证。

每个JobWorkItem只带processNonce与随机ticket，不带task ID、key或明文；封装留在至多64个唯一identity的内存reservation中。
ticket不是R2a event ID：实际event摘要依赖门内认证行版本及能力，只有进入原操作时才生成。
同identity在途合并，一份reservation只enqueue一次；第二次交付最多被动核对，已退休／缺失ticket直接完成，不重建owner资格。
新进程真正收到新的alarm回调可以建立新的cold-candidate；旧进程WorkItem重投永远不能如此。

固定一个JOB_ID、同一个JobInfo，非persisted、零override deadline作短交接提示且无网络约束；不为每票据改JobInfo extras。
deadline不等于即时运行或精确提醒保证，JobScheduler不承担触发时刻调度职责。
进程首次安排新票据前在worker上清理该JOB_ID的旧进程工作，再enqueue新ticket；此清理同进程只执行一次，
与新enqueue串行，不能在每次Service重建时cancel当前队列。旧JobService先启动则只完成无匹配ticket的旧项。
首次清理须实际返回后才发布queue-ready；交接超时不提前发布ready，不能让尚未返回的cancel随后删掉新工作。
[JobScheduler.enqueue契约](https://developer.android.com/reference/android/app/job/JobScheduler#enqueue(android.app.job.JobInfo,%20android.app.job.JobWorkItem))
说明同ID旧work可保留、JobInfo改变会影响执行；本片主动不使用持久work来恢复触发。

onStartJob只发起IO；每turn最多dequeue16项。alarm每项最多一次post；若处理重建页，整turn最多一个16行页，
并从同一剩余平台预算分配drain额度，不能把16项乘16页放大成256次调用。
有余项再异步续一个有界turn，让前台／关闭／其他入口有机会入队；不持门无限while扫描。
onStopJob立即封口关联reservation并返回false，不要求系统重投；已经进入平台调用的操作继续持owner租约直到真实返回及收尾。
stop先发生则不再对该params调用completeWork／jobFinished；两种完成调用与stop按同一params generation去重。
若状态检查后平台已停止而抛出完成异常，只退休本地ticket，不据此重新提交或再次发布。
这一OS停止回调不等于应用owner关闭完成；系统仍可能杀进程，之后只有未知事实，不自动重发。
正常冷owner可以跨同一有限job的多个turn使用，队列清空／停止／前台交接时关闭，不每16项强制改绑旧票据。
前台交接若使剩余旧lease退休，剩余alarm票据丢弃而不迁入新owner；只有数据库中的未来命令／重建进度可以以后续正常准入续核。
[JobService契约](https://developer.android.com/reference/android/app/job/JobService)是这组生命周期选择的依据。

## 9. 稳定重建epoch和有限扫描

拟Room14→15只新增`reminder_runtime_meta(slot=1,version,ciphertext)`，不改旧表／guard／v1–v14导出字节。
payload v1以独立DB-purpose AEAD保护，AAD绑定slot／version，最多4096字节，严格optional／计数／EOF。
包含：固定策略版本、不可变重建请求ID／完整摘要、原因bitset、可选BOOT_COUNT、当前zone、app versionCode、
能力值摘要及计划扫描PENDING／DONE、可选最后已扫描task ID；另有独立drain轮次的PENDING／DONE、dirty合并标记、
起始／下一cursor和本轮task ID上界。cursor只编码task ID、可选行内command代次和行已耗尽标志，不伪造一个执行token。
各文本沿已有领域上限；真正writer／reader须以所有cursor最大Unicode输入组合验证4096字节预算，不以静态估算代替测试。
这里只有一次重建进度及排序hint，不存在触发、claim、post事实或可恢复执行许可。
稳定fingerprint只使用boot／版本／zone及能力值，不含checkedAt、wall clock、process随机值或页面generation；
请求本身另保存其不可变观测／nonce以核对未知提交，不能让每次读取时间不同都触发新epoch。
请求ID固定为`b1:<nonce32hex>:<digest64hex>`共100 ASCII，摘要按独立namespace、codec／policy版本、nonce、
expectedMetaVersion及所有持久请求输入顺序规范化，字段沿R2a的大端／长度前缀／严格UTF-8规则；与event／claim／result用途隔离。
当前同ID完整请求可在meta进度版本推进后只读重放；不同旧请求先验expectedMetaVersion不命中则STALE，不覆盖新epoch。
同ID修改任何字段会先因重算摘要失败而冲突，不依赖永久保存历史ID。mutable cursor／drain进度不进入该不可变请求摘要。

系统receiver把原因提示交给broker；在准入后才读写meta。相同观察fingerprint／同请求精确重投复用epoch，
未知COMMIT按meta内请求ID／摘要读回，不只看物理lastMutationId；同ID改字段冲突，旧epoch请求不能重新覆盖新epoch。
显式TIME_SET等同值事件在进程内合并为一个新请求；新的实际事件使新epoch从头检查，不每次旋转／页面初始化重建。
原因集合只在请求固定前合并；固定后的请求字段不能为合并提示就地改写，新原因需另建请求或忽略确实无需处理的重复提示。
新进程复用未完成epoch；已完成的相同boot／version／zone／能力观察不重复启动扫描。
这一去重只限制“把既有SCHEDULED重新安排”的计划扫描，不屏蔽用户刚提交的SCHEDULE／CANCEL。
每次活跃用户提交、实际新入口或正常新owner准入可请求独立drain轮次；进行中只合并dirty标志，结束后有新工作才续一轮。
task行命令本身仍是持久outbox：提交后进程在通知job前死亡，下一次正常准入重新核对；不承诺无新准入也能立即修复这个空窗。
[BOOT_COUNT](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT)只作可选稳定观测；读失败不能伪造0或宣称发生过重启。
只有boot观测可确认相同时才用完成fingerprint抑制启动重建。boot未知时，新的实际BOOT提示强制一轮未来扫描；
正常新进程准入也可保守扫描未来计划一次，但不得把它标成“已经确认重启”。同进程旋转／重复提示合并，不反复启动。
旧ticket引起的JobService唤起不是新的正常准入，不能借这个降级规则绕过其零冷读要求。

每turn按task ID keyset最多取16行，不先解密全库；逐行认证当前OPEN、明确时间／模式及当前token。
未来且无当前触发／claim事实的PENDING、SCHEDULED或明确能力／平台受阻阶段，可在短事务确保同token当前SCHEDULE／PENDING，
用新增系统操作RM_REBLD，保留R1 UserReceipt和全部RuntimeLedger；不调用用户changeReminder、不增加提醒代次。
当前RuntimeLedger／TRIGGER_ACCEPTED／POSTING／任何已观察终态、REVOKED／完成／取消／仅日期一律不因此安排。
事务后平台仍经协调器重新核对；未知结果保留命令，不把page完成当成系统安排成功。
R2a现有drain会取全命令快照，不能直接用它假装后台只扫描16行。
生产拟新增明确的命令分页端口：按task ID和首行内的command代次keyset，最多读取／认证16行、返回16个可执行命令，
每行最多32命令的既有上限不变。单行未排完则保留该行的代次cursor；过期／DELIVER行可推进扫描cursor而不花平台额度。
协调器新增受门约束的page入口，仍逐项核对当前token／能力／due，不能把分页结果当执行授权；生产后台不再调用全快照入口。
每次唤起只完成一轮有限扫描，页间释放操作并续turn；所有项都失败也不得内部无限循环。待核命令保留供后续事件／用户核对。
drain轮次记录起始cursor及一次索引读取取得的task ID上界，最多绕回起点一次；新插入的工作在dirty触发的后续轮次处理。
SQL分页排序统一用id COLLATE BINARY及行内数值代次，不把Java UTF-16比较器当成SQLite UTF-8/BINARY顺序；
astral与BMP ID跨页顺序必须回归，不能因游标比较不一致而漏行或反复扫描。

page cursor与drain cursor在meta中按该轮进度推进／认证读回；任务失败不挡住后面的任务，失败任务仍在原行保留待核状态。
冷owner交接只迁移已认证的排序hint，不迁移票据、lease、LiveClaimAttempt或发布资格。
游标COMMIT未知可能使一个未来token再次核对／覆盖同PI，允许这种至少一次安排尝试；不承诺重建恰好一次，通知仍遵循R2a至多一次活跃发布尝试。
DONE仅表示已完成一轮有限扫描，未决命令和各任务观察仍是事实来源；失败数不隐藏，后续实际事件或用户重读可再核对。

| 原因 | 行为与禁止推断 |
| --- | --- |
| 进程死亡／旋转／普通返回应用 | alarm不因本进程退出就被假定丢失；只续未完成epoch、排未决未来命令、保守封闭失去活跃资格的DELIVER／POSTING，不补通知 |
| BOOT／已确认boot计数变化 | 检查未来计划并幂等重建；过去计划不安排、不发布 |
| MY_PACKAGE_REPLACED | 未来计划核对；cold若需schema迁移则等前台，不在后台改库；不能据此推断每条旧alarm已丢失 |
| TIME_SET／TIMEZONE_CHANGED | 使用每条已保存的date／time／zone／offset，不擅自换成新设备时区；只处理当前仍未来的计划 |
| 精确／通知权限、渠道变化 | 当前能力单独观测，未来原精确选择仍需精确资格；不可用不自动选大约，不恢复过去发布 |

实际TIME_SET到达时，broker同步推进进程内调度epoch并退休尚未开始平台调用的旧alarm reservation，
避免“旧wall clock到点已收回调→排队→时钟拨回→旧工作迟到执行”被重新解释为当前触发。
这只封口旧内存工作，不改用户计划、不批量TIME_PASSED；新收到的实际回调仍按接收时刻及当前DB代次检查，早到拒绝。
已经开始的平台调用按既有租约收尾，不能因随后时钟变化假称从未执行。该调度epoch不同于AppAccess授权epoch，不授予任何新访问资格。

本片不把“扫描时已经过去”自动写成TIME_PASSED：一个粗BOOT epoch不能证明该条当前代次在失调度事件前就存在，
期间也可能有新确认／重建后迟到的合法alarm。没有逐token丢失证明时只显示未核实，保留真实迟到回调资格。
R2a的markKnownLostPast仍保留给有足够目标证明的调用，本片不借它批量清理队列。
未来SCHEDULED被重新核对也不表示用户已看见通知；UI分别显示持久观察、当前能力及时间已过的未核实状态。

完整备份不导出meta，恢复后清空meta与内存hint、保留原有REVOKED／无命令／无RuntimeLedger规则；
必须更新完整包的schema inventory与恢复测试，不把meta加入用户原文／任务事实。旧包读取保持兼容，不迁移原设备执行身份。

## 10. 通知点击和当前授权

直接getActivity到现有singleTask MainActivity，用不同click用途的封装及URI；onCreate／onNewIntent只捕获受限导航请求，
不能转成alarm ticket、调用handle、完成任务或写发布回执。[官方通知导航](https://developer.android.com/develop/ui/views/notifications/navigation)支持直接Activity路径。

LOCKED时只显示现有应用锁页面。点击请求可作为当前一次显式导航意图等待正常认证，但绝不赋予后台发布资格；
这与LOCKED时已退休的alarm／Job入口严格分开。取消认证、新点击覆盖、owner退休时丢弃旧导航请求。
不写SavedState／文件；接收后清除可重放的Activity extras，进程死亡可丢失导航，不伪称已恢复。

只在当前OPEN且就绪的同一session／route generation下，经门解封并重新读取当前任务。
必须有当前OPEN任务及相同token，撤回／完成／旧代次／缺行／恢复后的旧封装只给通用“提醒已变化”信息。
读取后、发布导航前还要复核会话与版本，防止期间锁定或替代owner。只导航到正式待办，不绕到原文／联系人。
当前任务可以仍是POST_UNKNOWN；用户点击不是补写POST_RETURNED的证据，不把导航当成送达确认。

## 11. 实施顺序及必须新增的因果回归

设计ACK后顺序实施：先broker／同门与现存存储factory；再v15 meta／封装／受控adapter；再receiver／JobService／前台权限与导航接线。
每步保持可独审、真实SQL／JCA断言；所有Android端口在主机用受控clock／shadow，不运行真实系统grant或通知。
以下是准入用例组，不是已通过的测试计数：

| 组 | 必须验证的因果边界 |
| --- | --- |
| A01 | LOCKED收到alarm→Job延迟→用户解锁／禁用锁→旧ticket仍零keyload、零DB、零notify |
| A02 | 初次解锁false／未知、偏好损坏／读取失败、缺现存库／key／schema14／错误schema：不创建／重置／删除原文件 |
| A03 | cold准入与前台OPENING竞争、关闭失败／迟到factory返回：最多一个owner，OPEN不能早于真实交接 |
| A04 | 主动锁定忙碌拒绝、强制清理、平台调用屏障、key wipe／appAccessClosure顺序；线程迁移和enqueue自等拒绝 |
| A05 | 所有ManualTasks／提案／来源／RecordPackage恢复经门，旧dispatcher无生产调用残留；平台调用SQL深度0 |
| E01 | 实际封装最大合法token、坏长度／EOF／UTF-8／AAD／跨key／alarm-click交叉：拒绝且不UPSERT |
| E02 | 同token随机重seal仍同PI；两个token即便32位hash碰撞也不同；取消旧token不影响新token／别的任务 |
| P01 | API26／31／33／37能力边界，渠道及group阻断；外部返回不等于授权；旧UI回调不改新session |
| P02 | driver准备／canScheduleExactAlarms阻塞跨due后，set*仍0、ACK0、旧密文及合法迟到入口保留 |
| P03 | 精确被拒／撤销不调大约；用户明确改大约产生新的合法用户决定；无自动重弹权限 |
| P04 | 通用通知不得包含合成敏感title／原文，通知PI直接Activity；notify返回／抛错／取消／未知不二次发布 |
| P05 | alarm取消成功但通知取消抛错等两个方向，CANCEL仍保留；只精确token撤销，不cancelAll |
| J01 | 真正receiver→JobWorkItem→service受控链：64容量、同identity合并、16总预算、不把重建页乘成256调用 |
| J02 | fresh process旧ticket、第二次交付、onStop前后及callback重入：无冷读／无新发布资格、完成ACK去重 |
| J03 | process首次清旧JOB_ID与新enqueue屏障，不删当前工作、不触及其他作业 |
| R01 | v14→15真实迁移及旧schema导出不变；meta坏认证／同ID改字段／COMMIT未知及cursor推进读回 |
| R02 | 超过16失败／过期／DELIVER前缀、同一行32命令及Unicode跨页，跨冷owner重建仍能推进未来schedule与CANCEL，hint不授予旧入口资格 |
| R03 | 重复BOOT／旋转／权限提示不产生无限epoch；中途死亡继续有限页；失败行不挡后续行 |
| R04 | 先未来重建后扫描跨期／期间新确认，不能粗暴TIME_PASSED；旧回调排队时拨回时钟不再发布；时间／时区变化不改保存的计划语义 |
| R05 | 全备份跨key恢复排除meta、清执行状态；原文／提案／正式任务及原R1回执合同不退化 |
| N01 | LOCKED点击→取消／当前认证／新点击／旋转／进程死亡／撤回完成竞争：只在当前授权下读当前正式待办，零隐式写入 |

## 12. 交付边界

这份文件和R2a元数据封存是本轮交付；所有R2b用例组、schema15、组件及权限声明均尚未实现／执行。
实施需继续全量主机XML、原生AAR／APK／lint／Compose及Preview的精确SHA CI核验，正常开发分支推送，不改main或强推。
本地仍无Android SDK／设备，不安装或接受条款；没有设备不阻塞受控开发，但设备首解锁、权限、OEM、重启、
真实通知显示／点击／撤销、SQLCipher设备行为及性能必须标NOT RUN，不能由编译或Robolectric替代。
