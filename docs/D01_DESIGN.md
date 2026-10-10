# D01 视觉基础（依据原方案补建）

来源是两份原始方案的第5/6/7/17节。Starter ZIP、原 design/tokens.json 和
CODEX_TASKS 不在初始仓库；本轮自行补建，不声称导入这些不存在的文件。
D00已由主对话验收；本轮仅D01，不引入D02保存或后续模型、提醒功能。

## Token 与组件

`design/tokens.json`为唯一机器可读源；`scripts/generate-tokens.py`生成
`core/designsystem/.../AIyouTokens.kt`，CI以`--check`拒绝漂移。固定浅色奶油手账
主题、系统字体；重要文本和可操作边界使用经过对比度验证的语义色，装饰色不作正文。
4/8dp节奏、窄屏16dp/宽屏24dp页边距、最大720dp阅读宽度、20/24dp圆角。
Material按钮维持默认48dp最小触控目标，不施加固定高度以容纳大字体。

8组件：MemoryCard、CaptureComposer、PrivacyChip、SourceCitationCard、
TaskProposalCard、ProviderConnectionCard、StatusNotice、MascotBadge。
均提供正常/禁用/加载/错误/长文本Preview，另含空白和离线状态。
每个组件显式组合360dp常规与320dp/200%字体；不将两个独立Preview注解误称笛卡尔积。
原文与可选派生摘要分区，引用含日期/版本，建议不进入正式任务计数。
所有状态带文字，错误留在组件附近；文本换行，按钮用FlowRow换行，页面可滚动。
MascotAssetProvider只有状态到中性展示的资源接口，未接入噜噜IP或角色演绎。

## 页面和演示

底部此刻/回忆/待办，右上设置。初次运行为空状态；设置可主动打开虚构示例及
状态切换。假数据固定日期、明确标识，无真实模型/用户记录/密钥。
此刻含今天、占位徽章、最近记录示例与记下来/问一问输入；回忆含搜索外观、
换行筛选和独立引用示例；待办分待确认/今天/接下来/已完成，正式任务为0。
未实现动作禁用并给出说明；未把按钮点击或UI插入显示为已保存、已联网或已调度。
输入与查询仅Compose内存，不进入rememberSaveable、磁盘、日志或网络；切换页面
可能清除演示输入。D02再实现加密草稿，当前无重启恢复承诺。

页面使用稳定列表key、安全区padding与consumeWindowInsets；IME出现时收起底栏，
adjustResize配合imePadding。真实键盘/返回/旋转/TalkBack表现仍须设备验证。
无自定义位移或装饰循环动画，减少动画模式不需要关闭额外循环。

## Preview 渲染路径与兼容边界

新增依赖均固定：ui-tooling-preview/ui-tooling由原BOM2026.09.00选定1.12.1；
Android library插件同原AGP9.4.1；screenshot插件和validation-api为0.0.1-alpha16。
[官方standalone配置](https://developer.android.com/studio/preview/compose-screenshot-testing)
要求AGP>=8.5、Kotlin>=1.9.20、JDK>=17，满足当前版本。
官方源码接受8.5beta1<=AGP<10；独立隔离配置已实际注册update/validate任务。
standalone路径已弃用且插件仍为alpha；AGP test-suites替代需要9.5alpha，当前保持冻结
工具链，不为测试盲目升级。D00记录的Kotlin完整支持矩阵外风险继续适用，不能宣称
普遍兼容，新增依赖以AAR metadata、实际编译、严格lint及渲染核验。

插件的Layoutlib16.1.0-jdk17来自Google Maven，读取runner既有SDK，不运行sdkmanager。
SDK许可预检仍先执行，sdkDownload/Java自动下载仍关闭；本地没有SDK，也未接受协议。
新增Debug编译/运行、Kotlin编译器和截图配置以实际解析生成锁；不宣称所有变体或
插件内部detached配置全量锁定。

针对性复现命令：`scripts/gradle.sh d01ResolveLockedDependencies --init-script
scripts/resolve-d01-dependencies.init.gradle`。只有审阅后有意更新版本时追加
`--write-locks`；普通CI不改锁。解析组件图避免无SDK时直接选择Android库的多个
secondary artifact variant；真正artifact/SDK/编译兼容仍由hosted构建验证。

198项hosted render smoke计划：8组件×7状态×2尺寸=112，3页面×7×4尺寸=84，
主导航2尺寸=2。三页面另有800dp横屏和840dp大屏。截图测试源位于
app/src/screenshotTest/kotlin；初始reference仅CI生成并忽略，不作为已批准golden。
首次update再validate只能证明实际渲染、测试发现和结果一致性，不能证明外观正确。
verify-preview-results要求198个实际无失败/跳过XML用例及198个有效PNG并记录哈希。
不能为后续截图回归自动更新golden消除失败；需要独立人工看图后另行批准基线。

## 实际检查与待验收

命令与结果最终见D01_STATUS.md。5项Python设计契约验证文本4.5:1、控件边界3:1、
字体/触控Token与生成一致性；3项原有纯Kotlin测试继续保护默认隐私行为。
这些不能替代实际布局/语义树/点击/TalkBack检查。图片只提供中性占位标签，
真实图片解码/照片布局属于D03及设备验证。本轮APK与PNG通过正常CI artifact交付。
若本地下载仍403，不绕过，也不标看图通过；真机/性能/无障碍保持NOT RUN。

原方案下一阶段D02：加密本地记录、草稿、版本、附件状态机；通过离线/重启恢复
与迁移测试。需要先冻结Room/SQLCipher等官方兼容依赖并验证ABI/许可/16KB边界，
原文保存成功反馈必须来自持久化完成，不依赖任何AI或网络。
