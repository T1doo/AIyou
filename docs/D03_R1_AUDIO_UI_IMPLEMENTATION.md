# D03-R1 原音文件 UI 与前台播放候选

设计 `13cefb32bd3e525eed0a0f143b64b56196e470bf` 已获父侧限定 ACK；本文件描述实现候选，不表示设备或完整D03验收。
生产/UI基础提交 `5660074b97cb358897417c5b0f52f83956f3e963`；窄屏组合内滚动修正 `4a2a4a5671b101f1338a20db8a137ec396dbc74d`；
最终路由/前台领取修正 `b71c040ba36bb19f46c6b4bc37e796b4550bb066`。
原远端基线仍 `34036d8a452a926b239dea48699c7cb8676061e6`。本候选尚未推送或开始新CI；须先独审实现。

## 用户闭环

“此刻”新增“导入原音文件”：系统单文件SAF选择 → 严格检查 → 加入加密草稿 → 显式播放/停止/移除 → 明确保存 →
打开准确原文的附件行播放；历史附件播放也核对当前原文及活跃历史请求。
纯原音允许空正文，图片仍走原来的图片解码器。空正文改用中性“附件记录”，不把原音称作图片或虚构转写。
播放器全局状态和停止按钮保留在AppBar下面，关闭源页面后仍可看到资源未确认状态。
草稿写入与保存不依赖AI；`LOCAL_ONLY`、`AI_NONE`均能人工读取原音，不创建派生文字、事实或任务。

生产没有合成录音按钮，没有麦克风适配器/许可请求。新增Compose测试在页面上明确显示“合成演示数据·非麦克风录音”。
用户文件导入不是实录。该测试的SAF launcher被内存registry截获，WAV由合成端口交付，不冒称实际provider或设备选择器通过。

## 导入、资源与请求归属

- 使用`ACTION_OPEN_DOCUMENT`、`CATEGORY_OPENABLE`、`EXTRA_LOCAL_ONLY=true`、单文件和WAV MIME提示；只接`content:`。
  不调用takePersistableUriPermission，不向DB/日志/应用SavedState写URI/文件名，不写明文temp/cache/MediaStore。
  第三方provider对local-only请求的行为及Android真实恢复行为仍属设备边界。
- 开始先关新草稿准入、排空已接编辑，再核完整持久快照、当前binding与可见text；保留已审音频Reservation/CAS和格式。
  最多60秒、44100Hz/mono/PCM16 canonical WAV，最多5292044字节。最多读上限+1，必须真实EOF，完整检查RIFF长度及固定profile。
  额外chunk/尾字节、多声道、其他采样率和截断均拒绝，不转码、不截取前缀冒充完整原音。总附件20个/64MiB配额不变。
- 已领取请求使用VM/controller持有的UUID及路由代数；旧Compose对象只校验开始点击，不作为长期请求的路由证明。
  配置重建不作真实导航；用户导航推进代数并取消旧导入。新VM不采用旧UUID。
  前台状态为可观察流，picker仅在RESUMED时领取一次；result早于resume只在当前Request留RAM，失去owner/取消则不打开。
- provider open/read与close在独立IO producer；旧actor只做准入、绑定和最终结算。真实close返回前不提交、不解冻、不打开第二源。
  流关闭异常隔离同一handle和波形。显式“重试关闭同一个文件”只关闭该handle；确认后仍保持UNKNOWN，需选择同次保存/核对或明确放弃。
  不用取消协程、超时或移除UI代替资源ACK；进程死亡不保证恢复未提交RAM/URI。
- 保留同intent重试、完整后继快照及粘滞放弃。放弃意图在UI接收时保存，actor执行持久步骤；重试/核对先占FINALIZING，阻止在已接纳提交中再接受撤回。
  资源已确认、尚无持久intent时，明确放弃也必须只读核同一base后才清零；不创假intent、不触碰其他素材。

## 两阶段播放门与真实来源

`CaptureDraftGate.PlaybackPin`不持久化，不冒充草稿Reservation。UI_PREPARING只关新源写UI准入，已接actor前缀可继续排空；
ACTIVE前同时核draft mutation、source leases和destruction计数归零及无capture reservation。
新增同步source借用不复用capture cutoff，不通过线程局部变量继承协程permit。并发writer尚未退出时拒绝本次播放，不等待需要未来同actor命令的工作。

生产`EncryptedEntryRepository`在各能力构造前创建唯一门，注入capture、修订、附件、回收站/擦除、包/全备份恢复、范围授权和嵌套RemoteTextTransactions。
新source入口在SQL/文件lease前借同门。实际ViewModel、旧正文入口和相关controller在修改UI/排命令前也用同门；排队permit在退出/拒绝/退休时释放。
播放期间先停止再编辑、保存、修改附件/范围或删除恢复；列表可见性与写入冻结分开。

源码复核发现旧附件`list()`隐式恢复、`read()`损坏时会写CORRUPT：
生产`OwnerAttachmentStore`改用严格只读列表/完整认证读取；显式recover/save受门保护。
底层legacy list/read也计source借用，避免内部export等路径在ACTIVE Pin下通过隐式恢复/损坏标记绕写门；普通非借读路径保留其legacy行为。

草稿绑定完整snapshot和text；正式来源绑定entry、目标revision、当前revision、可见generation、完整附件descriptor/state；历史再核session/request。
Pin下先核持久来源、完整AEAD/tag/SHA/size与canonical WAV，再次核来源后才交给平台worker。缓存附件ID、UI按钮或`read(id)`本身均不是授权。
平台只拿已认证的自有波形，不接repository、DB、密钥、URI或Activity。

