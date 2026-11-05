# G0：自然背景无限帧与陪伴入口

本批基于 `f5cc92c4529f53574977205518b382e5b0a883d4`，最终交付提交绑定
`report.json` 中三个生产文件的 LF 归一化摘要。没有修改测试正文、扩大超时、
关闭动态或增加测试专用产品分支。

## 修复

自然背景原来通过普通 `withFrameNanos` 无限请求帧，真实设置往返测试无法
进入空闲。改用 Compose 的 `withInfiniteAnimationFrameNanos`，使其参与
标准 `InfiniteAnimationPolicy`。原有前台生命周期、减少动效、输入／滚动暂停、
相位保持及帧间隔上限保留。此 API 对无测试策略的实际 App 仍请求动画帧。
依据：[Android API](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/InfiniteAnimationPolicy)。

仅修帧接口后，原测试继续执行并暴露真正的页面遗漏：原生 Skyglass 回忆与
待办页隐藏旧欢迎区时，也漏掉了陪伴入口。现在在这两页的页头保留一个 48 dp
的小型入口，透明底；此刻页继续使用原有欢迎卡入口。显示许可和偏好仍由
`companionVisible` 管理，角色没有获得记录、事实或任务操作权限。

## 验证与边界

- Wrapper 的 AAR metadata、assembleDebug、domain:test、lintDebug 通过。
  AAR 与 domain:test 为 UP-TO-DATE；assemble、lint 及定向 UI 实际执行。
- 原先失败的三页设置往返测试：1 项、0 失败／错误／跳过。XML、初次失败及
  最终日志都在 `results.zip`。
- API 35 / Android 15，1080×2400、480 dpi 的既有模拟器实际安装并运行本轮
  APK。回忆与待办层级各只有一个入口，边界 144×144 px，即 48×48 dp；
  实际点开并关闭了陪伴面板。
- 原模拟器设置为星夜紫＋流光＋减少动效。减少动效开启的两帧，背景区域
  `[80,1300,1000,1750]` 没有像素差异。临时关闭减少动效后，同一页约 6.04 秒
  间隔的两帧出现背景变化。两组都排除了系统状态栏、陪伴图和任务卡片，
  没有把时钟或角色变化算作背景运动。量值和原始 PNG 见报告。
- 验证后恢复并读取确认“减少动效=true”。只操作合成测试设备；没有新增记录、
  待办、提醒或真实模型调用。最后关闭本轮模拟器，确认设备列表为空，再关闭
  本轮启动的 adb 服务。

这只是本批交互与状态证据，不能签收完整界面。截图中仍有模拟器系统栏上沿
截断，以及回忆列表孤立“操作”行等问题；周日历、紧凑任务组、详情等原型
对照仍待推进。最新全量 UI 仍是 **f5cc92c 的 409 项／71 失败**，本补丁后
没有再跑全量，不能自行减为 70 项失败。

本批没有重跑四个 Preview，没有真机中文 IME、TalkBack、OEM 提醒、16 KB 或
帧时间证据；模拟器预装输入法为 LatinIME，不能用于替代这些验收。

原始截图：`memories-entry.png`、`tasks-entry-a.png`；静态控制的第二帧为
`tasks-entry-b.png`；动态两帧为 `motion-a.png`、`motion-b.png`。
APK 路径与完整哈希见 `report.json`。
