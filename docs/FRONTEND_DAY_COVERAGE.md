# AIyou 前端日覆盖与证据

基准：Library `libfile_6df79df13dbc8191983f8ce6edbedf07` version 1，DOCX SHA256 `1c097a6180b91878a2eb7ce46660db8e144c17446f80544b8e0c7f178e877ce8`。已全文阅读并逐张检查36张嵌图（含19原创图板）；实际页面证据独立记录，文档图不计实测。

文档 main：`3d9f16bbc153dc00dd18ad5c42cf0d233d7e4350`；仅1个DOCX，远端读回hash一致。开发基线 `385463c5b168b0c66721a998e0c4b9f0b03c38f4`，包含全部51文件历史兼容链；不使用诊断tip。

## 页面 / 组件 / 状态合同

| 页面组 | 实施入口（app/src/main/java/com/t1doo/aiyou/） | 全量合同 | 状态与证据 |
|---|---|---|---|
| 首次使用 | ui/AIyouScreens.kt | 离线入口、连接、按需权限 | 沿既有离线入口；共享语义样式已接入，未做首次安装设备验收 |
| 此刻 | records/MixedCaptureScreen.kt | 轻输入、焦点态、原件、草稿、Undo、部分失败/UNKNOWN | 已改底部单实例输入/细记录行/高级面板；控制器持有光标/中文组合输入/有界Undo；主机真实controller与editor定向测试通过，真实IME未验 |
| 回忆 | records/LocalRecordsScreen.kt | 连续列表、网格、分类查询、活动条件和锚点 | 普通时间线双proof缩略、四类轻标签、独立筛选面与单行侧滑已接入；SQL/读取控制器定向测试通过，新接线尚未原生像素复验 |
| 主动回顾 | records/FactRecapScreen.kt | 主动日/周、证据不足 | 保留主动日/周及不足状态，沿共享样式；未逐状态跑本轮回归 |
| 原记录 | records/OriginalReadingScreen.kt; records/LocalAttachmentScreen.kt | 原件优先、源版本、复制、原音 | 保留连续正文/原件/复制及源版本；共享样式已接入，原件全部场景未逐验 |
| 编辑版本 | records/OriginalEditScreen.kt; records/OriginalHistoryScreen.kt | 版本只读、草稿、派生纠正 | 既有原文/派生版本与草稿接线完整保留；共享字号/色彩，未逐状态跑本轮回归 |
| 问一问 | records/LocalEvidenceAnswerSection.kt; records/SelectedQuestionConsentSection.kt | 范围确认、停止、来源失效、无INTERNET | 细来源样式已接入；无INTERNET已核APK；未运行真实AI/联网 |
| 两类历史 | records/SavedQuestionsContent.kt; records/ChatHistoryContent.kt | 仅填问题、同UUID重试、历史来源重核 | 已改细列表/时间/核对状态；保留同UUID和失效隐藏，旧回填OOM未重跑 |
| 候选 | records/LocalCandidateDialog.kt; records/SourceMemoryInboxDialog.kt | 修改/拒绝/确认独立 | 共享平面建议行；确认/拒绝/修订沿原controller；未逐状态跑本轮回归 |
| 正式待办 | records/ManualTasksScreen.kt | 分组、完成/撤销、详情、延后、提醒实际状态 | 已改复选/详情分离与连续行；分组/提醒摘要在cf54原生Preview；新增已提交完成的独立REOPEN协议及会话撤销，主机SQL/回执/备份测试通过 |
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
| A02 | tokens/LocalRecordsScreen | 暖桃/强封面已退出；普通列表当前版双proof缩略已接入，主机测试通过，原生未复验 |
| A03 | LocalRecordsScreen | 沿真实收藏/标签/月查询；自建集合模型未新增 |
| A04 | LocalOriginalSearchSection | 查询与轻分类保留；复杂日期/人物/标签进独立筛选面，结果页显示已选摘要；原状态/Undo保留，原生未验 |
| A05 | MixedCaptureScreen | 文字入口/轻输入已改；CaptureEditorSession按控制器/草稿持有范围/组合状态/Undo，主机测试通过 |
| A06 | MixedCaptureScreen | 焦点展开与同意图失败重试定向通过；UNKNOWN全状态未验 |
| A07 | SourceCitationCard | 细引用线已改，来源版本接线保留；全往返未验 |
| A08 | TaskProposalCard | 共享平面建议行已改，确认协议保留；未逐验 |
| A09 | ManualTasksScreen | 连续任务/独立复选与详情；完成成功后产生会话撤销入口，独立REOPEN CAS/回执/备份通过 |
| A10 | LocalAttachmentScreen | 原音/派生原协议保留；未验设备播放/中断 |
| A11 | OriginalReadingScreen | 原文/附件在前沿既有实现；未逐验 |
| A12 | StatusNotice/输入字段 | 共享局部状态与字号已改，异常状态全量未验 |
| A13 | NotebookToolsPanel | 仅必要浮层保留边界；焦点/手势未逐验 |
| A14 | CompanionEntry | 48dp小入口与单次反馈沿原协议，未验设备 |
| A15 | tokens/NotebookChrome | 浅色/单蓝动作/纯底已接入，静态对比5项通过 |
| A16 | FactRecapScreen | 系统字体/主动回顾沿现有实现；无新手写字体 |
| A17 | 记录菜单/侧滑 | RecordRowActions横向揭示112dp侧边带；普通操作按钮等价；滚动/返回/owner变化收起，设备手势未验 |
| A18 | ManualTasksScreen | 真实已提交状态驱动单次复选反馈；撤销REOPEN已实现并主机验证，设备节奏未验 |
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

