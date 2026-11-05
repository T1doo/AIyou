# G0：完整基线结果、归档清单阻断与备份文案

起始源码：`4eab72e49ed519ed35a8c32db7327df125b5b060`。
本批继续 `CORE-01`／`AUD-01`，并处理 `UI-05` 的两个明确文案问题。
G0 的最终 CI 仍需新候选实际运行，完整二级视觉和新装恢复未签收。

## 基线 CI 的实际结果

[运行 42065337675](https://github.com/T1doo/AIyou/actions/runs/42065337675)：

| 范围 | 结果及证据 |
| --- | --- |
| 主机合约作业 | GitHub 作业成功；协议原始 XML 为 domain 78、AI 178、remote-text 1559，均 0 失败／错误／跳过 |
| 完整原生界面 JVM 集 | 原始 XML **413 项，0 失败／错误／跳过**；不是设备测试 |
| Preview 作业 | GitHub 作业成功；原始 XML **444 项，0 失败／错误／跳过**，manifest 登记 444 张 PNG |
| Android 整个作业 | **FAIL**，发生在测试通过后的偏好／回顾 XML 归档清单校验 |
| 后续原生审计与 Linux lint | 原作业被上述错误中断，未执行；不继承旧结果 |

协议和 Android 原 artifact 的 SHA-256 均与 GitHub 元数据相符，原 ZIP 留在本目录。
Preview 报告原 ZIP 为 146841848 字节，已核对 GitHub SHA-256，留在忽略的
`output/base-ci-4eab-preview.zip`，没有把大文件重复提交。
其原始 XML、PNG manifest 和阶段 receipt 在 `base-preview-xml.zip`。
HTML 内嵌的四个 Skyglass PNG 已解码，并逐一核对 manifest 的哈希与尺寸，存于
`base-previews/`，实际打开检查过。它们是独立材质／排版组件样本，仍有原始简单
示例外观，不是当前主页面截图，也不是已经获批的页面 golden。

## 最小归档修复

`UiPreferencesTest` 实际已有 11 项，但 workflow 的明确清单仍列 10 项，遗漏
`scenePreferenceMigratesV3WithoutChangingCapturePolicyOrWritingOnRead`。
加上回顾类 8 项，精确数量应为 **19**。旧脚本在第 36 行的数量断言失败。

本批只增加该方法名，把断言／回执中的 18 改成 19。精确类名、完整方法集合、
无重复、零失败／错误／跳过的断言全部保留，没有把数量改成范围或删除校验。
同一份实际 Linux XML 上，原归档脚本可复现退出 1；修正脚本退出 0，生成 19 项
回执。脚本 fixture、原失败及修正 receipt 随本目录保存。该复现不重跑测试，也
不能倒写原 CI 为成功；新候选仍须走完整 workflow。

三个历史诊断 runtime、授权／预算／nonce、禁用状态及冻结 fixture 未变。
本机相关脚本检查：CI stage 13 项有 1 个 Windows 符号链接权限错误；诊断合约
18 项有 1 个相同类型错误。其余方法通过，两个完整集合均记为 FAIL，不跳过
符号链接检查，不改系统设置。完整输出保存在 `script-checks.zip`。

## 备份范围与两个产品文案修正

[完整源码核对](../../F08_BACKUP_COVERAGE_AUDIT_20261106.md) 区分 Room 17、
逻辑包 v8–v16、纳入／排除对象、硬边界和设备验收缺口；25 个 Room 实体都能
对应到 16 个纳入表或 9 个明确排除表。静态源码指纹另见上一批目录的
`f08-source-audit.json`，其基线仍为上面的 SHA。

实际产品只改两个字符串：

- 完整备份容量改为“图片与原音合计最多256个附件、64 MiB（含历史版本和草稿）”，
  对应生产 codec 的共享媒体预算；保留已有折叠详情和所有数据操作门槛。
- 模拟器两倍字号暴露导航长标题末字独占一行，导航标题缩短为“备份与恢复”；
  内容内仍写“完整资料库备份”。未压缩系统字号、设置硬换行或裁掉标题。

没有改变存储容量、数据库／包格式、认证／恢复、主题、动画或数据控制器。

最终候选实际执行四项规定 wrapper 检查和原有 `FullBackupPresentationTest`：
**退出 0，7 项 UI 全通过，0 失败／错误／跳过**。assemble、app 编译、lint 和 UI
实际执行；domain 为缓存，78 项原 XML 仍检查为零失败／错误／跳过。没有新增
只核对字符串的测试。最终日志／XML／lint 在 `final-local-checks.zip`；先前仅改
容量文字的检查另存 `local-checks.zip`，两次的 7 项不能相加成 14 个独立场景。

## 真实模拟器与图像证据

本轮 API35／Android15、480 dpi，已有 D 盘 AVD，隐藏／无声音、2 核／1536 MiB。
普通字号与两倍字号实际走设置 → 完整备份首页 → 展开详情 → 滚动容量 → 返回设置
→ 返回主入口。没有点击核对快照、文件选择、口令认证或导入。

- [普通字号完整容量段落](capacity-normal.png)：段落边界 `[48,1455,1032,2001]`，
  位于 `[48,352,1032,2037]` 滚动视口内，原始画面已打开检查。
- [两倍字号标题](backup-home-large.png)：短标题和返回设置均为单行且完整可见。
- [两倍字号容量开头](capacity-large-top.png)、[尾部](capacity-large-bottom.png)：
  通过实际滚动检查首尾；不能仅凭一个带完整 text 属性的裁剪节点宣称读完。

`before-title-*` 为中间候选的诊断画面，显示当时标题孤字换行；中间 APK 未保留
哈希，不用作最终候选的设备签收。首次首尾截图重复落在滚动尾部、普通字号
首拍只露出一行的问题均已重拍，修正写入 `device-observation.json`，不把不完整
画面误记为通过。

本页仍为旧的白色二级视觉，上述截图只验证文案、滚动、标题和返回，**不代表
Skyglass 视觉签收**。真实中文 IME、TalkBack、新装恢复、OEM 提醒、16 KB 与
性能没有在本批执行。模型／OCR／ASR 仍未启用。

字号 1.0、自动旋转 1、旋转 0 已读取确认恢复。模拟器 PID82776 及其子进程均退出，
设备为空后关闭本轮 adb；延迟退出的 helper 随后自然退出，没有强杀。见
`cleanup.json`。APK 路径与哈希及源码指纹见 `report.json`。
