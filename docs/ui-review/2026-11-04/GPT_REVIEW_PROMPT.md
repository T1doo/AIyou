# GPT 前端审阅提示词

请以资深移动端 UI/UX 设计师和视觉设计师的视角，评审 AIyou（爱忆）的前端。只讨论视觉、布局和交互，不展开后端架构。

AIyou 是安卓私人记录与记忆 App：此刻用于记录文字、图片和音频；回忆用于浏览、搜索、筛选和查看原文；待办用于用户确认后的任务与提醒；水豚噜噜是可爱、俏皮、温暖的陪伴角色。

先阅读图包说明，再打开图片：

- 图包入口与全部截图：https://github.com/T1doo/AIyou/blob/main/docs/ui-review/2026-11-04/README.md
- A 组新版三主页面概览：https://raw.githubusercontent.com/T1doo/AIyou/main/docs/ui-review/2026-11-04/prototype/00-overview.png
- 此刻：https://raw.githubusercontent.com/T1doo/AIyou/main/docs/ui-review/2026-11-04/prototype/01-now-blue.png
- 回忆：https://raw.githubusercontent.com/T1doo/AIyou/main/docs/ui-review/2026-11-04/prototype/02-memories-blue.png
- 待办：https://raw.githubusercontent.com/T1doo/AIyou/main/docs/ui-review/2026-11-04/prototype/03-tasks-blue.png
- 晚霞主题：https://raw.githubusercontent.com/T1doo/AIyou/main/docs/ui-review/2026-11-04/prototype/04-now-sunset.png
- 星夜主题：https://raw.githubusercontent.com/T1doo/AIyou/main/docs/ui-review/2026-11-04/prototype/05-now-night.png
- 主题和动态设置：https://raw.githubusercontent.com/T1doo/AIyou/main/docs/ui-review/2026-11-04/prototype/08-appearance-picker.png
- B 组安卓运行对照：https://raw.githubusercontent.com/T1doo/AIyou/main/docs/ui-review/2026-11-04/android/00-overview.png

重要区别：A 是最新设计代码的浏览器运行截图，尚未整体接入 Android APK，是本次主要审阅对象；B 是现有原生 App 的真实模拟器截图，仅用于对照，不要把 B 当成 A 已实现的证明。A 组普通截图按 390×844 CSS px、2 倍像素输出。所有内容都是合成样例；噜噜使用文字占位，正式形象仍在校准。

这一阶段保留了已认可的自然景观与磨砂玻璃方向，加入晴空蓝、桃色晚霞、星夜紫主题及可选背景动态；缩小此刻欢迎区并停靠输入区；移除回忆、待办的大欢迎卡；合并重复日期；精简“2条记录”“原音”等无用注释，同时保留音频筛选和必要状态；统一中文字级、图标与按钮层级，收紧底栏选中底形，并处理面板返回、焦点、空输入禁用和快速切换。

请坦率指出仍然不够精致的地方，重点看：内容主次与留白、中文排版、图标和按钮、玻璃材质、三主题对比度、编辑和搜索流程、待办状态，以及噜噜将来的融入方式。自然背景和玻璃质感已经认可，不要改回单调纯白，也不要加无意义装饰、功能或说明文字。

请先给出最值得改的 3–5 个问题，再按 P0/P1/P2 列出“图片位置—具体问题—怎么改—为什么”。必要时给出可调试的尺寸、字号或动效范围。动效只能从截图提出建议，不能假装已观察到流畅度、真实触区、输入法或 TalkBack；若结合图包链接的源码判断，请明确区分代码依据与截图观察。若无法打开图片，请直说，并指出需要我上传哪几张，不要凭项目介绍猜测画面。
