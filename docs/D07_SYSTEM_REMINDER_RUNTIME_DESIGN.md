# D07 系统提醒：最小闭环设计，待父侧独审

基线：`681e07c240723b7d5b1a3f676f75599b60941a05`，CI39373171441完整success，
父侧已收口D05。本文只提出D07实现合同，不表示运行时已经接入或通过设备验收。
两份原方案保持原字节；本次逐项重读二者§13.1–13.3，并对照§8、§14和§15。

## 选择与依赖

选择原D07的一次性提醒：**正式待办明确时间 → 单独确认 → 本机持久保存 →
系统安排／缺权状态 → 当前代次触发 → 通用通知 → 撤销／完成后旧触发无效**。
不增加重复提醒、日历、自然语言解析、后台AI或其他产品需求。

已有手工正式待办、日期／时刻／IANA时区／DST偏移、加密计划和命令outbox。
手工闭环不依赖D03转写／OCR或D04真实模型；无需跳过这些未完成项，亦不声称其完成。
D00–D02现有构建与加密存储是本片代码前置；完整设备验收仍单列。

| 现有位置 | 已有能力 | 接线前必须补齐 |
| --- | --- | --- |
| `TaskReminders.kt`、`TaskReminderRepository.kt` | 独立确认、稳定ID／代次、SCHEDULE／CANCEL、旧代次拒绝 | 重建合同及持久通知交付命令 |
| `SqlManualTaskRepository.kt`、`ManualTaskCipher.kt` | v1–v4密文、行CAS、最近操作精确重放 | 用户回执不能被异步系统写覆盖 |
| `TaskReminderController.kt`、`TaskReminderDialog.kt` | 保存核对、明确撤销、状态说明 | 生产driver及真实能力状态；移除“固定未接入”文案 |
| `LocalRecordsViewModel.kt`、`AppAccessViewModel.kt` | 串行IO与应用锁排空 | 平台回调借用当前owner；冷启动owner与锁协同 |

## 必须先解决的持久回执依赖

当前`changeReminder`只在`lastMutationId`仍是用户请求且操作为REMIND时识别重放；
`acknowledgeReminder`／`acceptReminderTrigger`写RM_ACK／RM_FIRE，会覆盖该行最近操作。
单纯接入后台driver会产生下列交错：用户确认已COMMIT但UI未获ACK → 系统ACK已提交 →
冷重开 → 同一用户请求因行／操作已变化而被拒。标题、日期、完成／取消操作也需同样检查，
不能只保护提醒确认。现有“同actor、读回后才dispatch”避免了一部分交错，不能替代后台合同。

已在未改产品的681e07基线上做一次真实SQLite/JCA隔离复现：1项、1个预期失败，
`ManualTaskConflict`精确落在`SqlManualTaskRepository.kt:126`，即系统ACK和冷重开后的同请求重放。
不是编译／环境失败，也不计入功能通过数。外部init script只加入隔离探针，未写入正常测试源目录；
原始失败XML、日志、探针和列出的源码原件见
[设计前置失败证据](evidence/d07-runtime-design-baseline-red-20261020.zip)。

实施R1：新增有界v5密文，保留**最近一次用户请求的独立认证回执**：操作、mutation ID、
完整规范化请求摘要、旧版本及已提交的用户决定。系统状态变更保留该回执；
后续用户操作退休旧回执。同一请求重放返回经重新认证的当前快照，不重新确认、不回滚系统进度；
改字段、旧操作、另一任务和后续用户修改后的旧请求仍拒绝。普通CAS版本冲突仍有效。
CREATE／STATUS／日期／RENAME／REMIND及来源／提案创建的既有重放规则须逐项核对。

沿用原`manual_tasks`表、现有密钥用途与AEAD；不迁Room、不新建Keystore alias。
v1–v4严格解码与AAD保持；v5有固定大小／枚举／EOF校验。开放包仍排除设备执行状态；
完整备份恢复保持REVOKED、无系统命令，并清除可执行用户回执，不因恢复自动安排。

## 最小运行时与交付状态

1. 用户仍在既有确认页选择精确／大约，明确未来日期、时刻与时区；保存与系统安排分别反馈。
2. 本地用户事务和读回完成后，已有outbox驱动平台调用。未知结果保留同一命令；
   系统ACK只确认实际调用结果，不修改用户输入，不生成新待办。
