> 历史审阅快照：下文未执行状态是准备时状态。父随后批准了精确补丁，run8/attempt1（41455962724）已执行。workflow SHA 00d36fdb3ffeaa93fb58bb9a819d8390e85bb1e6，源码e33ff67；270秒后DEX转换失败，后续lint/host/app/XML未执行。准入已消费，不能重跑；结果见 ../evidence/frontend-day-run8-20261102.json。原patch/candidate字节与哈希保留。

# 音频Compose状态修复：run8未执行方案

本地/远端开发分支源码 `e33ff67afaedabeb34f8aa0f1e298b702e250a38` 已正常push，main仍 `3d9f16bbc153dc00dd18ad5c42cf0d233d7e4350`。唯一产品差异是OriginalAudioScreen：实际StateFlow.collectAsState订阅、key(controller)身份与结构相等derivedState；未改投影内容、控制器或提醒协议。独立只读审查通过；本地离线实际重执行3套件34项，PSI语法1文件0错，均不能代替新Android lint/Compose验证。run7失败及实际5项Python、229未取得和累计账已随源码归档。

## 精确准入及最小diff

- 固定checkout/源码断言SHA：`e33ff67afaedabeb34f8aa0f1e298b702e250a38`。
- 唯一消息：`[frontend-once-no-upload-audio-state] e33ff67afaedabeb34f8aa0f1e298b702e250a38`。
- 仅指定公开仓库T1doo/AIyou、push、frontend/day-20261102、workflow路径、run_number 8、attempt 1。执行workflow提交SHA与实际编译源码SHA分别打印并核对。
- actual workflow仍已消费的run7字节，SHA256 `93f7811110d4d0071aefecdf65293d3f988c8236fa33ebd5517cad6c8e38525d`；本地candidate未应用，未提交触发消息，未触发run8。
- candidate SHA256 `8ee0525a76a9e1b2868c4d66fc82032c6eb3f65ce5c06ece011de97475283a45`；patch SHA256 `475c82a0a73c22f7a3a39f645992121aa9e6c06fe50073a8d77e4b3efdc50f6d`。
- 4处hunk：纠正已应用文件的历史“位于workflow外”注释；run7改8及精确消息；checkout固定新SHA；首步断言同SHA。没有其它执行、权限、测试数或超时变化。完整diff在同名前缀.patch，candidate在.candidate.yml，静态核验在.review.json。

## 只读当前状态、计算依据及上限

正规GitHub REST核public/visibility public，开放PR0；queued/in_progress/waiting/pending/requested各0。workflow404271199最新仍run7/attempt1，41452461459，completed/failure。源码提交关联run0，原生源码push没有触发其它工作。执行前必须重新核这些动态条件、源码与补丁哈希、远端HEAD；不符则停，不能改run号或绕过准入。

公开仓库标准ubuntu-24.04单job，沿原固定checkout/setup-java SHA与原依赖来源；contents:read、无新权限/凭据。GitHub官方公开标准runner计算免费依据：[Actions billing](https://docs.github.com/en/actions/concepts/billing-and-usage)、[标准runner规格](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)。不使用larger/self-hosted/付费runner。

原累计20次/27345秒（7h35m45）不归零。候选仅1个job，全局20分钟，包含setup与依赖；若获批后运行，最多21次/28545秒（7h55m45），实际结束按API job起止核账，不能预写预计值。内部构建720秒、host180秒、app120秒，TERM后10秒KILL。失败、超时或计数不符即停，不自动重试。当前没有run8执行授权。

## 验证内容及真实计数要求

1. 同已批准路线复核public、精确source、继承385463c祖先、已有SDK许可、设计token及5项Python契约、schema基线；不安装SDK或接受新协议。
2. `scripts/gradle.sh --no-build-cache --no-configuration-cache --no-scan :app:checkDebugAarMetadata :app:assembleDebug :core:domain:test :app:lintDebug`。随后schema复核及inspect-record-ai-permissions.py检查编译APK无INTERNET。必须修复真实lint问题，禁止suppress、baseline或关闭检查。
3. 原14个host selector不变：CaptureEditorSessionTest、MixedCaptureControllerTest、AudioFilterSqlTest、CurrentImageGridControllerTest、TaskUserReceiptSqlTest、ManualTaskViewModelTest、SourceTaskSqlTest、TaskUndoFullBackupTest、LocalSearchSqlTest、SelectedQuestionConsentControllerTest、ChatHistoryControllerTest、OriginalAudioPresentationTest、OriginalAudioControllerTest、TaskReminderControllerTest。预期139项/14套件，SQLite/JCA及合成端口，不代表设备音频/TalkBack。
4. Android定向仍UiPreferencesTest及MixedCapturePresentationTest.frontendDayFocusKeepsDraftAndFailedSaveRetriesTheSameRecord，预期12项/2套件；domain预期78项/12套件。总229/28只是计划，必须从当轮真实XML读回且0失败/错误/跳过。缺失/跳过/NO-SOURCE不能当通过。

## 无上传范围与停止条件

所有Gradle入口禁build/configuration cache与scan；setup-java没有cache输入，无artifact/cache action、ZIP收集、APK/package/Release发布、deploy或额外workflow联动。GRADLE_USER_HOME和ANDROID_USER_HOME仍由RUNNER_TEMP写GITHUB_ENV，APK/XML/报告仅临时runner，普通运行日志和状态由GitHub持久保存。平台启动Cache mode: write仅为元数据；没有缓存动作/日志不能表述为关闭了平台能力，也不据此推断实际已上传。结束后核正式artifact API0项和全部steps/log，无实际artifact/cache/package上传。

已有76,949,894B/7天工件完整保留，未删/改期；不宣称其存储或账户费用为零。未恢复原最小8MiB/1天上传提案。没有Preview/基线/新截图、emulator/设备/IME/TalkBack/D08、旧ShadowTrace OOM、OCR/ASR/模型或真实AI组。不用编译回归认证设备或像素。

父独审批准前只保留本地补丁。批准后执行前任一动态条件、许可、工具链不符都停；任何阶段失败或XML数量不符同样停止，不自动下一轮，不上传失败报告，不调整断言或超时。
