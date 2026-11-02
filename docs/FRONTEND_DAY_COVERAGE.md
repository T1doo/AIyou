# AIyou 前端日覆盖与证据

基准：Library `libfile_6df79df13dbc8191983f8ce6edbedf07` version 1，DOCX SHA256 `1c097a6180b91878a2eb7ce46660db8e144c17446f80544b8e0c7f178e877ce8`。已全文阅读并逐张检查36张嵌图（含19原创图板）；实际页面证据独立记录，文档图不计实测。

文档 main：`3d9f16bbc153dc00dd18ad5c42cf0d233d7e4350`；仅1个DOCX，远端读回hash一致。开发基线 `385463c5b168b0c66721a998e0c4b9f0b03c38f4`，包含全部51文件历史兼容链；不使用诊断tip。

## 页面 / 组件 / 状态合同

| 页面组 | 实施入口（app/src/main/java/com/t1doo/aiyou/） | 全量合同 | 状态与证据 |
|---|---|---|---|
| 首次使用 | ui/AIyouScreens.kt | 离线入口、连接、按需权限 | 沿既有离线入口；共享语义样式已接入，未做首次安装设备验收 |
| 此刻 | records/MixedCaptureScreen.kt | 轻输入、焦点态、原件、草稿、Undo、部分失败/UNKNOWN | 已改底部单实例输入/细记录行/高级面板；1项真实controller定向测试通过；跨模式光标/Undo未闭环 |
| 回忆 | records/LocalRecordsScreen.kt | 连续列表、网格、分类查询、活动条件和锚点 | 细行/日期分组/图片筛选缩略已接入；普通时间线缩略、音频快捷筛选未完成；默认浏览Preview待最新复验 |
| 主动回顾 | records/FactRecapScreen.kt | 主动日/周、证据不足 | 保留主动日/周及不足状态，沿共享样式；未逐状态跑本轮回归 |
| 原记录 | records/OriginalReadingScreen.kt; records/LocalAttachmentScreen.kt | 原件优先、源版本、复制、原音 | 保留连续正文/原件/复制及源版本；共享样式已接入，原件全部场景未逐验 |
| 编辑版本 | records/OriginalEditScreen.kt; records/OriginalHistoryScreen.kt | 版本只读、草稿、派生纠正 | 既有原文/派生版本与草稿接线完整保留；共享字号/色彩，未逐状态跑本轮回归 |
| 问一问 | records/LocalEvidenceAnswerSection.kt; records/SelectedQuestionConsentSection.kt | 范围确认、停止、来源失效、无INTERNET | 细来源样式已接入；无INTERNET已核APK；未运行真实AI/联网 |
| 两类历史 | records/SavedQuestionsContent.kt; records/ChatHistoryContent.kt | 仅填问题、同UUID重试、历史来源重核 | 已改细列表/时间/核对状态；保留同UUID和失效隐藏，旧回填OOM未重跑 |
| 候选 | records/LocalCandidateDialog.kt; records/SourceMemoryInboxDialog.kt | 修改/拒绝/确认独立 | 共享平面建议行；确认/拒绝/修订沿原controller；未逐状态跑本轮回归 |
| 正式待办 | records/ManualTasksScreen.kt | 分组、完成/撤销、详情、延后、提醒实际状态 | 已改复选/详情分离与连续行；分组/提醒摘要在真实Preview；完成撤销缺协议支持 |
| 设置连接 | ui/SettingsScreen.kt; ui/ModelConnectionSettings.kt | 字段标签、连接资格、密钥隐藏 | 去大卡分组，保留字段标签/资格/隐藏密钥；偏好11项通过，未逐字段验收 |
| 隐私与锁 | ui/AppAccessScreen.kt; records/RecordAiSection.kt | 不扩大范围、应用锁 | 所有owner/准入/范围逻辑未改；无新增权限，设备锁验收未运行 |
| 存储与用量 | ui/StorageUsageSection.kt | 原件/派生/缓存、未知用量 | 保留分类/清理与未知边界，沿共享样式；未逐状态跑本轮回归 |
| 数据可带走 | records/OriginalExportScreen.kt; records/RecordPackageScreen.kt; records/FullBackupContent.kt | 严格空库、取消/未知、实际结果 | 严格空库与取消/UNKNOWN协议保留，未跑D08/恢复设备演练 |
| 删除回收 | records/RecycleBinScreen.kt | 对象确认、失败/未知、不冒成功 | 保留对象确认/恢复/永久删除及传播；未跑本轮设备删除验收 |
| 独立陪伴 | ui/Companion.kt; ui/UiPreferences.kt | 既有5角色、独立二级页、静态背景、减少动态/离页停止 | 已改独立全屏二级场景/背景开关/v4兼容；5原PNG实看，生命周期设备验收未运行 |

## 27个迁移点