3. Android driver使用显式、immutable PendingIntent；身份绑定`reminderId + scheduleVersion`，
   extras只含有界不透明身份，不含标题、原文、联系人或秘密。精确／大约保持用户选择。
4. Alarm receiver验证格式后交给JobService；逐个JobWorkItem保存触发token，
   不用单个可被新extras覆盖的job替代多个触发。工作只在IO执行，分批最多16项。
   同一WorkItem重投有上限；失败保留数据库命令，显示待核对，显式重试／后续恢复再处理。
5. 有效触发在事务内核对现存OPEN任务、当前计划／时区／代次／权限，
   原子写入TRIGGER_ACCEPTED及同token的DELIVER命令。缺失、完成、取消、撤回、早到或旧代次不写入。
6. DELIVER发布前再次认证当前授权与行状态；发布与确认之间保留可重放身份。
   同token使用稳定通知tag/id与only-alert-once；发布成功只写NOTIFICATION_POSTED，不能写“已送达”。
   未知Binder／COMMIT结果保留交付命令，重复不会新建任务／通知身份。
7. 取消、完成、日期变更在用户事务中退休旧token；CANCEL同时撤销该token的闹钟和通知。
   发布核对与撤销须有明确串行边界；不能在一次SELECT后任意延迟notify，也不能在回调内重入SQL。

系统通知默认只有“爱忆有一条提醒”，锁屏不显示待办正文；点击进入现有应用锁入口。
通知发布API返回不代表系统最终呈现或用户看到。设备／OEM限制不作百分之百保证。

## 权限、调度与后台边界

