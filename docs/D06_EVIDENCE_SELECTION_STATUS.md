# §5.2 / D06 本机出处选择与确认

基线 `4785176f900f90c428a03e7477fdf4d05e70c459` 的候选切片已获上层独立源码及运行证据核验。
本片仍在同一指定环境、唯一写入者，开发分支 `codex/d06-evidence-selection-20261017`。
工作前核对本地及远端 HEAD、祖先和干净工作区，完整重读两份原方案，文件哈希不变；
未找到其他本地 SKILL.md，未知 `.git/codex-index-refresh.lock` 未触碰。

## 先核已有实现

已有 `LocalEvidenceController`、最多五条的本机候选、精确来源卡、原文跳转及 SQL 私有
proof；已有单源远端 consent/job/send 协议及其他用途的任务/导出确认。没有当前问题的
出处子集选择与确认。本片直接扩展既有卡片与控制器，没有重造候选视图、检索、排序、
索引、导航、schema 或远端发送合同。

## 用户行为与身份

- 默认不选中任何出处。用户在原候选卡主动勾选或排除，始终最多五条、每记录一条。
  原问题、精确摘录、原始日期/时区、source revision 及半开 Unicode 码点区间可见。
- 确认按钮仅在非空选择时可用。确认时显示本机检查，使用现有 `publishCurrent` 核对
  同一个完整私有 proof；确认后显示“已确认本次本机出处”。这不表示事实已经证实。
- 每次选择变化生成新随机选择集 ID，撤销旧 receipt，包括先排除再选回相同成员的 ABA。
  接受确认还须同时匹配控制器内持有的 receipt 对象、选择集 ID、完整不可变当前状态、
  问题/筛选来源实例、owner 和候选 generation。相同 ID 字符串或伪造 receipt 无效。
- 逐源版本、原文哈希、处理策略及 access generation 继续绑定在现有 owner 的私有 proof，
  不把裸引用、可伪造的公开代次或单源 `ConsentGrant` 当作整包权限。proof 保留原有
  标签、日期、收藏、图片及索引检查；它可能因未选来源变化而保守失效，不能缩窄校验。
- 任一来源删除、改版、撤权/恢复 ABA 或 proof 读取/事务失败都清除整包选择与确认，
  不能自动接受剩余来源或重绑到新版本。用户需要重新查找、选择并确认。
  当前输入或账号变化在下一次 sync 前也会拒绝旧点击/receipt。清空、退出此视图、
  打开原文等路由离开或 composition 关闭会丢弃本机选择；返回后需重新确认。
- 确认及检查中改选、清空、同问题重入或账号切换，迟到回调也不能恢复旧确认。
  选择集、问题、receipt、引文均只在内存，不存 Bundle、DataStore、数据库或缓存。

## 合并预算和本机边界

候选与确认共用一份精确区间验证函数：当前 source ID/revision、原文合法 Unicode、
左闭右开码点范围和逐字引文必须同时相符。沿用单条 240 码点、合计 1200 码点/4800
UTF-8 字节；问题最多 512 码点/2048 字节。确认后本机 inspection packet 只含所选
精确引文、日期/时区、版本及策略，按原候选顺序，不含整个原文或未选候选。
问题加引文合计最多 1712 码点/6848 字节。这是文本预算，不是 token、序列化请求
总长或进程堆上限；没有隐式截断或省略号混入引文。

`inspectConfirmed` 仅供本机非阻塞核对，在同一 counted SQL lease 内再次核验整个
proof 和 receipt 才发布局部数据；产品没有接入该接口的模型消费者。保留下来的 packet
是数据，不是持续有效的能力或授权；不得在回调中执行网络/阻塞工作。没有有效确认时
不会进入本机消费者。该本机类型没有连接/consent 字段、远端请求转换器或发送入口，
本机确认后同样不产生可用于发送的请求。合成 provider 仅是测试里的内存记录器，
不是 HTTP、密钥解析或真实模型。旧单源许可在既有域 guard 中仍只能匹配该来源；
LOCAL_ONLY 和另一来源均不得借此外发。

AI_NONE 仍排除，LOCAL_ONLY 默认不变；没有新增权限、条款、凭据、费用、依赖、
schema、模型调用或设备配置。真实多源 AI 问答仍需服务配置、逐源当前远端授权和
多源发送/取消/提交一致性合同，完整 D06 仍 OPEN。新切片独立签核交上层复审。

## 验证与门禁

首轮新增 15 项专项主机测试全部 PASS，无失败重试；随后加强逐源混合选择检查，
补充 inspection 检查期间改选的迟到回调用例，完整回归含新增 16 个唯一方法。
测试使用实际 App 候选/选择控制器、matcher、生产 SQL 私有 proof 与有界 Python
SQLite transport；不是 Android Room/SQLCipher。合成消费者全在本机内存。
原始 XML、执行输入指纹、完整方法清单、专项及完整运行日志随证据交付。

只读 SDK 前置本轮实际 FAIL：`ANDROID_HOME must point to an SDK with developer-accepted licenses.`
库存无已配置 SDK/许可、adb、emulator，KVM 不可读写。未接受/配置 SDK 条款。
Android AAR/APK/lint、Compose 编译/交互、Room11/SQLCipher、Preview、真机、返回
位置、窄屏大字体、TalkBack 与性能均 NOT RUN；新增两项 Compose 源码用例登记原门禁，
没有新 APK。主机通过不冒充 Android 或设备验收。

Actions 未访问，也未尝试替代路径。新 CI 未知；旧 `01584dd7df71d9ffa3754ffa2555fe4580c6abc0`
D01 run `38980866184` 的失败原因仍未知，不归因或盲改。main 不变，不强推、不部署。
最终测试计数、指纹及便携证据见 [主机证据说明](evidence/D06_EVIDENCE_SELECTION_HOST.md)。

最终实际执行 JVM **941/941 PASS**（AI178、domain71、SQL/controller692；新增16项
已包含，925基线保留），原始XML全部0 failure/error/skip；输入门及离线验证通过，
源码指纹 `a4caacefee218712e65289e31bfae875b478bece54cfa9a05d5932fae365e3dd`。
Python151/151本轮实际复验，无新增；队列149仅历史。本片仍待上层独立复审。
