> 历史审阅快照：下文“未执行”描述准备时状态。父随后批准了精确补丁；已执行run7/attempt1（41452461459），执行SHA a828cf5554f347b4a6032b17de08521638f0ac5b，源码ae6669c，478秒后因lint失败。本准入已消费，不能复用；实际结果见 ../evidence/frontend-day-run7-20261102.json。原patch及candidate字节与哈希保留。

# ae6669c 单次无工件验证提案（未应用、未触发）

固定源码：`ae6669c633d7cbc255e95fb4da6b7ed8fb245e86`。本次仅准备审阅文件；真实workflow仍保留已消费的run6准入，未改成7，未提交/推送触发提交，没有运行CI。本地候选不在`.github/workflows`执行路径中。

## 只读核验与预算

- 当前本地/远端开发分支均ae6669c，main仍3d9f16b；开始准备时工作树干净，产品代码未改。
- 正式REST核仓库`private=false`、`visibility=public`、默认main；开放PR为0。queued/in_progress/waiting/pending/requested分别计数均0。前端workflow ID404271199的最新记录仍run6/attempt1，41427828677、328ca761…、success。
- 本任务继承账是19次/26867秒（7h27m47），其中run5为零job配置失败；不清零、不把本地主机34项耗时计入GitHub job。仓库API另有390条全历史记录，这是更宽口径，不用它改写本次已确认起账。
- 公开标准`ubuntu-24.04`，x64、4 CPU、16 GB RAM、14 GB临时SSD，计算免费依据：[GitHub Actions billing](https://docs.github.com/en/actions/concepts/billing-and-usage)、[public standard runner规格](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)。不是larger/self-hosted/付费runner，不引入新凭据、权限或协议接受。
- 候选只允许1个job、20分钟全局取消上限，含checkout/JDK/依赖获取。子命令仍是构建/lint 720秒、host180秒、app定向120秒，TERM后10秒KILL；不加矩阵、自动重试或无限超时。若获批且用满配置上限，计划账20次/7h47m47；实际结束后仍按API job起止补账，不能预写预计值。run6历史耗时11m14仅供评估，不保证冷环境赶完。

## 最小可审查差异

- 候选：`20261102-detail-review-UNEXECUTED.candidate.yml`
- 未应用补丁：`20261102-detail-review-UNEXECUTED.patch`
- 静态核验记录：`20261102-detail-review-UNEXECUTED.review.json`
- candidate SHA256：`93f7811110d4d0071aefecdf65293d3f988c8236fa33ebd5517cad6c8e38525d`
- patch SHA256：`53d342db70dfbdb508192ace598fcf2896748df0324c6340164dddd0ecc47c67`

相对已成功run6的实际workflow，仅五类语义差异：准入编号6→7及新精确消息；checkout ref改ae完整SHA；首步源码断言改同SHA；host追加3个不重叠selector；最终host XML预期105/11→139/14。总共5个diff hunk。runner、20min、权限、原action pins、SDK/token/schema/祖先检查、Android构建与权限检查、app12项、步骤数量、环境位置/临时目录、缓存关闭、无上传均不变。

拟定唯一触发消息：
`[frontend-once-no-upload-detail-review] ae6669c633d7cbc255e95fb4da6b7ed8fb245e86`

未来仅在父另行核准后，正常开发分支的一次workflow提交使用这条精确消息。仍只匹配`frontend/day-20261102`、`.github/workflows/frontend-day.yml`路径、指定公开仓库、`run_number==7`、`run_attempt==1`；不复用run6、不接受其它分支/消息或重跑。首步分别打印workflow执行SHA和真正checkout的ae源码SHA，并再次核public。当前其它workflow均匹配各自codex/dev/device/diagnostic分支，无workflow_run链；没有PR时不关联android.yml的pull_request。执行前若出现PR/新运行/新准入编号/源变化，则先停而不静默调整。

## 与旧195项的关系

| 范围 | 原run6 | 新候选预期 | 验证性质 |
|---|---:|---:|---|
| domain | 12套件/78 | 12套件/78 | 实际领域JVM断言 |
| host原11类 | 11套件/105 | 11套件/105 | 原controller/SQL/JCA回归，全部保留 |
| 音频呈现 | 不含 | 1套件/3 | 稳定播报与整秒呈现性质；不是真TalkBack |
| 原音控制器 | 不含 | 1套件/14 | 真实控制器/认证与释放，合成音频端口 |
| 提醒控制器 | 不含 | 1套件/17 | 实际SQL/回执，含排队和commit期间关闭、原请求重试 |
| Android定向 | 2套件/12 | 2套件/12 | UiPreferencesTest 11＋实际此刻focus/失败同意图重试1 |
| 总计 | 25套件/195 | 28套件/229 | 新旧不重叠；229目前只是预计 |

host的14个精确选择器保留原11类，追加：`*OriginalAudioPresentationTest`、`*OriginalAudioControllerTest`、`*TaskReminderControllerTest`。原105＋新34＝139；总计78＋139＋12＝229。最后一步须读取真实XML，确认28套件及229项且失败/错误/跳过均0；缺XML/NO-SOURCE不能算通过。另有原5项Python token契约，未混入JUnit总数。

真实编译部分仍显式执行`:app:checkDebugAarMetadata :app:assembleDebug :core:domain:test :app:lintDebug`，随后复核schema及编译APK无INTERNET。它会覆盖ae新增UI/Flow/关闭按钮的Android类型编译；host测试通过本身不能替代这部分。APK只在临时runner用于检查，不发布。

## 无新增artifact/cache/package持久存储路径

只沿原有checkout/setup-java两个固定SHA action；setup-java没有cache输入。每条Gradle入口保留`--no-build-cache --no-configuration-cache --no-scan`；SDK自动下载禁用，只预检已有已接受许可的SDK，不安装或自动接受新协议。依赖仍来自原wrapper/JDK及已锁定Maven/Google/Plugin Portal来源，不引入新工具或模型数据来源。

首个运行step用RUNNER_TEMP写GITHUB_ENV，后续Gradle只使用临时runner目录。候选没有artifact/cache action、ZIP收集、上传命令、package/Release/deploy、OIDC或新权限；所有APK/XML/报告/依赖缓存只留临时runner。普通GitHub运行日志和状态仍由平台保留，用于读取实际结果，不能说“没有任何持久化”。原四工件76,949,894B/7天不动，也不保证已有工件或整个账户存储零费。

不运行Preview/重复基线、新截图、D08、emulator/设备、真实IME/TalkBack、OCR/ASR、模型下载或真实AI；不纳入旧问题回填ShadowTrace OOM组。229项不能被称为截图或设备验收，也不能据此关闭旧OOM。

## 停止条件与当前状态

父核准前不应用真实workflow、不提交精确消息、不触发run7。将来执行前再次核公开、无PR/在途、latest仍6、ae源码及补丁hash不变、其它触发规则不变；条件不符就停，不重置编号或自行换源。SDK/许可/工具链不符、任一命令失败、超时、实际XML缺失/数目不符/有失败错误跳过即失败停止；不自动再跑，不上传失败产物或弱化断言。

本次已做的仅是本地YAML/context可用性检查、嵌入Python compile语法检查、selector无重复/计数核对、与原run6的结构比较及git apply --check。它们不代表GitHub服务端已接受或ae已Android编译。真实workflow SHA256仍`596e6a974151dfeb830ffa05b734d51af8d3d43b34a5c362ad92d78e3801656b`，原累计19次不变。