## 前台播放与关闭

实际Android适配器只用平台AudioTrack/AudioFocusRequest：即时临时焦点、拒绝延迟焦点、44100Hz/mono/PCM16 MODE_STREAM和非阻塞短写。
分块PCM scratch明确清零；时长在认证后展示，播放头消耗完末帧才完成，入队字节不当作已播放。
focus/build/play各有短准入边界，平台调用在monitor/SQL锁外串行；已准入调用若迟到，等待其实际返回再关资源，不声称能撤回在途系统调用。

Activity pause、真实导航、源面板失效、焦点LOSS/TRANSIENT/DUCK和becoming-noisy均停止；恢复前台/焦点GAIN不自动续播。
停止用pause→flush→release，随后确认自身focus/receiver清理。异常/FAILED保持CLOSING_UNKNOWN和Pin，显式核对只重试同一handle，不开替代track。
建轨抛错且无可确认handle时保留未知边界，不伪造释放；系统API回执不是声学零延迟或设备验收。

`appAccessMayRetire`及可观察idle包含播放器忙态；仅在实际释放/缓冲清零后重新允许原锁流程。
强制VM清除先退休准入，在NonCancellable owner finally等独立worker真正退出；未知时关闭回执失败，不能先关库抹key并声称已关闭。
worker不向正在等待自己的actor排一个释放任务。

## 验证、因果记录及交付边界

最终实际测试、输入指纹、原始失败/成功日志、XML及两套同步逐文件hash见本轮证据收据；不借旧CI绿灯。
当前Android metadata/APK/lint、Compose/Preview、真实文件provider、实际播放/权限、锁屏/旋转/杀进程、SQLCipher原生/16KB/OEM/性能/可访问性均未执行。
新增Compose两方法已列为必需门：窄屏200%字体的导入/播放/停止/一次保存/准确原文播放，以及显式移除后空记录拒绝。
AppBar返回按真实文本点击；Lazy列表先组合目标再在已存在控件上滚动，不猜不存在的节点。

受控原生工程复用应用已锁定的Room运行依赖：arch core-common/runtime 2.2.0、collection-jvm 1.5.0、sqlite-framework-android 2.7.0；
只补宿主classpath/lock，生产依赖、权限、Room v15、音频格式和原两方案未改，没有SDK条款接受或凭据。
生产DI的17入口门控使用实际Room/JCA；包参数使用故意不可读取的sentinel，证明先拒绝再解析；真实包往返仍由完整合同门执行。
平台focus/receiver为API35 JVM模拟，不能冒称真实通知、耳机路由或硬件播放。

保全的因果失败包括：初次CLI重复console参数（未进入编译）、图片接口误包装/构造调用/类型推断编译错误、新测试SQL参数误按list传给vararg、
宿主缺ArchTaskExecutor及新增中断行fixture混合数组推断错误。均按确定根因修正，不删除断言、不降门/超时，不重复失败SHA的CI。
旋转修正来自自审实际旧闭包生命周期问题；此前通过轮只作为各自输入的中间证据。

实现独审后才正常推既定开发分支并执行一次必要有界CI；main不改、不强推、不部署。
先前本地设计提交带`[skip ci]`，后续推送须明确处理该设计提交的skip语义，确保产品精确SHA实际运行一次，不能声称push即CI启动。
D03实录/ASR/OCR、D04真实绑定和D07设备门保持未完成，不因本片编译或合成波形通过而改变。


## 本机最终实测收据

以下均在产品 `b71c040ba36bb19f46c6b4bc37e796b4550bb066`、dirty0实际执行并核方法/XML，不把封存文档提交当作重新测试。

| 实际门 | 命令及结果 |
| --- | --- |
| 完整合同 | `python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test`；1388项（178/71/1139），0失败/错误/跳过。`verify-ai-protocol-results.py`严格通过。 |
| ViewModel队列 | `python3 scripts/jvm-gradle.py -p checks/record-queue test`；队列188项，0失败/错误/跳过。`verify-test-results.py --queue`必需方法通过，63个同步生产源字节一致。 |
| 受控原生构件 | `python3 scripts/jvm-gradle.py -p checks/reminder-native :test`；45项，0失败/错误/跳过。`verify-reminder-native-results.py`核199个输入文件及实际Room v15导出一致。 |

完整合同输入SHA256：`394377c972c2fd14c2fd8c1f34d979645e8db94a52e6dd47d5c24fe2b49337cb`。
受控原生输入SHA256：`03a626a2a8f3d4aac25f9c9792ffa0208a3aea47c153519b56cc7a29d273f493`。
完整合同与队列的core依赖会重叠执行，不将三门相加宣传为独立测试总数。

两套工程的data/VM/queue同步清单分别核73、46、63个实际文件。

[最终本机证据 ZIP](evidence/D03_R1_LOCAL_EVIDENCE.zip)：317749字节，SHA256 `0aca46d52a4275ab759fca1518181e079eb22fe836876c48801e37006246c602`，229项manifest、230个ZIP条目，逐项大小/hash/CRC已核。

本地ANDROID_HOME与ANDROID_SDK_ROOT均未配置，工作区没有android-37/android.jar；没有安装SDK或接受许可。Android/Compose新门均NOT RUN，设备门均NOT RUN。没有新APK或新CI结果。
