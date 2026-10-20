# D03 短录音本地存档：下一闭环设计候选

基线 `791f2ecfc599a36230648e27634599e33fa39f4b`。两份原始方案 §3.1、§8.1、§12.2、§17
要求短录音可靠存档、明确能力降级和可纠正转写；本设计先完成原音，转写另片处理。
这是待独审的设计，不是已实现或已验收能力。此前 D07 R2b 的精确提交 CI 已封存，设备边界不变。

## 选择与源码事实

| 未完成范围 | 当前事实及优先级 |
| --- | --- |
| D03 短音频 | `CaptureDraft`/`CaptureRepository`/`SqlCaptureRepository` 和混合输入页面只接图片；没有录音、播放或音频草稿。直接补齐原方案的另一输入入口，优先选择。 |
| D03 OCR / ASR | 没有实际引擎。bundled OCR 需另核依赖/模型许可；ASR 不可由通用识别服务推断本地处理。先保留原音，不把未转写说成识别失败。 |
| D04 生产绑定 | `LocalRecordsViewModel.bindAi`/`capabilityProbe` 仍默认 null，Manifest 移除 INTERNET；真实凭据和发送不在现授权内。不造假的运行连接或 secretRef。 |
| D07 / D09 | R2b 已有本轮 APK/lint/Compose/Preview 证据；真实权限、提醒、重启、SQLCipher、OEM、性能与可访问性未验。不为此再跑无变化主机测试。 |

现有 `EncryptedAttachmentStore.validate` **已经**接受 audio/wav、audio/mp4、audio/ogg，并有 16 MiB 内部上限；
这不等于产品音频入口存在。复用其现有 key、AAD、完整 AEAD/哈希/大小验证、fsync/原子发布和删除登记。
草稿格式当前 1–2；原文/媒体包已有 1–7，完整备份已有 v8；不能把 v3 当成空闲版本。

## 用户可完成的路径

“此刻”点击“录一段” → 当前页面内说明麦克风用途 → 当前请求获得系统许可后由用户再次点击开始 →
显示录音时长及“尚未保存” → 点击结束或达到 60 秒 → 停止采集、封存完整 WAV、加密暂存并读回确认 →
草稿出现“原音已加密暂存，未转写” → 可播放/移除/添加文字和图片 → 明确保存记录 →
回忆/原文详情按版本播放同一原音；备份恢复后仍可播放，删除遵守现有传播链。

最多一段 60 秒原音/草稿；加入原音后与图片合计最多 20 个附件、64 MiB，单个附件仍不超过 16 MiB。
已有音频须先明确移除再重录，不静默替换。录音时暂停选图、正文保存、清空与处理范围变更，正文和既有素材保留。
纯音频记录允许空正文，但必须存在本版本已完整认证的原音；列表显示“音频记录”，不生成虚构转写或检索词。
失败提示留在录音卡片；拒绝/撤销许可或硬件不可用仍可继续文字、图片输入。

## 平台与隐私选择