| 点 | 实施位置 | 状态 |
|---|---|---|
| A01 | AIyouPanel/OriginalReadingScreen | 平面阅读面已接入；长原件未逐验 |
| A02 | tokens/LocalRecordsScreen | 暖桃/强封面已退出；普通列表缩略待补 |
| A03 | LocalRecordsScreen | 沿真实收藏/标签/月查询；自建集合模型未新增 |
| A04 | LocalOriginalSearchSection | 查询与筛选控件去卡；活动状态/锚点逻辑保留，未逐验 |
| A05 | MixedCaptureScreen | 文字入口/轻输入已改；跨模式光标/Undo待补 |
| A06 | MixedCaptureScreen | 焦点展开与同意图失败重试定向通过；UNKNOWN全状态未验 |
| A07 | SourceCitationCard | 细引用线已改，来源版本接线保留；全往返未验 |
| A08 | TaskProposalCard | 共享平面建议行已改，确认协议保留；未逐验 |
| A09 | ManualTasksScreen | 连续任务/独立复选与详情已改；完成撤销待协议 |
| A10 | LocalAttachmentScreen | 原音/派生原协议保留；未验设备播放/中断 |
| A11 | OriginalReadingScreen | 原文/附件在前沿既有实现；未逐验 |
| A12 | StatusNotice/输入字段 | 共享局部状态与字号已改，异常状态全量未验 |
| A13 | NotebookToolsPanel | 仅必要浮层保留边界；焦点/手势未逐验 |
| A14 | CompanionEntry | 48dp小入口与单次反馈沿原协议，未验设备 |
| A15 | tokens/NotebookChrome | 浅色/单蓝动作/纯底已接入，静态对比5项通过 |
| A16 | FactRecapScreen | 系统字体/主动回顾沿现有实现；无新手写字体 |
| A17 | 记录菜单/侧滑 | 未实现记录侧滑窄操作区，普通详情入口保留 |
| A18 | ManualTasksScreen | 真实状态驱动勾选；自定义单次勾/撤销/设备节奏未完成 |
| A19 | 来源与详情展开 | 待办原位详情已接入；局部展开节奏未设备验收 |
| A20 | Companion | 既有5角色实看复用，独立静态场景已改；生命周期实测未验 |
| A21 | SourceCitationCard | 引用入口/来源版本沿原协议，细行已改；未逐验 |
| A22 | 候选/版本 | 用户修订/旧来源状态沿原协议，未逐验 |
| A23 | LocalRecordsScreen | 无叠纸/封面墙，真实查询入口保留；自建集合未新增 |
| A24 | Companion静态远景 | 原创静态Canvas仅陪伴二级页，未引入光团/纹理 |
| A25 | Companion减少动态 | 减少动态/后台停止沿原interaction，不启动循环背景；未设备验收 |
| A26 | DestinationIcon/AttachmentIcon | 既有线性Canvas图标22dp/触区48dp；未TalkBack逐控件验收 |
| A27 | tokens/OriginalReadingScreen | 正文16/26、标题24/32、元信息13/20；长文全量未验 |

## 验收边界

- V01/V02/V03/F01–F07/A01/M01：待本轮验证，不引用旧图作为新UI证据。
- 本环境 SDK 预检失败：`ANDROID_HOME must point to an SDK with developer-accepted licenses.` 不安装、不接受协议。
- 已知旧问题回填测试 ShadowTrace OOM 停 before-fetch；未隔离，不重跑，不宣称样式修复。
- CI 起始累计13次 / 6h41m50。第14次 `41411293236` 实际job4m28s，累计14次 / 6h46m18；编译因Compose DSL作用域失败，已修复可用高度显式捕获。未运行下游lint/Preview，不算通过。
- 真机、TalkBack、真实中文IME、连续动效、D08/设备/OCR/ASR/联网均未验。
- 自建集合持久模型是文档明确另列新增范围，不能伪造数量、封面或可用性。

## 已落地的源码（尚待 Android 复验）

- 全局语义浅色、字号/密度与平面内容行，保留真正浮层的12–16dp层级；对比契约5/5通过。
- 此刻底部单实例轻输入，展开编辑不替换controller或字段；高级附件/策略/取消/UNKNOWN入口集中在一份面板。
- 回忆细行、日期分组，列表键表同步维护；图片筛选列表复用已认证缩略读取路径与12张内存预算，不新增请求或权限。
- 正式任务复选与详情分开，操作在对应行展开；所有原来日期、提醒、来源、取消入口保留。
- 问题收藏与回答历史分开；修改时间、历史核对状态及细来源编号，既有同UUID/失效隐藏协议不改。
- 设置分组去卡片；陪伴独立全屏二级页、原创静态远景、5个既有动作和背景开关，偏好v4兼容v1–v3，不改Room。
- 本地领域测试：12套件78项测试，无失败/错误/跳过；不充作Android/设备证据。

## 仍未完成，不能称全文已实现