## 尚未完成的验收，不能称全文已实现

- 本轮新普通列表缩略、音频轻标签、侧滑菜单、草稿光标/Undo及待办撤销已有真实源码与主机协议验证；Android编译/lint/同视口新截图仍未跑，cf54通过不能覆盖这些后续diff。
- 全页面逐状态交互、真实中文IME、320dp最大字号、TalkBack、真实键盘、原生焦点/手势、连续动效仍需实际验证。
- 自建集合持久模型为文档另列新增范围，未伪造模型、计数或可用性。
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
| 记录行与缩略 | Components.kt、RecordThumbnail.kt、LocalRecordsScreen.kt | 原文摘录、日期、当前版图片/坏图、列表锚点 | 原生静态Preview；图片读取复用原认证路径，普通列表双proof缩略已补且主机验证，新原生像素未验 |
| 搜索字段 | LocalOriginalSearchSection.kt | query与活动条件、索引错误、Undo、展开/关闭 | caller状态/owner未改；真实IME和滚动回归未验 |
| 引用 | SourceCitationCard、ChatHistoryContent.kt | 编号/日期/版本/短摘录/回跳、失效隐藏 | 呈现与旧协议保留，实际AI未执行 |
| 候选与正式任务 | TaskProposalCard、ManualTasksScreen.kt | 确认/拒绝/修改独立；勾选与详情两个48dp目标 | cf54静态Preview；本轮独立撤销/回执/来源/备份主机验证通过 |
| 提醒元信息 | TaskReminderDialog.kt | 精确/大约、通知拒绝、调度失败、结果未知 | 短摘要保留真实语义；完整值在详情；未设备验收 |
| 设置字段与偏好 | SettingsScreen.kt、UiPreferences.kt | 标签/帮助/错误、隐藏密钥、读取失败、ACK未知 | v1–v3兼容与v4往返11项PASS；不改Room或发送范围 |
| 普通内容/局部错误 | AIyouPanel.kt、StatusNotice、CaptureFeedback | 读/空/忙/失败/UNKNOWN分别表达 | 纯底与细分隔，真实状态来源不变；无虚构百分比 |
| 菜单与浮层 | 既有Dialogs/NotebookToolsPanel | 取消/返回/确认/焦点/忙对象 | 使用平台边界；完整焦点与TalkBack未验 |
| 底导航 | NotebookChrome.kt | 全宽、中文标签、selected语义、48dp触区 | 取消胶囊选中底、按压缩放；静态Preview |
| 独立陪伴 | Companion.kt、CompanionInteraction.kt | 5既有身份、一次回应、离页/后台/减少动态、关闭 | 原创静态远景、原PNG未改；不读取记录；真实生命周期未验 |

主题只采用本次批准的完整浅色语义体系；未增加暗色/极光/纸纹分支。动效以原位真实状态和既有有限回应为准，140–200ms拟定节奏未冒充设备实测。

第16次 `41414387198` / `e888efe62184a483666b31f998ced3b34aefd22f`：APK/领域编译成功，lint在ManualTasksScreen.kt:130报告SuspiciousIndentation，未进入后续Preview。已移除外层if内重复if并校正缩进，不关lint、不加baseline。job7m41s，累计16次 / 7h04m53。源审另补回列表外“有新内容”提示，避免滚动时提示不可见；保留主动点按回顶。下一轮仅核这些实际修正。

## 最新通过结果与父冻结（本地记录，未再上传）

最新正式验证：41415468944，执行SHA cf54f3d9b4781a2fb4402ef86a4aeee8ee8bd482。metadata/APK/domain/lint、全部schema guards与APK无INTERNET检查通过；XML为78领域+11偏好+1实际此刻controller测试，另6项原生Preview生成，零失败/错误/跳过。基线另生成6幅，共12幅PNG逐张实看，同视口尺寸和各自hash已核。普通360dp与320dp/2倍字号，均为虚构数据Compose Preview，非设备。新版已无重复页标题，三主入口呈连续内容与底部轻输入；未因此把未覆盖状态标为完成。

