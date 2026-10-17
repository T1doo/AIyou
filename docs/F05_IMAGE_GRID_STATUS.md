# §5.2 当前图片筛选结果网格

从独审基线 `01584dd7df71d9ffa3754ffa2555fe4580c6abc0` 接续。指定新环境最初实际为
`work@7383e502418f5f9fc0c540ef2072742359343d40`，工作区干净；默认 Git 网络核实并
获取指定远端分支后，创建 `codex/f05-image-grid-20261017`，不恢复旧实例写入。
两份原方案完整读取并保留。未发现本地 SKILL.md，根 AGENTS.md 已读取。

## 用户行为与数据边界

- 默认仍是原时间线列表。开启当前版本图片筛选后，可主动“切换图片网格”，可以切回
  列表。关闭图片筛选或清空条件会重置网格；偏好与缩略图不进 Bundle／磁盘。
- 使用同一个 `OriginalSearchState.hits`，不另起检索或重排。关键词／精确片段、日期、
  手工标签、收藏及图片条件不变。每记录一张当前版本 PNG/JPEG；代表图按附件 ID
  稳定选取，不因失败而改用历史图片或另一张图片。纯图片记录保持空原文。
- 卡片沿用原查找结果的 proof、原文定位及列表返回锚点。大字体时改为单列；图片使用
  Fit 完整显示，正文可换行，失败卡片仍有打开原文入口。
- 复用同 records owner 的 AttachmentStore.list/read、认证／hash／大小／格式检查和
  严格 PNG/JPEG 解码。store 自身既有恢复与完整性状态标记行为未改。网格不创建
  附件、不替换原图、不写明文临时文件或缩略图缓存。
- IO 使用现有 ViewModel 串行队列；每次只排一个读取命令，结束后才排下一张，允许
  其他已排队的保存／删除命令先执行。关闭或切换不清除仍在认证中的明文源，读取
  完成后必在 finally 擦除；尚未发布的图片释放。
- LazyColumn 实际可见行决定读取，预组合项不触发解码。当前请求最多保留 12 张，
  单图最大边 256、实际 allocationByteCount ≤256 KiB，受持有缩略图预算 3 MiB；
  另允许一个 256 KiB 未发布解码结果。原附件最多 16 MiB；既有认证／PNG 校验的
  有界工作内存另计。这不是整个进程堆或 RSS 的实测上限。已发布 Bitmap 随 Compose
  引用与 GC 生命周期回收，避免回收正在渲染的图；快速滚动的峰值仍须设备测量。
- 读取前后检查当前源／版本、owner、输入代次与可见集合。发布再经现有私有检索证明
  的 counted SQL lease，覆盖删除／恢复、附件元数据变化。账号 owner、筛选、版本、
  删除或关闭后的迟到结果不能回填。读取或解码失败保持失败卡片；同一请求不自动
  重试。超出当次可见预算的项不解码，滚动后重新按可见集合分配。

未新增 schema／迁移／权限／依赖或条款、模型调用、凭据或网络安全配置。
LOCAL_ONLY 与无自有后台保持。main 不变；仅正常开发分支提交／推送。

## 验证记录

验证结果及原始日志保留于当前指定环境 `/workspace/scratch/aiyou-image-grid-evidence/`。
本文件末段按最终实际结果补全。主机 SQL transport 与合成图片解码边界不代表
Android／Room／SQLCipher／真机验收。

首轮网格专项实际执行 12 项，11 PASS／1 FAIL：删除场景的测试误用了不存在的
`entries.deletedAtMillis`，导致合成回调提前抛错。失败 XML 与日志已单独保全；修正
为生产 `remote_source_access.deleted/generation` 路径后，专项 12/12 PASS。
原四项图片筛选 controller 回归也实际通过。仅作有限、有明确修正理由的重跑。

SDK 只读前置检查 FAIL：本环境没有指向完整、既有许可已接受 SDK 的 ANDROID_HOME。
未安装 SDK、接受新条款或配置通知权限。AAR／APK／lint、Compose 编译／交互执行、
Android PNG/JPEG 解码、真实 Room11/KSP、Preview、仪器及真机均 NOT RUN，无新 APK。
新增 2 项 Compose 和 2 项原生图像解码用例已登记原有精确方法质量门，源码覆盖不冒充执行。
权限 APK 检查亦因 SDK 前置缺失未执行。新提交 CI 状态未知；未访问此前拒绝的
Actions、artifact 或替代路径。既有未知超时根因不在本片解释或修复。

本任务进行源码与差异复查；另一审查者对新提交的独立签核仍待上层独审。

最终实际执行：完整 JVM **907/907 PASS**（AI178、domain71、SQL/controller658），
精确方法清单与执行输入指纹门 PASS；新增网格 12 项均包含其中，不重复累计。
独立队列 **149/149 PASS**，原始 XML 与既有质量门 PASS；同命令重复的 AI/domain
不重复计覆盖。Python **151/151 PASS**。最终完整回归无测试失败／跳过。
`git diff --check` PASS；两份方案 SHA-256 与独审基线一致。
输入指纹：`3ffaf8f49eaec889ce275895fe842de403cef5883f99391c2f6d08e11a22c588`。
原始 JVM XML、队列 XML、方法清单、SDK 前置失败、首轮专项失败和修正日志均已保全。
