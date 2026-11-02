# AIyou 前端日覆盖与证据

基准：Library `libfile_6df79df13dbc8191983f8ce6edbedf07` version 1，DOCX SHA256 `1c097a6180b91878a2eb7ce46660db8e144c17446f80544b8e0c7f178e877ce8`。已全文阅读并逐张检查36张嵌图（含19原创图板）；实际页面证据独立记录，文档图不计实测。

文档 main：`3d9f16bbc153dc00dd18ad5c42cf0d233d7e4350`；仅1个DOCX，远端读回hash一致。开发基线 `385463c5b168b0c66721a998e0c4b9f0b03c38f4`，包含全部51文件历史兼容链；不使用诊断tip。

## 页面 / 组件 / 状态合同

| 页面组 | 实施入口（app/src/main/java/com/t1doo/aiyou/） | 全量合同 | 状态与证据 |
|---|---|---|---|
| 首次使用 | ui/AIyouScreens.kt | 离线入口、连接、按需权限 | 待实施/待逐项验证；既有数据合同保留 |
| 此刻 | records/MixedCaptureScreen.kt | 轻输入、焦点态、原件、草稿、Undo、部分失败/UNKNOWN | 待实施/待逐项验证；既有数据合同保留 |
| 回忆 | records/LocalRecordsScreen.kt | 连续列表、网格、分类查询、活动条件和锚点 | 待实施/待逐项验证；既有数据合同保留 |
| 主动回顾 | records/FactRecapScreen.kt | 主动日/周、证据不足 | 待实施/待逐项验证；既有数据合同保留 |
| 原记录 | records/OriginalReadingScreen.kt; records/LocalAttachmentScreen.kt | 原件优先、源版本、复制、原音 | 待实施/待逐项验证；既有数据合同保留 |
| 编辑版本 | records/OriginalEditScreen.kt; records/OriginalHistoryScreen.kt | 版本只读、草稿、派生纠正 | 待实施/待逐项验证；既有数据合同保留 |
| 问一问 | records/LocalEvidenceAnswerSection.kt; records/SelectedQuestionConsentSection.kt | 范围确认、停止、来源失效、无INTERNET | 待实施/待逐项验证；既有数据合同保留 |
| 两类历史 | records/SavedQuestionsContent.kt; records/ChatHistoryContent.kt | 仅填问题、同UUID重试、历史来源重核 | 待实施/待逐项验证；既有数据合同保留 |
| 候选 | records/LocalCandidateDialog.kt; records/SourceMemoryInboxDialog.kt | 修改/拒绝/确认独立 | 待实施/待逐项验证；既有数据合同保留 |
| 正式待办 | records/ManualTasksScreen.kt | 分组、完成/撤销、详情、延后、提醒实际状态 | 待实施/待逐项验证；既有数据合同保留 |
| 设置连接 | ui/SettingsScreen.kt; ui/ModelConnectionSettings.kt | 字段标签、连接资格、密钥隐藏 | 待实施/待逐项验证；既有数据合同保留 |
| 隐私与锁 | ui/AppAccessScreen.kt; records/RecordAiSection.kt | 不扩大范围、应用锁 | 待实施/待逐项验证；既有数据合同保留 |
| 存储与用量 | ui/StorageUsageSection.kt | 原件/派生/缓存、未知用量 | 待实施/待逐项验证；既有数据合同保留 |
| 数据可带走 | records/OriginalExportScreen.kt; records/RecordPackageScreen.kt; records/FullBackupContent.kt | 严格空库、取消/未知、实际结果 | 待实施/待逐项验证；既有数据合同保留 |
| 删除回收 | records/RecycleBinScreen.kt | 对象确认、失败/未知、不冒成功 | 待实施/待逐项验证；既有数据合同保留 |
| 独立陪伴 | ui/Companion.kt; ui/UiPreferences.kt | 既有5角色、独立二级页、静态背景、减少动态/离页停止 | 待实施/待逐项验证；既有数据合同保留 |

## 27个迁移点

| 点 | 实施位置 | 状态 |
|---|---|---|
| A01 | AIyouPanel/OriginalReadingScreen | 待核 |
| A02 | tokens/LocalRecordsScreen | 待核 |
| A03 | LocalRecordsScreen | 待核 |
| A04 | LocalOriginalSearchSection | 待核 |
| A05 | MixedCaptureScreen | 待核 |
| A06 | MixedCaptureScreen | 待核 |
| A07 | SourceCitationCard | 待核 |
| A08 | TaskProposalCard | 待核 |
| A09 | ManualTasksScreen | 待核 |
| A10 | LocalAttachmentScreen | 待核 |
| A11 | OriginalReadingScreen | 待核 |
| A12 | StatusNotice/输入字段 | 待核 |
| A13 | NotebookToolsPanel | 待核 |
| A14 | CompanionEntry | 待核 |
| A15 | tokens/NotebookChrome | 待核 |
| A16 | FactRecapScreen | 待核 |
| A17 | 记录菜单/侧滑 | 待核 |
| A18 | ManualTasksScreen | 待核 |
| A19 | 来源与详情展开 | 待核 |
| A20 | Companion | 待核 |
| A21 | SourceCitationCard | 待核 |
| A22 | 候选/版本 | 待核 |
| A23 | LocalRecordsScreen | 待核 |
| A24 | Companion静态远景 | 待核 |
| A25 | Companion减少动态 | 待核 |
| A26 | DestinationIcon/AttachmentIcon | 待核 |
| A27 | tokens/OriginalReadingScreen | 待核 |

## 验收边界

- V01/V02/V03/F01–F07/A01/M01：待本轮验证，不引用旧图作为新UI证据。
- 本环境 SDK 预检失败：`ANDROID_HOME must point to an SDK with developer-accepted licenses.` 不安装、不接受协议。
- 已知旧问题回填测试 ShadowTrace OOM 停 before-fetch；未隔离，不重跑，不宣称样式修复。
- CI 起始累计13次 / 6h41m50；本轮尚未启动 CI。
- 真机、TalkBack、真实中文IME、连续动效、D08/设备/OCR/ASR/联网均未验。
- 自建集合持久模型是文档明确另列新增范围，不能伪造数量、封面或可用性。
