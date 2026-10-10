# D01 工程及静态视觉限定通过

最新结论见[D01_ACCEPTANCE.md](D01_ACCEPTANCE.md)：精确5e9871b源码及修后212渲染的
工程与独立静态视觉复核已通过，原Privacy/EMPTY缺陷及长文本证据缺口关闭，可进入D02。
设备交互、可访问性与性能仍NOT RUN。以下是保留的历史工程记录，不是当前验收结论。

后续用户已上传五包，主对话与独立审查实际查看全部198图，确认隐私长文本裁切与
EMPTY预览契约矛盾；D01未签收。下面保留的是旧提交的工程证据，不是当前视觉结论。
当前修复与14张完整长文本补证范围见[D01_PIXEL_FIXES.md](D01_PIXEL_FIXES.md)。

D00已由主对话验收。本轮依原方案完成D01：语义Token、8组件、三主页面、
假数据与各状态Preview，冻结源码独审和实际构建/严格lint/自动渲染均通过。
尚未人工检查PNG、运行真机或TalkBack，不能宣称原方案视觉门槛全面通过。
D02未开始。实现及新增依赖的兼容风险见[D01_DESIGN.md](D01_DESIGN.md)。

## 冻结源码与实际 CI

已独审并实测的源码SHA：`45299cc94b30f2c083ee98c9b226b38707038616`。
本状态更新只改此文档；最终交付SHA/远端以交付报告为准，不自引用未创建提交。
分支：`codex/d01-designsystem-20261010`，正常提交/推送；main仍为
`7383e502418f5f9fc0c540ef2072742359343d40`。两原方案字节未变，没有Starter ZIP。

