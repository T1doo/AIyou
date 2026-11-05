# F08 备份覆盖范围与容量核对（2026-11-06）

> [!info] 核对范围
> 对应计划 `AUD-01` 的能力盘点，以及后续 `CORE-03`／`UI-05` 的验收输入。
> 源码基线：`4eab72e49ed519ed35a8c32db7327df125b5b060`。
> 本文是源码与已登记测试入口的静态核对，不是新装恢复或设备验收结果。

## 格式与数据库版本分别看

当前 `RecordsDatabase` 为 Room **17**；完整离线包是独立逻辑格式，支持 **v8–v16**。
`FullBackupData.version` 按实际内容及历史导入回执选择所需格式；不是每个新导出的包
都必须使用 v16。问答历史需要 v16，问题收藏需要至少 v15，原音需要至少 v9。
旧格式保持各自表布局；Room 17 不是备份格式编号。

[早期 F08 实现记录](F08_FULL_OFFLINE_BACKUP.md) 中的 Room 12／v8 是历史范围，
不能作为当前音频、问题收藏及问答历史覆盖情况的完整说明。

## 当前覆盖与排除

| 对象 | 源码核对结果 | 主要位置 |
| --- | --- | --- |
| 原始记录、回收站、修订、收藏、手工标签／人物、事件日期 | 固定逻辑列纳入；原文及修订身份保留 | `FullBackupTables.specs`、`SqlFullBackupRepository.snapshot` |
| 原图与受支持原音 | 已保存修订和当前捕获草稿的媒体描述、字节和哈希纳入；原音不是被旧“图片”变量名排除 | `FullBackupData.media`、`snapshot`、`FullBackupCodec.validate` |
| 捕获草稿、保留的原文编辑草稿 | 纳入，仍为草稿；非 READY 媒体／不明中间态拒绝 | `FullBackupCodec.decodeCapture`、`captureData`、`restoreOwned` |
| 正式待办、待确认提案 | 共用表的不同有界 payload 纳入；提案不变成正式任务 | `manual_tasks`、`FullBackupCells.transform` |
| 人工记忆、偏好、事实、原音转写及片段、图片文字的既有纠正 | 通过各自 portable codec 校验、换密钥封装；不证明真实 ASR／OCR 已启用 | `FullBackupCells.validatePortable`、`transform` |
| 问题收藏 | 单独 `saved_questions` 表纳入，包括删除终态 | `SavedQuestionCipher.portable`、v15 |
| 问答历史与来源关系 | `chat_messages`／`chat_message_sources` 纳入；历史内容不携带发送权限 | `ChatHistoryPortable`、v16 |
| 删除证明、指定完成／导入回执 | 固定表和已知操作纳入；未知回执拒绝 | `FullBackupTables.receipts`、`validateReceipts` |
| 提醒 | 保留任务的提醒意图；导入后为 `REVOKED`，命令清空，运行进度不迁移 | `ManualTaskCipher.portable`、`restoreOwned` |
| 联网授权 | `REMOTE_ALLOWED` 规范化为 `LOCAL_ONLY`，`AI_NONE` 保留；connection／consent 清空 | `snapshot`、`FullBackupCodec.validate` |
| 模型配置、能力 profile、模型作业／注释、搜索索引、文件所有权中间表、提醒运行元数据 | 明确排除；索引通过本地作业重建，不恢复远端作业执行 | `FullBackupTables.excluded`、`retainedJobs`、`excludedJobs` |
| API Key、设备 Keystore／锁、外观偏好 | 不属于可移植记录资料库包；完整备份不是设备镜像 | 备份表清单及设备侧独立存储 |

Room 17 导出 schema 的 **25 个实体**，均能对应到 **16 个纳入表**或 **9 个明确排除表**，
此次静态集合核对没有发现未分类实体。这只证明表分类完整：不证明每个对象的所有
业务含义、设备文件、异常情况与未来 schema 都已完成验收。

## 当前硬边界