- 大约模式用AlarmManager的一次性非精确调用；精确模式只在实际资格检查通过后使用精确调用。
  不因缺精确权限偷偷改为大约。Android官方区分两者，并要求核对精确权限；
  关机后原闹钟被清除，必须重建。[官方闹钟合同](https://developer.android.com/develop/background-work/services/alarms)
- 新安装Android13+未获得通知授权时不发通知；分别检测应用通知与渠道状态。
  缺权／渠道关闭是可见状态，不伪造SCHEDULED，不从一次权限检查猜测用户曾经点过拒绝。
  [通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)、
  [渠道](https://developer.android.com/develop/ui/compose/notifications/channels)
- 拟声明POST_NOTIFICATIONS、SCHEDULE_EXACT_ALARM、RECEIVE_BOOT_COMPLETED及受
  BIND_JOB_SERVICE保护的本地JobService；不使用自动授予的USE_EXACT_ALARM，不增加INTERNET。
  这些是待审的代码声明；本任务不运行真实权限grant、自动点击系统授权或安装应用到用户设备。
  授权入口只能由用户主动动作触发；保存提醒不自动接受授权。
- 原方案的WorkManager用于一般后台整理／周期工作。本片只给一次性alarm receiver提供短IO交接，
  采用系统JobScheduler／JobService，复用现有minSdk26及SDK，不加SDK／模型／依赖／服务协议。
  JobWorkItem在API26可用；统一按非persisted WorkItem处理，不假称其在API26跨重启持久，
  跨重启事实来源仍是加密数据库及重建事件。
  [JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler)、
  [JobWorkItem](https://developer.android.com/reference/android/app/job/JobWorkItem)、
  [JobService](https://developer.android.com/reference/android/app/job/JobService)
- 不轮询、不常驻录音／前台服务、不申请电池白名单、不增加费用或外部凭据。

## 重启、权限变化与迟到

BOOT_COMPLETED、应用升级、改时／改时区、精确授权变化、应用前台恢复会发起有界核对。
未首次解锁不读取加密库。用户确认的未来计划可按原时刻／时区重建；同一持久重建作业ID精确重放
不能再次增加代次。需要新代次时，旧token的撤销与新命令同事务保存。
不得只读PENDING outbox：已SCHEDULED且命令已ACK的确认也需要重建；REVOKED、终态任务、
已触发／已发布及备份恢复的计划不得重新安排。权限恢复不擅自改变原精确／大约选择。

不能仅因“大约提醒”已过标称时刻就撤销仍有效的系统调度；当前代次的合法迟到触发仍可被接纳。
只有已知原调度丢失的重建事件，才把已过时刻且无触发回执的计划记为TIME_PASSED：
“时间已过，未重新安排；通知送达未核实”。不悄悄把过去时间交给系统立即补发，也不声称用户已收到。
普通前台核对无法证明是否送达时保持未核实状态。迟到旧代次始终拒绝；改时间仍需用户单独确认。

正式待办取消／完成撤销提醒；原文回收／永久删除沿用现有“独立正式待办保留”规则，
不擅自联删任务。若目标任务行不存在，触发与ACK只拒绝，不UPSERT或复活。

## 数据owner和应用锁

平台工作通过进程内broker优先借用已准入VM的串行IO owner；不能绕过未确认输入和排空边界。
冷启动只在设备首次解锁、应用锁明确关闭、现有密文库存在且无旧owner排空时读取提醒。
冷owner只打开已有数据库密钥和任务SQL端口，不初始化附件、草稿、备份或新密钥。
broker互斥协调UI准入与冷owner退出；扩展既有retirement barrier，先排空再抹密钥。
禁止在receiver另开一个完整记录／附件owner，也不创建空库来“恢复”缺失目标。

应用锁开启且无已验证会话时，后台不解密全库；提醒显示“需解锁后核对”，待准入会话恢复。
本片不承诺强隐私锁定状态下仍能后台读取任务；不会借通用通知文案绕过数据访问门禁。

## 实施顺序与验收

父侧设计ACK后，在同一D07分支实施R1（持久用户回执／交付／重建合同），再接R2（Android driver、
receiver／JobService、owner broker及确认UI）。R1是R2必需前置，不单独宣称用户提醒闭环完成。

必需的真实回归：丢用户ACK后系统ACK及冷重开；日期／标题／取消／完成同类交错；
精确命令重放与未知系统结果；多token同时到达不覆盖；新旧代次及重复触发；
确认→冷启动／BOOT重建→撤销；权限／渠道各自缺失；合法大约迟到与已知丢失的过时计划；
通知发布前撤销竞态；发布后ACK丢失；应用锁准入／排空；任务缺行和原文删除；
v1–v4字节兼容、v5认证／损坏／EOF、完整备份恢复仍不可执行。

主机SQLite/JCA及受控平台端口验证因果合同；Robolectric核真实Android桥和Compose确认交互。
完整本地相关测试通过后正常push一次，运行现有有界免费CI，不逐项猜错堆CI。
Android原生编译、Compose模拟和设备实测分别报告；真实系统授权、重启、锁屏、OEM、
通知到达及跨设备恢复仍须设备验收，不以缺设备停独立代码。

本设计尚无实现ACK；当前不修改生产代码／manifest／依赖，不触发真实系统权限。

## 父侧审查增补：R1获准实施，R2仍须补齐

父侧已ACK独立认证用户回执方向，限定先实现／独审R1；R2平台接线仍未获准实施。
R1规范化请求必须绑定task ID、操作、mutation ID、expectedVersion、请求时间及全部输入字段；
系统写不产生新用户决定，备份恢复清除可执行回执。新用户写将采用v5，旧v1–v4保持原解码／AAD，
旧版本无法重建的历史回执不能凭空恢复。本文前述“尚无实现ACK”指具体代码尚待独审，非方向未准入。

R2另有三个不得省略的前置交错，当前设计不能算收口：

1. **系统已schedule，RM_ACK未知／未入库，到点DB仍PENDING。** 当前canAccept仅认SCHEDULED，
   会拒绝唯一触发；“保留SCHEDULE命令”不足以解决。R2须在重新认证当前token后可靠保留触发事实，
   或按明确执行门受理；不得靠重投已过时刻的alarm补发。必须有真实SQLite与受控driver的因果回归。
2. **锁态没有DB触发回执，JobWorkItem丢失／进程死亡／重启。** 不持久化时，只能记为需解锁核对／
   未核实，不能宣称DELIVER可恢复。若另存不透明token，须先设计容量、去重、清理和解锁后的授权复核；
   不能绕锁、静默解密或补发过去提醒。API26的系统WorkItem不替代数据库持久合同。
3. **外部调用与撤回／排空之间的门。** 当前代次核对→schedule／notify→结果处理，与用户撤回／完成、
   owner排空共用明确执行门；外部调用不在SQL回调里重入。迟到ACK不复活、不UPSERT。
   稳定通知ID／only-alert-once不证明用户只看到一次，NOTIFICATION_POSTED更不等于送达。

R1不修改canAccept、系统driver、权限或通知发布。以上问题待R1独审后继续收口R2设计，再实施R2。