[CI run38020724736](https://github.com/T1doo/AIyou/actions/runs/38020724736)，
job114120922728，2026-10-10 UTC completed/success，head SHA精确匹配上述源码。
完整日志保全`/workspace/d01-ci-job-45299cc.log`。

| 实际命令 | 结果与证据 |
| --- | --- |
| `scripts/gradle.sh :core:domain:test` | PASS；6任务全部执行，3测试0失败/错误/跳过 |
| `python3 -m unittest discover -s scripts/tests -v` | PASS；9项：5Token/对比度契约、4PNG完整性反例回归 |
| `python3 scripts/generate-tokens.py --check` | PASS；Compose Token与JSON源一致 |
| `python3 scripts/verify-test-results.py` | PASS；真实非空JUnit XML；未把NO-SOURCE算作测试 |
| `python3 scripts/check-sdk.py`（runner） | PASS；既有Platform37.0 r2、BuildTools36.0.0及匹配条款接受记录；只读，无安装/接受步骤 |
| `scripts/gradle.sh :app:checkDebugAarMetadata :core:designsystem:checkDebugAarMetadata` | PASS；3任务执行，验证新增library/tooling依赖 |
| `scripts/gradle.sh :app:assembleDebug` | PASS；56任务/52执行，两模块compileDebugKotlin实际执行 |
| `scripts/gradle.sh :app:lintDebug :core:designsystem:lintDebug` | PASS；67任务/32执行，两份报告均No issues found；abortOnError+warningsAsErrors保留 |
| `scripts/gradle.sh :app:updateDebugScreenshotTest` | PASS；41任务/5执行，实际生成初始render smoke图片 |
| `scripts/gradle.sh :app:validateDebugScreenshotTest` | PASS；41任务/1执行，执行截图JUnit引擎 |
| `python3 scripts/verify-preview-results.py` | PASS；198实际用例、198 PNG、0失败/错误/跳过；完整结构/CRC/解压/scanline/结束块及哈希检查通过 |

198项范围：8组件×7状态×2尺寸=112、3页面×7×4尺寸=84、主导航2尺寸。
含320dp/200%字体组合及横屏/大屏。该结果证明hosted Layoutlib真实渲染和
结果完整性；初始update+validate不是已批准golden的回归，更不证明外观或
交互正确。reference仅CI生成，不提交；未以自动更新golden掩盖回归失败。

## 产物

以下产物均属于精确源码SHA45299cc，expires 2026-10-24 UTC；最终文档提交自动
触发的CI如另有产物，以最终交付链接为准。本环境没有APK或PNG本地副本；此前
artifact下载通道403，未绕过或为看图改写上传路径，人工视觉检查保持NOT RUN。

- [调试APK](https://github.com/T1doo/AIyou/actions/runs/38020724736/artifacts/11658326507)：
  runner路径`app/build/outputs/apk/debug/app-debug.apk`；APK SHA256
  `b36f49eda88656ad8c488ebbaa00611eb74fed499d786854ce80fb9ff736ebb3`；
  artifact ZIP SHA256 `a3653019e91a8b2d367c66c3e85350cfdc4b791cbfa412542d1edea98fc9f369`。
- [198项初始渲染图片](https://github.com/T1doo/AIyou/actions/runs/38020724736/artifacts/11658985906)：
  ZIP SHA256 `9da0cf703c11c0180ea6d64a4184de5c1cb67cde1a6eca3c61aea3ccc6dffa67`。
- [测试、双模块lint、截图HTML/XML和逐图哈希manifest](https://github.com/T1doo/AIyou/actions/runs/38020724736/artifacts/11658491336)：
  ZIP SHA256 `f11d6812eb8e25ad3619f798495579bd51688cbb35c15a1e3d70ec84fdcd334f`。

## 独立复核与已纠正失败

96d48e5完整独审：5Python、3领域单测、针对性锁解析实际PASS；范围/隐私/许可
边界正确，发现P3 PNG guard接受24字节假PNG。d46f1ea定点独审关闭P3：独立
9Python PASS，198假PNG完整反例exit1；两处Compose Modifier API修正一致。
45299cc定点独审PASS，确认删除10无调用旧字符串及CI失败保全条件。
复核证据`/workspace/d01-readonly-review/{96d48e5,d46f1ea,45299cc}/REVIEW_RESULT.md`。

历史真实失败保留：

- 本地app:resolvableConfigurations：FAIL，ConcurrentModificationException；
  `/workspace/d01-configurations.log`。
- 直接resolve Android secondary artifact variants：FAIL，歧义；
  `/workspace/d01-dependency-locks.log`。改为针对组件图生成/验证锁后PASS，
  未将其当Android artifact实测；真正artifact兼容已由上述hosted任务验证。
- 96d48e5 CI38020086747：metadata/assemble PASS，lint FAIL ModifierParameter；
  修正参数顺序，日志`/workspace/d01-ci-job-96d48e5.log`。
- d46f1ea CI38020397448：测试/metadata/assemble/library lint PASS，app lint FAIL
  10条UnusedResources；删除失效D00占位字符串，日志`/workspace/d01-ci-job-d46f1ea.log`。

没有抑制、baseline、continue-on-error、动态版本或为消除问题盲目升级。
锁更新后针对性不写锁解析PASS，独审核实锁未变化；不宣称所有变体全量锁定。

## 本地与剩余验收边界

本地完整JDK17可用；真实3领域测试/9Python测试、锁解析PASS。SDK预检FAIL，
ANDROID_HOME缺失；未安装SDK/CLI或接受协议。本地Android构建/lint/Preview为
NOT RUN，使用上述runner既有SDK成功验证，不需要扩大网络或配置凭据。

Kotlin2.4.21完整支持矩阵仍未覆盖Gradle9.8.1/AGP9.4.1；standalone截图插件
alpha16仍实验且路径已弃用。官方约束及本轮针对性实测支持当前D01，不能扩大为
普遍兼容保证，后续加依赖继续核官方矩阵并实测，不降低严格检查。

人工看图、TalkBack、真实200%字体交互、触控目标测量、键盘/返回/旋转、照片
读取/布局、真机启动、120Hz/耗时/帧、OEM与16KB设备验证均NOT RUN。
最小剩余动作：从正常GitHub artifact入口取得渲染图片并审查各状态/窄屏；
安装调试APK在设备验证关键操作，再决定D01视觉验收。无需真实模型或私有数据。

原方案下一步D02：加密本地记录、草稿、原文版本、附件状态机，离线/重启恢复
与迁移测试。先核Room/SQLCipher/KSP兼容、ABI/许可/16KB边界；保持原文、派生
内容、候选与正式任务分离，保存成功只由完成的持久化确认，默认LOCAL_ONLY。