| 对象 | 当前上限／规则 |
| --- | --- |
| 记录 | `entries` 最多 **256 行**，包含回收站记录 |
| 原文修订 | `entry_revisions` 最多 **4096 行** |
| 媒体附件 | 已保存版本与捕获草稿合计最多 **256 个**；图片＋原音合计最多 **64 MiB** |
| 单张图片 | JPEG／PNG，最多 **16 MiB** |
| 单个原音 | 固定 PCM WAV：44.1 kHz、单声道、16 bit，最长 **60 秒**；不是任意音频解码 |
| 完整逻辑清单 | 最多 **16 MiB**，表行数和估算字节先检查，再读取批量 payload |
| 编辑草稿 | 最多 **256 份** |
| 正式任务＋提案 | `manual_tasks` 合计最多 **4096 行** |
| 本地候选／人工记忆等 | `local_candidate_batches` 共用上限 **4096 行**，不是每一类型各 4096 |
| 问题收藏 | 最多 **128 行**，包含删除墓碑；删除不会重置保留上限 |
| 问答历史 | 最多 **128 行**，来源关系最多 **640 行**，另有 **4 MiB** 内容预算 |
| 作业／包回执 | `local_jobs`／`record_package_receipts` 各最多 **16384 行**；只接受已知语义 |

“附件最多 256 个”与“记录最多 256 条”分别计数；媒体预算也包括旧修订与草稿，
不能解读为 256 张图片外还能另加 256 段原音。`RecordMediaPackages.MAX_IMAGES=20`
属于既有选定记录包，完整备份的 256 个上限由独立验证和导入参数指定，不能混用。

超限、未知表／列／profile、未完成附件或不明状态会拒绝整个快照／导入，源码没有
截断后继续宣称全库成功的路径。当前边界不能覆盖任意规模资料库；万条检索目标
也不能当作已支持万条整库备份。后续容量扩展需单独核对内存、流式认证、失败恢复
与格式兼容；本次没有擅自提高限制。

> [!info] 界面文案修正
> 上述基线的 `FullBackupContent.kt` 仍写“256 张 JPEG/PNG 原图”“原图共64 MiB”，
> 未表达原音也计入同一媒体数量／总字节预算。本批候选已改为图片＋原音合计，
> 并明确包含历史版本和草稿；没有改变限制、展开结构或恢复门槛。当前候选的
> 检查和设备展开结果须另行登记，不能用本报告代替产品验证。

## 恢复安全规则与证据缺口

导入只接受严格空的逻辑目标。精确默认空捕获槽允许初始化，已有记录、非空草稿、
旧删除证明或未知数据不自动清理。明确相同认证备份的 PENDING／DONE 回执用于
重试和重放核对；不会拿另一个备份覆盖不明失败现场。

当前生产路径先保存 PENDING 意图，认证并发布媒体／编辑草稿，再在最终事务中
验空、换密钥导入、核对逻辑内容与外键，最后提交 DONE。远端权限、提醒命令
和发送作业不会随历史内容恢复成可执行行动。

已存在的相关主机测试入口包括 `FullBackupTest`、`FullBackupControllerTest`、
`TaskUndoFullBackupTest`、`SourcePreferenceBackupTest`、`SourceFactBackupTest`、
`AudioTranscriptRangeBackupTest`、`SavedQuestionFullBackupTest`、`ChatHistoryBackupTest`。
本文仅确认这些测试源码存在，当前执行结果须绑定具体 CI SHA 与 XML，不能凭
方法名认定通过。主机真实 SQLite／JCA 也不替代 Android Room／SQLCipher、Keystore
和 SAF 提供器。

README 登记的历史 D08 运行 `40473284135` 在 admission 阶段失败，尚未进入实际
恢复；相应旧云端 KVM 清理确认也未闭合。它既不是恢复成功证据，也不能直接
证明恢复算法失败。本轮没有触碰旧云端 ACL、清理未知现场或执行用户资料恢复。

后续 `CORE-03` 仍需真实 SAF → 全新安装恢复，并核验错误口令、损坏、空间不足、
中断重试、删除传播及同一备份重放；本报告不关闭该任务。

## 源码定位

- `core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordsDatabase.kt`
- `core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase/17.json`
- `core/data/src/main/kotlin/com/t1doo/aiyou/data/FullBackupCodec.kt`
- `core/data/src/main/kotlin/com/t1doo/aiyou/data/SqlFullBackupRepository.kt`
- `core/data/src/main/kotlin/com/t1doo/aiyou/data/RecordMediaPackageCipher.kt`
- `core/data/src/main/kotlin/com/t1doo/aiyou/data/ManualTaskCipher.kt`
- `core/model/src/main/kotlin/com/t1doo/aiyou/model/RecordPackageImage.kt`
- `core/model/src/main/kotlin/com/t1doo/aiyou/model/LocalPcmAudio.kt`
- `core/model/src/main/kotlin/com/t1doo/aiyou/model/SavedQuestions.kt`
- `core/model/src/main/kotlin/com/t1doo/aiyou/model/ChatMessages.kt`
