# D02 实际设备验收入口（设备结果仍 OPEN）

本切片接齐已有 **13 + 4 + 2 = 19** 项原生测试，并增加外部强停分阶段验收。编译、源码收集、DEX 注解收集、Python 协议检查都不是设备执行。原 Master §17 的离线、重启恢复、迁移门槛保持不变；不提前实现 D03，不为收藏/标签自行指定阶段。

## 最小受支持环境与命令

需要已有完整 JDK17、合法已安装 API37 revision2/BuildTools36 的构建 SDK（具体锁定工具链见 BUILD.md）、该主机已有匹配 SDK 接受记录、adb/aapt，以及已解锁且 ADB 已授权的专用 API≥26 Android 设备。APK 内实际 ABI 必须匹配设备。可用既有受支持 AVD 代替一般设备测试，但镜像、模拟器、VM 加速必须已可用；Linux 需已有可用 KVM，不新增权限，不重试旧无加速 TCG。16KB 运行与真机性能另需对应设备。官方：[sdkmanager](https://developer.android.com/tools/sdkmanager)、[SDK 条款](https://developer.android.com/studio/terms)、[模拟器加速](https://developer.android.com/studio/run/emulator-acceleration)。新协议必须先显示具体包、完整条款链接和最小批准动作；脚本没有安装 SDK 或接受协议功能。

在精确已审核、干净 SHA 的仓库根目录：

```sh
scripts/gradle.sh :app:checkDebugAarMetadata :app:assembleDebug :core:data:assembleDebugAndroidTest :app:assembleDebugAndroidTest
python3 scripts/inspect-d02-device-test-apks.py
python3 scripts/run-d02-device-acceptance.py --collect-source
python3 scripts/run-d02-device-acceptance.py \
  --serial YOUR_EXISTING_DEDICATED_SERIAL \
  --expected-sha "$(git rev-parse HEAD)" \
  --apk-manifest app/build/reports/device-apk-manifest.json \
  --output /tmp/aiyou-d02-device-evidence-UNIQUE
```

也可使用同 SHA CI 生成的三份 APK 与 `device-apk-manifest.json`，放回 manifest 声明的路径；逐一验证真实 SHA256/大小。脚本核对本机 SDK 既有接受记录、实际设备/API/ABI/page size、实际 APK manifest/runner/target。**要求这些实际目标包均尚未安装**，防止测试碰用户库。安装用实际 APK 推导的包名，没有 `-r`/卸载/清数据/改权限；每阶段只强停本次新安装的目标/测试包，核实 pidof 已空，再由原生 instrumentation 启动新进程。结束后保留应用与合成库；失败保全日志，不自动擦除或重试。只想核对 inventory 用 `--collect-source`，不连接设备。

当前保存云环境实查：无 SDK 包/接受记录、adb/emulator、/dev/kvm。此前同 SHA CI 38165543041 有已接受 SDK 与 adb，但无 emulator/system-image、KVM 不可用；已结束 job 不是持续设备服务。当前不存在受支持可用 serial 的证据。其他未暴露环境不能假定可用。因此此轮完整设备运行 **NOT RUN**，不伪造安装/启动/强停/重启日志。

## 19 项逐条映射

脚本 `d02_device_acceptance.py` 固定精确类/方法集合。源码漂移直接失败；实际 runner 需每个方法恰好一次 start/pass、唯一成功 terminal、零 skip/error，数量相同但方法不同也失败。CI `inspect-d02-device-test-apks.py` 校验实际 DEX checksum/signature，按 `@org.junit.Test` 注解收集编译后的精确集合，而非只搜字符串。下表各项目前均 **NOT RUN on Android device**。

| # | 类（均 com.t1doo.aiyou.data） | 方法 | 实际断言对象 |
|---|---|---|---|
|1|EncryptedRecordsIntegrationTest|encryptedDraftAndEntrySurviveCloseAndReopen|原文/草稿/时间/LOCAL_ONLY 加密重开|
|2|同上|actualJobInsertFailureRollsBackEntryRevisionAndDraftConsumption|真实 SQL 故障下整事务回滚|
|3|同上|editCreatesHistoryAndRejectsStaleRevision|不可变历史、旧版本 CAS 拒绝|
|4|同上|editTransactionFailureRetainsCurrentAndPreviousOriginal|修改事务失败保全原文|
|5|同上|repeatedAndConcurrentSaveHaveOneDurableEntryAndJob|重复并发保存单条记录/作业|
|6|同上|changedDraftCannotBeCommittedFromStaleSnapshot|旧草稿快照拒绝|
|7|同上|directRevisionOverwriteAndDuplicateInsertAreRejected|SQL 防覆写与重复版本|
|8|同上|wrongDatabaseKeyFailsWithoutResettingExistingFile|错误密钥不重置库|
|9|同上|keystoreWrapReopensAndTamperingFailsWithoutReplacement|真实 Keystore 包装/篡改保全|
|10|同上|missingKeystoreAliasOrEnvelopeNeverCreatesReplacementKey|丢 alias/envelope 不换钥|
|11|同上|encryptedVersionOneMigrationPreservesDraftAndOriginalAndAddsLocalJob|加密 v1 迁移|
|12|同上|invalidMigrationSchemaIsRejectedWithoutDestructiveFallback|错误迁移无破坏 fallback|
|13|同上|fiveAcknowledgedSavesSurviveSeparateDatabaseReopens|五次已确认保存/独立重开|
|14|AttachmentRecordsIntegrationTest|encryptedAttachmentIsRevisionBoundAndReopensWithoutChangingTextDraftOrJobs|附件绑定不可变版本/独立草稿|
|15|同上|failedFinalRoomTransitionPreservesPublishedBlobAndStartupRecoveryIsIdempotent|真实 READY 转移失败、启动幂等恢复|
|16|同上|encryptedVersionTwoFixtureMigratesWithoutChangingOriginalsDraftAndLedger|加密 v2→v3 附件 schema 迁移|
|17|同上|wrappedMediaKeyHasSeparatePurposeAndMissingMaterialNeverReplacesIt|独立媒体 key/AAD 与缺失保全|
|18|OriginalEditRecordsIntegrationTest|encryptedEditAndCaptureSurviveReopenWithoutChangingFormalOriginal|实际编辑 cipher/捕获独立持久化|
|19|同上|changedNativeOriginalRejectsStaleEditAndDiscardTombstoneSurvivesReopen|编辑冲突/放弃墓碑/迟到写拒绝|

## 真实跨进程阶段与证据

`ProcessRestartPersistenceTest` 同一方法由四次外部 instrumentation 调用执行。每阶段输出独立原生 PID、UUID token、phase；脚本核匹配元数据与不同 PID，强停后的 pidof 日志与完整 terminal 一起保全。既有 19 项以外单独报告，不混入19计数。

1. **write**：真实 AndroidKeystore 分别包装 DB/媒体/编辑随机 key；实际 Room/SQLCipher 提交文字+捕获草稿、ACTIVE 编辑、READY 附件；实际 SQLite trigger 拒绝另一个附件 READY，保留已发布密文/STAGED与hash；返回成功后外部强停。
2. **read**：新 PID 重新解包真实 key；启动 prepare 遇到仍存在的 READY 故障时失败且字节保全，删除仅本合成库故障 trigger 后 prepare 两次恢复 READY；确认幂等不重拷；编辑草稿恢复，真实 receipt 插入 SQL 故障令新 revision+LOCAL_INDEX+receipt 全回滚；撤故障显式重试，正式追加一次；同一编辑重复提交返回原回执，留 ACTIVE 模拟提交后收尾前退出。随后外部强停。
3. **confirm**：第三 PID 核正式回执、错误身份/版本无回执，确认不再次追加，写 COMMITTED 墓碑；核两份原文、独立捕获草稿、旧版本附件、两条 PENDING LOCAL_INDEX 与一条 CONFIRMED receipt。
4. **cleanup**：只删显式合成 UUID 目录及它自己的三个 alias；不删生产目录、未知锁或其他数据。

`ProductionColdStartPersistenceTest` 两个外部分离阶段使用 **实际生产 open(Application)、Room/SQLCipher、独立 Keystore 包装、生产 ViewModel 与 MainActivity**。write 要求生产库尚不存在，留下正式事务已提交但 ACTIVE 草稿未收尾的断点输入。另一合成记录通过生产 ViewModel 经一次草稿收尾异常、一次读取确认异常、显式重试完成，始终只新增一个版本。异常只在测试接口的确认交付边界注入；真实 DB/cipher/key 没有替换或 mock。read 新 PID 用 `ActivityScenario.launch(MainActivity)` 的真实 ViewModel，恢复列表→恢复编辑→核 awaitingReadback/非冲突→确认→无新增 revision，且独立捕获草稿不变。ActivityScenario 是本新 PID 内真实启动，不是跨 PID 证据的替代；跨 PID 来自外部强停和实际 PID。

输出目录保存每条命令 stdout/stderr/超时、APK badging/manifest、安装、实际 runner、19方法完整结果、强停/pidof、6次阶段 terminal/PID、许可证前后 hash 与 RESULT.json。中途失败仅记录已真实结束的阶段，剩余 NOT RUN。不延长旧单例240s上限，不对失败阶段循环补测。

## 对应生产修正与边界

此前 `committedOriginal` 仅在 ViewModel 内存中；提交后、草稿 COMMITTED 收尾前杀进程，恢复会误判自己的正式版本为外部冲突。本次 `reviseFromDraft` 在 **同一个加密 Room 事务** 写 revision、PENDING LOCAL_INDEX 和完成态 ORIGINAL_EDIT_COMMIT/CONFIRMED 回执。回执 ID 绑定完整已确认编辑身份、entry、base/source revision、version 和精确 UTF-16 内容的摘要，既有 local_jobs ledger 可容纳完成态操作，**不改 schema3、不冒充待执行工作、不触发后台 Worker**。确认查询核 operation/state/FK版本/内容/hash；仅同文本、不同草稿身份或版本不能认领提交。生产恢复据回执进入 awaitingReadback，确认重试不调用再次追加。

原 SDK/JDK、依赖版本与原两方案保留；app 的新 instrumentation 依赖复用已有锁定 runner/junit 版本，锁文件由真实 Gradle dependencies 生成。设备 native collection/运行、真实旧4.19.0库读取、加密迁移及失败保全、Keystore首次解锁、真实断电/fsync、16KB runtime、IME/TalkBack/真机性能、JNIString NOTICE 祖源都维持各自未验门槛。此切片及设备 PASS 都不能自动关闭整阶段或替代上述门槛。