APK本地：/workspace/aiyou-ci-evidence/latest/unpacked/app/build/outputs/apk/debug/app-debug.apk；SHA256 cc47499cc35f518fa26959ab7cb06e07bbe9a3680953c43d89a74f4f7e359b94。
工件13127851856，26,245,410 B，ZIP SHA256 422a25d7285d8bb7a6a73e99c85fa0ccee5f647732c344f8869703c9b5b22c6b，7天，2026-11-09T08:57:47Z到期。最新工件范围：前后PNG、app reports/test-results、domain test XML、debug APK、生成schema核对记录。已完成的本地逐像素/校验报告在 /workspace/aiyou-ci-evidence/latest/verified-local-review.json。

本轮4次job：4m28s + 10m54s + 7m41s + 11m40s = 34m43s；从原13次/6h41m50累加为17次/7h16m33（官方job起止UTC相减，非归零新预算）。每job配置30分钟超时，有界子命令，无旧OOM组或设备/联网。

| run | outcome | job | artifact ID | bytes | 保留 |
|---|---|---|---|---|---|
| 41411293236 | compile FAIL，高度DSL作用域已修 | 4m28s | 13125338370 | 13,463 | 7天 |
| 41412104521 | all steps PASS，首轮像素后继续改 | 10m54s | 13126840696 | 26,318,472 | 7天 |
| 41414387198 | APK成功/lint FAIL，缩进已修，后续跳过 | 7m41s | 13127800714 | 24,372,549 | 7天 |
| 41415468944 | all steps PASS | 11m40s | 13127851856 | 26,245,410 | 7天 |

四工件合计76,949,894 B。执行依据为一般有界免费CI委托和公开仓库/标准ubuntu-24.04；未找到四轮存储上限、7天保留期的单独批准，不能从旧单方法8MiB/1天推定扩展许可。公开标准runner计算的免费政策不证明账户共享artifact存储账单为零；未核账户/Packages共享配额或实际费用。父冻结在第四轮完成/上传后收到，之后仅做正式API只读核账、本地PNG/ZIP/XML校验；未新增CI/上传、未擅删既有工件或改其保留期。后续CI与工件上传待父核定预算范围。

## 冻结期间继续完成的源码与独立审查

- 普通时间线：CurrentImageGridController继续使用原记录IO队列，绑定当前搜索对象、entries引用、owner和epoch，最多12个256px缩略。当前图片集合proof和原搜索来源proof在发布租约内双重复核；无图片与坏图/未知分开，坏首图不换其他图。失败文字移到可换行的记录行，64dp图片槽不裁字。
- 音频分类：LocalSearchRequest/OriginalSearchInput新增onlyAudio，用当前revision的audio/wav附件ledger构造与完整重核proof；不读转写、历史版本不计。全部/收藏/图片/音频为轻标签，复杂日期/人物/标签条件仍保留。
- 侧滑：RecordRowActions仅横向揭示一行操作区；普通操作按钮暴露同一组收藏、编辑、移入回收站。删除进入既有对象确认；滚动、返回、来源变化退休展开状态。关闭恢复焦点，滚动和跨行展开不抢焦点；Main再次核导航/原搜索对象/entries与owner。
- 草稿：CaptureEditorSession为内存专用，UTF-16范围、中文composition和40帧/524288 code-unit历史由控制器持有。普通持久ACK不重置；新草稿/退休清除历史及引用，迟到回调以确切草稿ID拒绝。中文IME先清composition后提交文字也保持单次Undo。菜单直达附件状态并回焦；减少动态时同内容使用静态Dialog。
- 待办撤销：只有已提交并读回的COMPLETED产生短时会话入口；独立ManualTaskReopen/REOPEN按确切版本CAS，原STATUS依然拒OPEN。保留日期/来源，提醒保持REVOKED及清理命令；同UUID与请求hash认证重放，系统提醒推进不丢回执。离页、刷新、完整恢复和退休清除入口，已接纳未知请求保留原意图供核对。
- 兼容：手工任务加密cell语义格式7用于REOPEN及其后续系统回执；读旧1–6格式保留，Room结构与完整备份外层版本均未改。格式7可跨密钥完整备份恢复，RESTORE会退休撤销回执并保持提醒撤销。旧客户端不认识格式7会拒绝，不能称旧客户端向前兼容。
- 一名独立只读审查员检查来源/手势与替代入口/草稿/撤销生命周期，发现5处P2及最后轻标签接线的1处API参数遗漏，已逐项修复并读回复核；最终未见剩余P1或确定编译错误。审查未运行构建或上传，不充作实测。

