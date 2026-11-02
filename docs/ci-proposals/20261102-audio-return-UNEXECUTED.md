> 历史审阅快照：下文未执行状态描述准备时。父随后批准精确补丁，run9/attempt1（41458718832）已success。workflow SHA aec49fe3fad7d0b71f281cd99f01a8a63acf6837，源码2abfc61；527秒。实际domain XML12套件78项零失败/错误/跳过，Python5；编译/DEX/APK/lint/schema/权限通过，无host/app定向229整体结果。准入已消费；结果见 ../evidence/frontend-day-run9-20261102.json。原patch/candidate字节与哈希保留。

# run9最小编译验收方案：未应用、未触发

固定源码及已push开发分支HEAD：`2abfc619ea43e37b93006ba1f90871d50aa6d084`。main仍`3d9f16bbc153dc00dd18ad5c42cf0d233d7e4350`。本轮只修OriginalAudioStatus的表达式体/内联lambda提前返回：显式块函数中保留key(controller)、collectAsState和structural derived，使用正向可见条件，不再return@key；实际独审与本地主机34项通过，编译任务全部UP-TO-DATE，未编译Android，未宣称DEX/lint已修复通过。原失败JSON和账保持原哈希不变。

## 根因证据的边界

run8正式D8错误在OriginalAudioScreenKt.class引用$$$$$NON_LOCAL_RETURN$$$$$.<anonymous>，说明内联返回标记进入D8并含DEX非法方法名。只读javap已核现存锁定Kotlin2.4.21编译器的generateGlobalReturnFlag/getMarkedReturnLabelOrNull确实生成/识别该owner和labelName。ae（APK完成但lint失败）至e33唯一产品差异集中在该函数新key表达式、带标签提前返回及派生状态；本地无失败class，不能隔离具体Kotlin/Compose lowering pass或宣称工具链bug已复现。return@key本身为lambda局部返回。源码修复移除这条相关返回路径，不改不相关工具链，也不suppress。

## 精确准入与diff

- run9/attempt1，唯一消息：`[frontend-once-no-upload-audio-return] 2abfc619ea43e37b93006ba1f90871d50aa6d084`。
- 仅push、frontend/day-20261102、.github/workflows/frontend-day.yml路径、指定公开T1doo/AIyou仓库。checkout和首步断言固定同一完整SHA，工作流执行SHA与源码SHA分别打印。
- 真实workflow仍run8，SHA256 `8ee0525a76a9e1b2868c4d66fc82032c6eb3f65ce5c06ece011de97475283a45`；没有应用候选、提交唯一消息或触发run9。
- candidate SHA256 `2f9805fa8fe0b5703d50c2ded46dc2164db218dd3697aadc62fe86bbd9808667`。
- patch SHA256 `50d5a72e2bd215c44d27a482e1a00535c79b6a132f92ed10d66c6502ce31762c`。
- 完整.patch/.candidate.yml/.review.json均为本地未跟踪审阅文件。差异是准入8改9、唯一消息/两个固定SHA，以及去掉host139和app12两个回归阶段及其XML统计。本次只验证编译/DEX/APK/lint/schema/权限和必需领域测试，不能当作229项/28套件整体回归通过。构建、lint和剩余domain断言完全不变，不靠降低失败阶段要求通过。

## 验证及预算

单个标准ubuntu-24.04 job，全局20分钟；原contents:read及固定checkout/setup-java pins保持。既有SDK许可、public/source/385463c祖先/token/schema基线检查不变，不安装新SDK或接受新协议。构建原720秒timeout加TERM后10秒KILL：

`scripts/gradle.sh --no-build-cache --no-configuration-cache --no-scan :app:checkDebugAarMetadata :app:assembleDebug :core:domain:test :app:lintDebug`

随后原schema复核、编译APK manifest权限检查（无INTERNET）及源无变动检查必须通过。预期实际domain XML12套件78项、零失败/错误/跳过，另5项Python token契约；78当前只是计划数，缺失/NO-SOURCE不能算通过。若编译/DEX失败，lint和后续仍明确未验，不用本地34或旧195充当结果。

当前正规API核repo public，PR0，各queued/in_progress/waiting/pending/requested0，前端workflow latest仍run8/attempt1 41455962724 completed/failure，源码2abfc61关联运行0。父批准后执行前必须重核动态条件、HEAD、源/补丁哈希；不符即停，不调run号绕过。

继承21次27615秒（7h40m15），若另获批且跑满20分钟，配置上限为22次28815秒（8h00m15），实际按API job起止追加。公开标准runner计算免费依据：[GitHub Actions billing](https://docs.github.com/en/actions/concepts/billing-and-usage)、[标准runner](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)。不使用larger/self-hosted/付费runner、不引入新凭据权限。不保证已有artifact或账户存储零费。

## 上传与停止条件

RUNNER_TEMP两处用户目录写GITHUB_ENV、setup-java不配cache、Gradle禁build/configuration cache及scan保持。无artifact/cache action、ZIP、APK/package/Release发布、deploy、截图/Preview/旧基线、设备/emulator/IME/TalkBack/D08/旧OOM/OCR/ASR/模型/真实AI组。普通平台日志/状态会持久保存；Cache mode: write能力元数据不等于已上传。已有76,949,894B/7天工件不删改，不恢复任何上传额度。

父独审前不执行。获批后若任一步失败、超时、SDK/许可不符或XML缺失/计数不符，停止并核账，无自动重试/后续run。真实artifact API及steps/log读回证明无新增上传；本方案尚未运行，不能预写通过或0上传的实际结果。