候选采用 SDK 已有的 `AudioRecord`/`AudioTrack`，不引入库、模型、SDK 版本或新条款。
平台 [AudioRecord 合同](https://developer.android.com/reference/android/media/AudioRecord.html)支持读取 PCM；
本片固定 44100 Hz、单声道、16-bit PCM，初始化、实际参数或读取失败即显示不可用，不偷偷改制式或重启麦克风。
`read(short[],...,READ_NON_BLOCKING)` 后显式转为 little-endian；空读按有界节奏让出，错误终止，不忙轮询。
完整读到的样本决定时长；展示同时受单调时钟 60 秒上限约束，挂起/无数据不延长录音窗口。

使用应用生成的严格 canonical WAV：44 字节 RIFF/WAVE PCM 头 + 偶数字节数据；
头中 sampleRate=44100、channels=1、bits=16、byteRate=88200、blockAlign=2，RIFF/data 长度与完整 EOF 精确一致。
最多 2646000 样本，PCM 5292000 字节、文件 5292044 字节；空音频拒绝。duration 从样本数计算，不信任 UI 计时器。
不接收任意 WAV/压缩音频导入，不在此设计增加通用媒体解析器；固定格式用于降低解码攻击面和依赖范围。

录音 PCM 只在有界进程内存中，停止后构造精确 WAV 与不可变 AttachmentIntent，再复用加密附件路径。
不写明文 temp/cache/MediaStore，不用可被其他组件读取的文件、URI 或 FileProvider。
停止时的 PCM/WAV 临时副本必须显式清零；峰值内存和 GC 需要设备测量，当前不能声称低内存验收。
未停止/未完成加密暂存前若进程被强杀，当前内存片段可能丢失；UI 必须一直显示尚未保存，不承诺恢复这段。
已确认的草稿正文、图片、其他记录与已暂存音频仍按现有恢复合同保护。
不新增分块密码格式、Keystore alias 或独立数据库来掩盖这个未提交边界。

没有选 MediaRecorder 文件方案：它的 [输出文件合同](https://developer.android.com/reference/android/media/MediaRecorder#setOutputFile(java.io.File))
需要可 seek 文件，不能未经验证就把它接入不可 seek 的加密管道；也不能先生成明文录音再加密。
没有开启 SpeechRecognizer。后续 ASR 必须独立核对 on-device 能力、输入原音和派生文字来源映射，不争抢当前麦克风。

## 录音归属、后台与关闭

每次录音绑定不可变 `(records owner, draft id/version, route generation, request id)`；许可结果不是录音授权的替身。
旧许可回调、旧路由、换 owner、清空后的旧草稿不能开麦或写入新草稿；获许可后也不自动开始，必须有新的明确点击。
初次启动不弹许可，返回设置不自动开始，无后台/长录音/前台服务。

| 状态/事件 | 必须处理 |
| --- | --- |
| IDLE / 许可待定 | 不持麦克风，不改变草稿；请求过期即丢弃回调。 |
| STARTING / RECORDING | 一个 session、一个设备实例；不持 SQL 事务、VM actor 或 D07 执行锁等待采样。固定内存和时间上限。 |
| 结束 / 超时 / 页面离开 / Activity ON_STOP | 原子封口一次，停止和 release 真实返回后不再读样本；有数据则最终化到原草稿，没有数据仅报告未录到内容。返回前台不继续录音。 |
| 显式取消本段 | 封口、真实停止/release、清零；不触碰正文、图片或既有正式附件。取消与结束按同一 session 仲裁，不能同时发布。 |
| FINALIZING / 结果未知 | 同一个 intent/hash 和原草稿身份核对，不开第二段、不重新创建 ID；持久状态与文件恢复区分 READY/FAILED，未知不能显示已存好。 |
| 手动锁定/切换保护策略 | 将采集、最终化、待核对状态加入现有 `appAccessMayRetire` 忙碌边界，提示先结束并确认；保持原有忙碌拒绝语义。 |
| onCleared / 强制退休 | 先封新请求，停止设备并加入原 owner 的关闭追踪；在 actor finally 的 NonCancellable 收尾中直接最终化已准入片段，再关闭库和抹 key。禁止向已关闭 commands 通道 enqueue 后等待自身。 |
| 硬件错误/许可撤销 | 停止；有效前缀可保存并标“录音可能中断”。没有有效前缀则不创建附件；不自动重试或恢复麦克风。 |
| 进程死亡 | 未提交 RAM 片段不可恢复；已持久 intent/密文依现有恢复核对。重启只读取，不自动录音或播放。 |

正常停止/ON_STOP 的最终化经当前 VM actor；强制退休的最终化只能用该 owner 已准入的不可变身份直接收尾。
两条路径共享一次结算标志。旧 draft 已变更/被清空、删除 fence 或 intent 不匹配时不得写回；清零片段并输出固定失败状态。
现有 `mixedCapture.dispose()` 只封 UI，`disposeOnIo()` 会清准备缓冲；音频最终化必须在后者之前完成，
不得由先行 dispose 抹掉已准入的音频缓冲/身份，也不得为收尾重新把 `available()` 或新 session 设为有效。
正在进行的原始文件/SQL 操作不能因 CoroutineScope 取消就宣称完成；关闭未知继续使 appAccessClosure 未确认，不能先开替代 owner。
平台停止/release 异常不等同设备已释放；本 session 禁止再开，保全脱敏状态，不承诺硬性 Binder 超时。

## 播放与访问边界

只在用户点击当前可读来源时播放。先由现有附件读取完整认证 AEAD、SHA、字节数，再校验 canonical WAV。
播放器接收 PCM 内存，不接密文路径、未认证流或可外发 URI；不向 MediaPlayer 明文临时文件兜底。
[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack) 使用 MODE_STREAM 和小块写入；
切换记录、原文版本、回收/删除、应用后台、焦点丢失、输出设备断开或 owner 退休立即封口并 pause/flush/release。
不能把 stop() 当即时清空已排队音频；释放真实完成后清零，迟到 write/completion 不更新新页面。
焦点申请仅在前台明确播放点击下进行；失败则不播放、保留原音。一次只有录音或播放其一，不做自动播放/后台播放。
系统输出切换和静音输入不总能由应用可靠识别，不能推断麦克风无竞争、声段完整或真实可听；这些属于设备验收。

## 存储、来源与可迁移性

保留现有 `images` API/编码，添加独立 `CaptureDraftAudio`/audio 操作，不把音频伪装成图片。
草稿 format 3 包含独立音频 intent/state；1/2 解码仍严格只接受原有合同，未含音频时保持旧写入格式与 fixture。
复用同一 `record_package_receipts` CAS、WRITING/READY/REMOVING、移除/清空/保存回执，不另造 job 或 outbox。
现有附件表能存 MIME/sha/size；duration 由认证 WAV 派生，暂不增列、Room 版本或迁移。原 schema 导出保持字节不变。
`commitCapture` 继续最多 20 个 intent；总数/总字节包括音频，旧图片配额不扩大。
正式保存仅在全部附件 READY 且再次认证后提交 entries/revision/attachments/索引作业/同一次保存回执；纯音频必须有本版本音频证据。
音频不是 SourceRef 的可引用文字；当前搜索只用手工文字/标签，后续 DerivedText/时间段映射另行设计。

不可上线“有原音、备份静默漏原音”的中间状态。新增音频包版本 **v9**（现有 v8 是完整备份）及 full-v9 逻辑格式，
沿用既有口令 KDF/AEAD 帧与总量限制，新增严格类型化的媒体描述符和音频声明；旧 1–8 读取及各 fixture 原字节不变。
选择包采用 `AIYOUPK9`、完整备份采用 `AIYOUFB9` 的独立 8 字节 magic，外层 version 均为 9；
认证 header/AAD 和逻辑 scope 必须一致，不仅靠未认证的字段分派或互认两种格式。具体字段及独立固定夹具随实现一起冻结和独审。
新格式显式区分选择包和完整备份，既有 tasks/history/manual people/edit drafts 等已选范围不得为音频降级或漏项。
旧格式绝不宽松接受音频 MIME；无音频时仍走原写入格式。音频-only 空正文只有同来源/版本的完整认证音频证据才能合法。
导出预检不支持的来源必须明确失败，不能静默跳过；实际提交用户入口前，选择包及完整备份的往返必须一起通过。
恢复生成新的 record/attachment ID 并重加密，清除旧远端/提醒运行资格；不恢复播放/录音/麦克风许可。
永久删除、回收、历史附件、所有文件创建登记、存储统计和删除后旧快照失效路径须纳入音频，不能只改当前草稿。

## 实施与验证门

1. 独审设计和许可边界；先实现受控平台端口/模型/真实 SQL+JCA 音频草稿、删除和备份往返，保留所有旧格式夹具。
2. 接入实际 Now/详情、AudioRecord/AudioTrack 和生命周期；录音许可单独取得明确准入后才可加入 Manifest/实际请求。
3. 只运行因变更必要的定向回归；核实际方法/XML/输入指纹后跑一次既有免费 CI。新增的原生组件编译及 Compose 不代替设备证据。

| 因果验收 | 必须证据 |
| --- | --- |
| 无许可/无设备/初始化失败、旧许可回调 | 不开麦、不建附件，不丢正文/图片；页面给可继续操作。 |
| 分片/空读/错误/最大样本、格式边界 | 实际 canonical WAV 和独立样本夹具一致；长度/样本/EOF/溢出/多余数据均校验，不用只有实现镜像的断言。 |
| 结束/取消/ON_STOP/退休竞争 | 一次停止结算、最多一次发布；原 owner 关闭前实际排空，旧会话不写新草稿。 |
| 文件与 SQL 各持久点失败/回执丢失 | 真 SQL/JCA、冷重开、同 intent 重试；未完整认证不 READY、不重复记录，不清其他素材。 |
| 纯音频/混合/历史/回收与永久删除 | 原音同字节可读，当前/历史版本严格，删除后旧播放请求和封存引用失效。 |
| 选择包和完整备份 | 图片/音频/空正文/历史和编辑草稿完整往返、旧 1–8 fixture 不变、错密钥/截断/多余 frame 拒绝，不重放授权或运行资格。 |
| Compose | 320dp/200% 字体、录音/保存/待核对/失败状态、只保存一次、返回原文同版本播放、后台停止的可见回执；先核实际控件与 LazyColumn 组合范围。 |
| 真设备 | 麦克风许可/撤销、真实声音采集和播放、电话/路由竞争、后台/旋转/强杀、存储不足、内存/耗电及可访问性另验；本设计全部 NOT RUN。 |

## 授权与交付状态

本次只改设计文档，没有产品、Manifest、依赖、密码格式或权限变更，没有新测试/CI/设备运行。
[源码审计清单](evidence/d03-short-audio-design-source-audit-20261020.json)绑定基线文件哈希及本次只读观察，不是执行测试证据。
原方案明确短录音方向，但当前持续授权仍禁止新增安全权限；
[Android 官方录音说明](https://developer.android.com/media/platform/mediarecorder)要求 RECORD_AUDIO 的 Manifest 声明和运行时同意。
**本次不加入 RECORD_AUDIO；设计 ACK 不自动扩大该权限边界。** 无 INTERNET/USE_EXACT_ALARM/前台服务/存储权限，
无真实模型/ASR 调用、新费用/凭据/条款接受、角色素材或部署。
独审可先审上面的用户闭环和收尾/备份合同；许可未明确准入前，后续只可推进已有授权的受控本地部分，不启用实际麦克风入口。
