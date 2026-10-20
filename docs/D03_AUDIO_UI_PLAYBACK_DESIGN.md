# D03-R1 原音文件 UI 与前台播放：待独审设计

基线 `34036d8a452a926b239dea48699c7cb8676061e6`，远端同 SHA，dirty0、ahead/behind0/0。
父侧已收口 ACK [CI39480585794](https://github.com/T1doo/AIyou/actions/runs/39480585794) 的实际成功；该结果不代表本设计已实现。
本轮只写设计，不改产品、Manifest、依赖、SDK、schema，不运行麦克风、选择器、音频设备、系统授权或模型，不重跑已过 CI。

## 1. 选择及与原方案的对应

原两份方案的 F01/D03、§5.1/5.4、§9.1、§12.2、§14.2、§16.3/17要求原始素材可靠保存、可找回、权限/能力降级及诚实反馈。
Word 原件 §12.2 与 Master 同样将独立原音存档和可纠正转写分开；120秒/9图为示例限额，不把它冒称为已验设备合同。
本片保留已审实现的60秒规范，不更改原文件。

优先选择 **用户选择短WAV → 加入加密草稿 → 点击试听/停止 → 保存记录 → 从准确原文附件播放**。
这让已实现存储成为真实应用交互。录音需要另行新增授权，OCR需要另审SDK/素材许可，D07剩余真实设备门不可借编译补齐；它们均不优于当前可完成的原音闭环。
从文件导入不是录音，不显示“正在录音”或“刚刚录下”。生产界面不提供合成录音按钮；测试/Preview注入的样本显式标“合成演示数据·非麦克风录音”。

本片包含：当前草稿唯一原音导入、试听、显式移除、保存；当前/历史准确原文附件中的只读播放/停止；前台生命周期及关闭门。
正式记录可含多条已认证原音（v9恢复可能如此），列表逐条显示，但同owner同时仅播放一段。正式版本不新增单附件删除/替换接口；使用既有记录删除/恢复流程。
不含麦克风、SpeechRecognizer、ASR/OCR、MP3/AAC转码、seek/暂停续播、后台/锁屏播放器、MediaSession/前台服务、播放通知或蓝牙设备选择。
`LOCAL_ONLY`/`AI_NONE`均可人工播放，不授予任何远端处理资格。读/播放不创建摘要、转写、事实或任务。

## 2. 已核源码与最小改动面

| 基线源码 | 实际情况与必要接线 |
| --- | --- |
| `app/.../records/MixedCaptureScreen.kt` | 只接文字/图片控件；接入原音卡、导入/播放/停止/移除和真实状态；共享20附件/64MiB配额，不把原音算图片。 |
| `MixedCaptureController.kt` / `ControlledCaptureAudio.kt` | 已有冻结排空、完整快照CAS、同intent重试和粘滞放弃。新增文件生产端复用同一Request/结算路径，不另写一套UNKNOWN/放弃状态机。 |
| `LocalAttachmentController.kt` / `LocalAttachmentScreen.kt` | 列表已有原音元数据；`verifyRows`核来源/重复ID，READY非图片只显示“此格式的查看功能尚未开放”。接原音行，图片仍只走IMAGE_TYPES。 |
| `OriginalReadingScreen.kt` | 空正文仍写“图片记录”；改为中性“附件记录·无文字正文”，不凭StoredEntry猜素材种类/声称有转写。 |
| `LocalRecordsViewModel.kt` / `MainActivity.kt` | 现有serial IO actor、onPause/onResume和存储退休；增加播放器唯一owner、前台epoch、停止/关闭ACK，禁止scope.cancel代替实际释放。 |
| `CaptureDraftGate.kt` / `EncryptedEntryRepository.kt` | 同owner现有草稿/恢复门可扩展独立PlaybackPin；不能用伪造CaptureDraft/音频intent冒充正式记录播放租约。 |
| `CaptureRepository.audio` / `AttachmentStore.list/read` | 已有确切草稿读与完整AEAD/hash/size认证；播放只使用这些能力，不开放加密路径/密钥给平台适配器。 |
| `checks/ai-protocol/remote-text`、`checks/record-queue` | 两个独立生产源同步清单均需同步新增的共享控制器；回归不能漏掉第二工程，不能改为NO-SOURCE或降门。 |

应用当前minSdk26、target/compile37；只使用已有平台及项目依赖。两份原方案的hash、生产依赖、Room v15和音频草稿3/选择包9/全备份9保持。

## 3. 文件导入与数据所有权

1. “导入原音文件”先检查owner/路由、无现存草稿原音及剩余配额；点击不替换正文/图片/范围。
   复用现有录入冻结顺序：关新草稿准入 → actor排空已接pendingEdits → 核UI文字和持久完整快照一致 → 保留最终base。
   只有actor确认后才发出一次picker claim；待选择期间继续冻结，不允许旧正文/附件/清空/恢复回调写入。
2. 使用`ACTION_OPEN_DOCUMENT`+`CATEGORY_OPENABLE`、单文件、`EXTRA_LOCAL_ONLY=true`，MIME提示WAV（含常见别名/octet-stream）。
   只处理用户选择的`content:` URI；不索要媒体库/全盘权限，不takePersistableUriPermission、不存URI/文件名到DB、日志、Bundle或SavedState。
   SAF是用户选单文件的访问路径，无需新增存储系统权限。[官方SAF说明](https://developer.android.com/training/data-storage/shared/documents-files)
   local-only extra是向provider请求本地可用内容，不能把第三方provider行为宣称成应用可证明的绝对离线保证。[Intent合同](https://developer.android.com/reference/android/content/Intent#EXTRA_LOCAL_ONLY)
3. Registry key沿用VM-owned UUID模式：配置变化保留当前活会话，新VM拒绝恢复旧key/旧result。
   result可早于onResume到达：仅在当前Request RAM中暂存URI，等同owner/同路由RESUMED后一次领取才打开。锁定/退休/取消则丢弃，不开流。
4. 原字节上限5292044，读取最多上限+1以识别超限；真实EOF后用`LocalPcmAudio.inspect`验证完整canonical RIFF及60秒/44100Hz/mono/PCM16。
   MIME、显示大小/时长不作证据；不截断、不补尾、不重编码，不支持额外chunk、多声道或其他格式时明确拒绝，原提供方文件不动。
   UI可在格式说明中展示这些必要选择限制，主卡只显示“WAV·最多60秒”。失败仍可继续文字/图片记录。
5. 有界字节仅留RAM，文件源真实close后才进入现有加密暂存与CAS；无明文temp/cache/MediaStore。原body/hash原样保存，不伪造拍摄/录音时间。
   加入草稿READY后显示“已加入本机草稿·原音未转写”；正式save提交且必要附件认证后才显示整条“已保存在本机”。
6. 文件生产端与合成PCM生产端分开，文件端直接交付已验证原WAV字节，不把用户文件装成“麦克风采样”。只共享准入/取消仲裁/资源收尾/结算。
   UI区分PICKING、读取检查、加密暂存、待核对、READY/FAILED；不沿用RECORDING文案。
7. 取消在最终化接纳前胜出则关闭实际输入后清零未提交波形；已有WRITING/未知结果只走同intent核对/重试或明确放弃。
   保留已审约束：活跃RAM的不完整WRITING仍可同intent重试；明确放弃后retry、reconcile、强制结算均继续放弃；不把已接受放弃反转为READY。
8. provider open/read可能迟到或不响应协程取消：单producer持有资源，到真实返回/close前保持忙/未知及owner关闭未确认，不开启第二来源，不伪造释放ACK。
   不在主线程/actor等待外部provider；actor只负责准入/绑定/最终CAS，独立IO生产者不等待actor才能释放自身流。
   打开资格与取消/退休在短monitor内排序，平台调用在monitor外；已准入调用可真实返回后立即关闭，旧result不得启动新打开。

进程死亡前尚在RAM的片段/URI不保证恢复。已完成加密发布的WRITING沿原recover认证成READY；冷恢复不完整文件仍标FAILED，正文图片保持。
显式更换原音先移除既有草稿原音并确认完成，本片不增加隐式replace/失败后偷换新intent。

## 4. 播放借读门、源校验与实际写入口

首片采用保守互斥：**播放期间，先停止再编辑/保存/修改附件/范围/删除恢复**，不把点击过的写操作排队到播放后自动执行。
原文阅读/复制与列表滚动仍可用；写冻结不等于隐藏已保存列表，`recentEntries`的只读可见性不得依赖写入门。

`CaptureDraftGate`增加独立不持久化`PlaybackPin`，与capture reservation互斥，scope为整个records owner；不共用草稿音频Reservation或修改持久格式。
必须区分 **UI_PREPARING** 和 **ACTIVE借读**：

- 播放接纳前拒绝尚有图片/原音picker、异步导入、待确认保存/清空/策略变更、编辑提交、恢复/删除未决或其UNKNOWN的owner；不能在这些producer迟到回调之前抢Pin。纯文字pendingEdits可排空，完整绑定前不输出PCM。
- UI点击播放在同一短准入边界阻止新的源写UI接收，并enqueue actor barrier。此前已接命令仍可完成，包括sync repository嵌套调用；此时不关实际数据mutation准入，避免旧prefix被syncMutation误拒。
- 该UI门覆盖此刻和legacy正文/保存/清空/恢复、原文编辑提交、图片/原音导入移除及其迟到回调、范围授权、回收站/永久删除、选择包/全备份恢复入口。先准入再改变UI/排actor；拒绝时不改UI、不记全局存储失败。已准入命令在自己的finally释放permit，不能只靠按钮disabled或仓库末端异常阻挡。
- barrier执行时先核请求仍归属当前owner/前台路由。只有既有draft及新增source两类`admitted/running`均0、无capture reservation、无destruction/恢复中租约，才能原子激活Pin并关闭实际源写准入。
  若有并发借用写者，不等未来actor命令、不轮询假排空：拒绝本次播放并还原UI，保留源，允许新点击。
- 激活后，所有源相关storage写入口均须走同一门，裸旧回调在碰文件/SQL前拒绝；不只依赖UI按钮、StateFlow观察或播放前的一次版本检查。
  Pin不持SQL/media锁，actor/file解密读取仍走现有计数借用能力。平台worker只持验证后的自有WAV和标识，不持repository/DB/key/URI/Activity。
- Pin解除仅在实际读取任务结束、native/focus/receiver资源释放已确认、缓冲清零后；未知时保持Pin和忙态，不复用旧Pin开第二段。
  退休后可结束自己的Pin但不能重新开放owner或使旧Mutation有效。

新增source写计数/同步借用只为PlaybackPin与退休提供保护，不改变既有capture reservation的cutoff语义：新的正式源writer不能简单套既有`syncMutation`，从而让capture冻结前已接的同步嵌套操作被新门误拒。
拟新增的source借用在同monitor下检查ACTIVE Pin/retired并计数；UI_PREPARING不拦已经接纳命令的实际借用，嵌套同步调用也在实际调用期独立计数，外层资格持续到最后一个内层返回。
既有draft写保留原Mutation上下文/cutoff机制，并在准入时加Pin判断；Pin激活同时检查两类计数。不得假定协程permit可由线程局部变量跨线程继承，也不在SQL事务/文件lease之后才首次借门。

实际source写入口覆盖（生产DI必须共享同一门，不能默认为各自newGate或以fake门证明生产已接线）：

| 源入口 | 处理 |
| --- | --- |
| SqlCaptureRepository全部写、legacy update/save/discard/restore | 沿已有门，加入PlaybackPin判断；原pendingEdits/cutoff/CAS行为不改。 |
| EncryptedEntryRepository.revise / reviseFromDraft | 在编辑文件lease及Room事务前取得同门写资格，不能只包最内层dao.advance。 |
| 对外AttachmentStore.save / recover | owner façade在ledger/文件前加门；list/read保留认证只读合同。内部capture暂存沿自己的reservation。 |
| SqlRecycleBinRepository.apply | 移入/恢复及generation变化的实际同步事务前加门。 |
| SqlPermanentErasure / full backup restore / record package及media restore | 沿现有外层mutation/destruction门，加入Pin互斥；不先清文件后才拒绝。 |
| SqlLocalProcessing.change；RemoteTextTransactions.updateAuthorization/tombstone及其调用者 | policy/current-source generation/visibility实际变更前同门；包括recordAuthorizations、selectedQuestion内嵌服务及RoomSourceBoundText路径，不以“只是授权”漏掉源generation变化。私有authorize是只读资格检查，queue/admit等仅工作状态变化不冒称源writer。 |

门实例需早于owner各capability构造，显式注入SqlCapture及上述嵌套服务；原文/来源触发器本身不作为app播放同步的替代。
单独写AI派生值、普通任务与不影响该源身份的元数据不冒充原音写；如实现审查发现其他源writer，必须加入同一表并证明先拒再写，不留下默认null生产旁路。

源读取标识：`owner generation / request id / route epoch / foreground epoch`，草稿再绑定完整CaptureDraft快照；
正式源绑定`entryId / 目标revision / 当前revision / visibility generation / attachment intent及state`，历史再绑定现有AttachmentHistoryOwner session/request。
现有人工LOCAL_ONLY/AI_NONE读权限保留，不借用远端send permit。
actor在Pin下分别验证可见原文/历史、完整descriptor、READY和完整AEAD/size/hash/WAV，并再次核相同来源/路由后交付；没有任何认证前PCM输出。
仅read(id)不是来源授权，不能凭已缓存的附件ID重放删除/恢复前或另一版本的数据。数据已变化/不支持/损坏则保留文件并停止，历史原文只读不修复其他版本。

## 5. 平台播放与生命周期

只使用现有Android平台AudioTrack，不加入Media3/codec依赖或新的SDK/权限。一次明确点击创建一请求，一owner仅一native执行worker；不自动开始/续播。

| 项目 | 决策 |
| --- | --- |
| 建立 | 真正前台RESUMED且路由仍拥有来源，获即时AudioFocus后才准入建track与play。minSdk26可用的AudioFocusRequest，拒绝延迟焦点；目标37遵循>=35的顶层应用限制，不新增前台服务。 |
| PCM | MODE_STREAM、mono/44100Hz/PCM16；从已认证WAV分块转ShortArray，使用WRITE_NON_BLOCKING；处理0/短写/负错误，不以已写入等于已听到。按播放头更新进度，尾帧实际消耗或明确停止后才收尾。 |
| 准入 | requestFocus/build/play各有同一请求的短monitor接纳标记；close胜出前不得调用。平台调用在monitor/DB锁外串行，之后胜出的停止等待在途调用真实返回，不声称可撤销已接纳的系统调用。 |
| 停止 | 即时撤销后续write/play准入，worker依次pause、flush、release，清理自身focus/动态noisy receiver；所有出口同一收尾，不只调用stop或scope.cancel。 |
| 前后台 | MainActivity.onPause、源页面disposal/切换/返回、锁请求/owner退休均触发停止；onResume和focus gain不会自动播放。picker使Activity暂停也停止旧播放。 |
| 焦点/路由 | LOSS/TRANSIENT/DUCK以及ACTION_AUDIO_BECOMING_NOISY都停止释放，不抢音量、不默认转扬声器、不查受限蓝牙设备。监听器仅在该请求有效期间注册，旧callback只可拒绝。 |
| 资源未知 | track pause/flush/release异常或已拥有focus的abandon返回FAILED/异常，进入CLOSING_UNKNOWN并隔离自己的handle/lease；不显示资源已释放、不新建替代track、不把超时/Job取消当ACK。没有自动循环release；显式核对也只处理本会话。 |

平台依据：[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)明确非阻塞可短写，流模式stop会等队列播放；立即停止须pause+flush，release释放资源。
[Audio focus](https://developer.android.com/media/optimize/audio-focus)说明目标>=35的焦点限制；[AudioManager](https://developer.android.com/reference/android/media/AudioManager#abandonAudioFocusRequest(android.media.AudioFocusRequest))提供释放结果码。
[输出路由说明](https://developer.android.com/media/platform/output)建议播放期间监听becoming-noisy。以上是平台合同，不是本应用已通过的设备结论；系统调用返回也不证明声学零延迟。

播放UI状态：IDLE → LOADING/FOCUS → PLAYING → CLOSING → IDLE；失败前无track可清理完直接给失败，资源不确定则CLOSING_UNKNOWN。
主卡提供“播放原音/停止播放”、时长/字节及原文版本，“原音未转写”；不提供暂停/续播/seek或后台按钮。
UNKNOWN保留可见停止状态；来源面板可立即隐藏，但全局关闭未确认/写门仍在，不丢失结束资源的唯一控制入口。

应用锁现有手工/启动策略不改。播放/加载/关闭未知时`appAccessMayRetire`必须为false；正常停止并关闭源面板后才能按既有流程更改锁/关闭会话。
强制VM清除先退休所有音频准入，再在既有NonCancellable finally等待读取/播放器worker实际释放，然后擦RAM/关存储。
worker结束不enqueue回自己正在等的actor；关闭ACK异常不被最终的store.close掩盖，不声称已锁/已关闭。不存在“等一个已关闭actor才能释放track”的环。

导入picker未开流时只持内存token/URI；普通外部picker暂停不误作新录音，也不后台播放。
已接纳文件读取可在原owner计数生命周期内完成同次有限导入/释放/结算，不开启新的后台来源或重试；失去owner后旧结果不得交给新owner。
读取/释放未知仍按§3保留关闭边界，进程死亡不会自动重开URI或恢复播放。

## 6. 验收路径（独审后实施，以下本轮均NOT RUN）

| 必要场景 | 验收断言与实际层 |
| --- | --- |
| 真UI单入口 | 实际此刻/原文附件行可操作；无原音时仍可图文保存；导入/播放/停止/移除、原文返回均48dp及语义可读，窄屏/200%字体不截主操作。Compose实控件定位，不对AppBar不存在的scroll节点猜修。 |
| picker归属 | 一次claim；result早于resume暂存不读取；配置变化同owner可继续，新VM/旧route/锁后结果丢弃；MIME空/假大小、超限、坏RIFF/EOF/通道格式均无写入和假成功。 |
| 已审录入合同 | 排空已接编辑后完整绑定；取消在读/检查/最终化竞态的仲裁正确；WRITING不完整可重试；发布后故障+明确放弃+核对不转保存；资源真实关闭后才解冻。 |
| 真实来源读 | 已保存/历史音频经完整认证才有PCM；替换ID、版本/generation变化、回收站/恢复ABA、坏tag/hash/size时拒绝；图片与音频不串解码/计数。 |
| 活跃借读/写入口 | 用实际数据实现和同owner生产DI，Pin下直接调用每一表内writer，断言文件/SQL均未改；准备阶段旧prefix（含sync嵌套）可排空，后来UI写不入队/不污染UI，后台并发借写使本次play拒绝而非等未来actor；新增source门不破坏既有capture cutoff。 |
| 平台串行与取消 | 阻塞focus/build/play的合成端口中退休，已赢关闭不再准入；partial/zero/error写、尾帧、focus迟到/丢失/noisy停止；fake sink验证pause+flush清队列，不能只断言某函数被调用。 |
| 实际释放 | source disposal、Activity pause、保存/移除前停止、强制VM关闭等真实worker释放；track或focus释放未知不假idle/解冻/关闭ACK，不死锁actor，不回调复活旧播放。 |
| 往返 | 合成明确标识WAV+图文导入→草稿试听→保存→准确版本原文播放；现有加密选择包/全备份往返原字节/hash不变，删恢复保留隔离；不运行真实模型。 |

纯Kotlin/真实SQLite合同复用现有ai-protocol、record-queue工程，平台调用复用app既有Robolectric/Android编译路径，不为数字新建外围项目。
新增共享源同时更新两套Sync；平台适配器若进入受控原生项目，必须同步其实际源与输入核验清单，不用shell替代被测实现。
先保存因果失败与相关回归；产品独审后按变更运行必要完整合同/native及一次正常分支push触发的有界CI，APK/lint/Compose/Preview结论分别列。
设计-only不执行上述回归、不重跑39480585794，不给新设计提交借用34036d8绿灯。

真实文件provider、扬声器/耳机/蓝牙、焦点竞争、锁屏/进程死亡、16KB/性能/可访问性仍需设备验收，当前均未运行。
本设计通过也不表示麦克风录音、ASR/OCR或D03/D04/D07整体验收完成；后续新增RECORD_AUDIO/SDK条款仍须单独处理。
