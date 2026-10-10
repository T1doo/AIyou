# D01 已确认像素缺陷修复与覆盖补证

本轮以用户上传的五包为准。主对话及其独立审查已实际查看全部198张原图，并重点
原尺寸复核。D01未签收，D02未开始；原文件与旧CI证据保留，不把旧render smoke称为视觉通过。

## 已确认问题

PrivacyRender_常规_12b592c2_e0fd052a_4.png（945×966）与窄屏大字体_4
（840×2400）的隐私长文本被胶囊形状裁切；常规图完整边框可见，不能归因于2400px上限。
原PrivacyChip用CircleShape裁剪多行Column，圆角随内容高度增大，12/8dp padding不能保护正文。
现改用固定8dp token圆角，保留原标签、正文、字数、字体及12/8dp内边距。

Composer、Memory、Proposal的EMPTY_5预览仍注入非空数据与内容动作，属于预览/展示契约
矛盾；既有静态图不足以断言真实运行时故障。现fixture为空，无记录时间、附件或AI摘要、
建议时间或来源；Memory/Proposal组件有专用空态而无内容CTA。Composer空输入禁止提交，
输入文字后不再追加“暂无内容”；错误/加载/禁用状态不因输入而被抹去。

## 长图覆盖

Memory/Notice/Proposal/Source/Mascot窄屏大字体长文本自动截图恰为2400px，下方缺失
是覆盖缺口，不据此报运行时截断。保留原198个状态/尺寸组合，另加Privacy、Memory、
Notice、Proposal、Source、Mascot、Provider七组件的LONG_TEXT常量完整预览：
常规360dp宽/1600dp高，窄屏320dp宽/fontScale2/4000dp高，合计14张新增图。
同一未缩短正文，末尾追加“完整内容结束”复核标记。它们是静态完整内容画布，
不是设备滚动截图，也不提供IME、触控可达性或TalkBack证据。

实际render guard须212个无失败/跳过XML用例及212张完整PNG；新增14图须实际高于
2400px。既有五包保留（28+28+28+112+2），新增d01-visual-complete独立14图小包。
所有内层仍限制17,000,000字节，交付时核每个GitHub外层<18,000,000字节。

## 回归与复核边界

新增JUnit4.13.2使用现有固定版本。:core:designsystem:testDebugUnitTest须实际执行7项：
真实shape的四角受内容padding保护、角半径不随高度增大、空Memory/Proposal无内容、
空Composer及编辑反馈、错误/加载/禁用不被抹去、长文本未缩短及原文/派生内容分离。
CI读取真实XML至少7例并核实际testcase数，无结果或NO-SOURCE不能算通过。

本地13项Python与token核对已PASS，更新的单测/编译器依赖图锁生成PASS。
本地仍无Android SDK，不接受或安装SDK；实际Android单测/构建/lint/render留待
正常推送自动CI，未在这里提前写PASS。严格lint规则、默认LOCAL_ONLY及原方案不变。

Library组件ZIP正常materialize失败；单张原图
图片读取返回正确945×966/179992字节元数据和图片引用，但本执行环境无可显示像素块，
正常materialize亦未落盘。实现者未冒称自己已看原图，不重试受拒artifact或绕过网络。
按主对话后续明确授权，依据其和独审的已确认像素证据及已定位源码实施修复。
修后仍必须实际渲染并交由主对话/独立像素复核；不能用新测试或自动自比对提前签收。

主导航大字体首屏输入区仅部分可见，源码确认其是LazyColumn末项，并实际应用
Scaffold的padding和consumeWindowInsets；没有滚动到底/键盘交互执行证据，
不能单凭首屏定为遮挡。完整只读核对保存在交付证据d01-scroll-insets-readonly.md。
设备、滚动、IME、性能与TalkBack仍NOT RUN。