- 全量普通时间线（未启用图片筛选）的真实图片缩略接线；音频分类快捷筛选。
- 记录侧滑窄操作区及菜单焦点恢复的完整验收。
- 完成后的撤销：当前正式任务协议明确拒绝把已完成任务重开，本轮未增加假按钮或改动确认协议。
- 全页面逐状态交互、草稿光标/中文组合输入/Undo/键盘、320dp最大字号、TalkBack及真实设备动效仍需实际验证。
- 麦克风录音、ASR/OCR及联网不因前端日开启；已有原音文件导入和播放继续使用原能力。

## 第15次验证与像素复审

- `41412104521` / `e7b235daa5492619636bfb951376a192a25af223`：Android metadata / APK / domain / lint / schema /无INTERNET检查全部通过。
- 实际XML：领域78项、UiPreferences11项、真实此刻controller焦点/失败重试1项，零失败/错误/跳过。原生Preview六幅生成通过；同数据同视口基线六幅也成功。
- APK SHA256 `b8933c60ff80a662910d457a87069ef810345b7ec93fbf65d66b73c6664a9630`。工件ZIP SHA256 `85d4748c2f07a0601ceb7a284c52f238290ec128442694830ea286492004f64d`。
- job10m54s；累计15次 / 6h57m12。无旧OOM组、设备、D08或联网运行。
- 实看全部12张PNG，确认此刻已移除大封面/卡片/浮动选中底纹；发现回忆/待办重复页标题与过长任务元信息，继续做像素驱动修正。
- 首轮回忆Preview冻结在Main异步查找的WAITING，而非默认BROWSE；修正fixture为直接给真实LocalRecordsScreen提供显式BROWSE快照。前后都使用同一fixture，不能把这幅图称为搜索/导航已验。

## 组件、状态、主题、动效核对

| 组件 | 实施文件 | 必须保留的状态/行为 | 本轮证据边界 |
|---|---|---|---|
| 语义样式 | design/tokens.json、AIyouTheme.kt | 浅色、系统中文字体、大字、对比 | 5项token契约PASS；全页面可访问性未签收 |
| 记录输入 | MixedCaptureScreen.kt | 读取、空、编辑、焦点、附件、提交、失败、部分失败、UNKNOWN、取消 | 焦点/失败同意图重试1项PASS；其余需逐状态验证 |
| 记录行与缩略 | Components.kt、RecordThumbnail.kt、LocalRecordsScreen.kt | 原文摘录、日期、当前版图片/坏图、列表锚点 | 原生静态Preview；图片读取复用原认证路径，普通列表缩略未补 |
| 搜索字段 | LocalOriginalSearchSection.kt | query与活动条件、索引错误、Undo、展开/关闭 | caller状态/owner未改；真实IME和滚动回归未验 |
| 引用 | SourceCitationCard、ChatHistoryContent.kt | 编号/日期/版本/短摘录/回跳、失效隐藏 | 呈现与旧协议保留，实际AI未执行 |
| 候选与正式任务 | TaskProposalCard、ManualTasksScreen.kt | 确认/拒绝/修改独立；勾选与详情两个48dp目标 | 静态Preview及领域合同；撤销协议未补 |
| 提醒元信息 | TaskReminderDialog.kt | 精确/大约、通知拒绝、调度失败、结果未知 | 短摘要保留真实语义；完整值在详情；未设备验收 |
| 设置字段与偏好 | SettingsScreen.kt、UiPreferences.kt | 标签/帮助/错误、隐藏密钥、读取失败、ACK未知 | v1–v3兼容与v4往返11项PASS；不改Room或发送范围 |
| 普通内容/局部错误 | AIyouPanel.kt、StatusNotice、CaptureFeedback | 读/空/忙/失败/UNKNOWN分别表达 | 纯底与细分隔，真实状态来源不变；无虚构百分比 |
| 菜单与浮层 | 既有Dialogs/NotebookToolsPanel | 取消/返回/确认/焦点/忙对象 | 使用平台边界；完整焦点与TalkBack未验 |
| 底导航 | NotebookChrome.kt | 全宽、中文标签、selected语义、48dp触区 | 取消胶囊选中底、按压缩放；静态Preview |
| 独立陪伴 | Companion.kt、CompanionInteraction.kt | 5既有身份、一次回应、离页/后台/减少动态、关闭 | 原创静态远景、原PNG未改；不读取记录；真实生命周期未验 |

主题只采用本次批准的完整浅色语义体系；未增加暗色/极光/纸纹分支。动效以原位真实状态和既有有限回应为准，140–200ms拟定节奏未冒充设备实测。

第16次 `41414387198` / `e888efe62184a483666b31f998ced3b34aefd22f`：APK/领域编译成功，lint在ManualTasksScreen.kt:130报告SuspiciousIndentation，未进入后续Preview。已移除外层if内重复if并校正缩进，不关lint、不加baseline。job7m41s，累计16次 / 7h04m53。源审另补回列表外“有新内容”提示，避免滚动时提示不可见；保留主动点按回顶。下一轮仅核这些实际修正。