本地主机命令（JDK17，仓库正规proxy/trust配置，不初始化SDK）：
`python3 scripts/jvm-gradle.py -p checks/ai-protocol :remote-text:test --tests '*CaptureEditorSessionTest' --tests '*MixedCaptureControllerTest' --tests '*AudioFilterSqlTest' --tests '*CurrentImageGridControllerTest' --tests '*TaskUserReceiptSqlTest' --tests '*ManualTaskViewModelTest' --tests '*SourceTaskSqlTest' --tests '*TaskUndoFullBackupTest' --tests '*LocalSearchSqlTest' --offline --max-workers=2`

最终XML：9套件81项，0失败/错误/跳过。范围为4编辑会话、8实际图文控制器、15真实缩略控制器+SQL proof、10用户回执、13实际待办VM、9来源待办、20本地查找SQL、1音频筛选、1跨密钥完整备份。SQLite为主机driver，非Android SQLCipher/Room/设备。先前缓存/直连依赖失败没有算通过；改用仓库既有jvm-gradle.py代理配置后补齐锁定依赖，测试初轮发现两个fixture/计数假设错误，修正后最终通过；未修改原断言以掩盖产品失败。Kotlin PSI语法解析26个变更文件0错误，仅语法而非Android类型检查。token契约5项、候选证据收集上限2项Python测试通过。

## 待恢复批准的精确候选上限（未执行）

`.github/workflows/frontend-day.yml`仅workflow_dispatch，无push/PR触发；execute和upload输入默认false，job还由字面量false硬冻结。恢复需审阅源码并精确指定单次SHA，run_attempt必须1，只准公开T1doo/AIyou标准ubuntu-24.04。候选至多1次job、timeout20分钟；失败即停，不自动重跑，不跑旧OOM组，不生成重复基线。

默认不上传。若另获存储批准，候选仅6张当前虚构Preview与指定测试XML，最多32文件（含scope manifest）/6MiB原输入，内ZIP不超过7MiB，外工件提议上限8MiB、1天；禁止APK、重复before、完整报告树、缓存及本地任意日志。收集失败删除的仅是本次本地临时ZIP，不碰GitHub已存在工件。此为提议，不是已恢复批准或存储零费保证。

免费依据仅限GitHub官方所述公开仓库标准runner计算：[Actions billing](https://docs.github.com/en/actions/concepts/billing-and-usage)、[shared storage accounting](https://docs.github.com/en/billing/concepts/product-billing/github-actions)。账户/Packages共享存储配额与实际费用尚未核实，需父确认可验证的剩余额度/无新增费用依据后才能恢复候选执行及独立上传。历史17次/7h16m33、四工件76,949,894B/7天完整保留，无删除或改期。

## 继续补齐的三项文档源码缺口（仍未运行新CI）

1. 文档第4节：复杂日期/人物/标签移入独立全屏筛选面，结果页保留已选条件摘要。query/from/through实际TextFieldState与Undo仍由原owner持有，关闭不清输入、不强制展开；错误留在结果页，面板提供原重试入口，关闭回到触发按钮。
2. 文档第8节：当前回答复制先inspect及checkReceipt，历史回答先actor内current(view)复核，再生成单次内存token；平台操作在Main且离开SQL/所有monitor后执行。复用敏感剪贴板标记，仅消费者实际成功后显示成功，失败明确可重试。当前结果关闭/换receipt/owner后退休回调；历史换view/来源/owner后拒绝token。回答与引用移除绕过此复核的原生SelectionContainer，问题选择保留。格式带待核对声明、编号与对应原文版本/摘录，未新增请求/发送权限或持久复制模型。
3. 文档第20节：侧滑从向下展开按钮改为112dp真实右侧带；同一时刻一行，滑动本身不写入。普通“操作”按钮打开同一侧带，所有动作≥48dp；大字时侧带可滚动。滚动/返回/owner改变收起，删除仍进入原对象确认。

主机实际源定向XML更新为11套件105项，0失败/错误/跳过；包含先前81项和两类回答controller整套24项，新增复制用例涵盖成功/失败反馈、单次token、UI退休、owner变化和来源撤回。协议wire/剪贴板consumer为受控合成实现，没有运行真实服务或系统剪贴板。记录见docs/evidence/frontend-day-host-copy-20261102.json。Kotlin PSI语法检查9个变更文件0错误，不能替代Android类型检查。独立只读复审未见确定P1/P2，未构建/执行CI。实际workflow仍是硬冻结版本，原17次/7h16m33账与四工件完全不动；新版截图未生成，不用cf54旧截图证明新源码。
