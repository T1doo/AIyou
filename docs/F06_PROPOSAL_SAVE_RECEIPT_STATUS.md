# F06：刷新后的草稿保存回执

2026-10-16，分支 `codex/f06-proposal-save-receipt-20261016`，从已核远端
`9baf4104b760e73af89b0b82a414611eaa0f1f89` 正常建立，功能祖先为
`89949bb26dc581ba6b18408569622a341baf2586`。开始前已核唯一活动写入者、
单一 worktree、干净工作区和远端祖先；不恢复 UNKNOWN 旧任务。
main 保持 `7383e502418f5f9fc0c540ef2072742359343d40`。

已定位 P2：编辑器在 p1 保存成功后，仅凭 `epoch == savedEpoch`、没有本地错误，
就一直显示“已加密保存在本机，可从待确认清单恢复”。另一 owner 更新为 p2、
确认消费或永久删除草稿后，刷新虽然取得新清单并正确禁止旧确认，却仍保留
上述恢复保证。根因是展示回执未与刷新后的权威清单绑定；本次不是 SQL/CAS 修复。

现在只在清单可用、非读取中且无读取错误，编辑器没有待处理/未确认输入时，
核对同 ID 唯一草稿的整个 `TaskProposal` 快照、binding、创建时间/时区及当前
标题/日期，才显示保存保证。清单缺失、快照变化、读取未知分别明确提示；刷新中
先撤下保证。若完整草稿仍存在、只是来源失效或待核对，则单独说明草稿可恢复、
来源不能确认。状态异常时关闭按钮改为“关闭并核对已保存内容”，不承诺保留。

当前标题、日期、旧 saved 快照与会话保持不动，不用 p2 覆盖用户正在核对的输入。
来源验证、迟到回调门槛、明确确认/拒绝、完整快照 CAS、持久存储及永久删除事务
均未修改。没有 schema、依赖、SDK、协议、网络权限或凭据变更。

新增两项主机 controller 回归：一项通过第二个实际 `SqlTaskProposalRepository`
owner 更新和确认，验证完整快照、输入保留、旧确认被拦及旧拒绝仍报 CAS 冲突；
另一项执行实际 `SqlPermanentErasureRepository` 至 DONE，在 entries/proof 缓存
尚未更新时核实草稿缺失、保存保证撤下、输入仍在且不能创建正式任务。既有来源
元数据/读取失败回归保留并增加对应保存状态断言。

新增两项 Compose 源码回归走实际 `AIyouApp`、`TaskProposalDialog` 和 controller，
覆盖窄屏/双倍字体下读取中、读取失败、来源单独失效、异版、另一 owner 确认及
删除结果发布后的真实文本和关闭按钮。展示测试使用受控持久端口；实际 SQLite/
AEAD 与实际永久删除由主机测试覆盖。原有断言及方法均保留，验收脚本只追加
两个精确方法名，未降低原门槛。新增 Compose 本地未运行。

独立只读源码审查通过，没有独立执行构建。审查确认完整快照/输入 ACK 门槛、
状态区分及输入保留，SQL/CAS 未改；Compose API、全局 Dialog 插槽与静态路由
未发现阻断。作者专项实际 XML 为 11/11，0 failure/error/skip。

作者完整主机合约 **693/693**（AI 178、domain 46、SQL/VM 469），使用
`python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --offline --rerun-tasks`
实际重新执行，XML 无 failure/error/skip，完整方法库存及测试前输入指纹已验证。
指纹为 `6cd64bf2fff31a9c63536817fc9f26d8747725b9093e85cd76ad9f4f0c60f271`，
原 XML、执行前捕获和验证报告已在队列运行前单独复制保全。队列完整重跑
**149/149**，实际 XML 与必需精确方法名核验通过；队列后再次核验主机完整
方法库存及相同指纹通过。Python **123/123**、tokens 与已解析 HTTP JAR 校验
通过。本次实际测试无失败重试。主机平台外壳/SQLite 测试适配器不能替代
Android、Room/SQLCipher 或真机验收。

本地 SDK 只读预检仍失败：`ANDROID_HOME` 未指向已有开发者许可的 SDK。
未安装 SDK、接受协议或重试已知受限路径。AAR metadata、assembleDebug、lint、
Compose、Preview、instrumentation、原生 SAF/Keystore/SQLCipher、设备重启、
TalkBack、视觉和性能均本地 NOT RUN；没有新 APK，CI 不等于真机验收。

保留 [精确 CI 证据与缺失材料](F06_SOURCE_TASK_CI_FIX.md)：`5c5b6b9…` 的
run `38855242541` / job `116825118552` 已知 Compose 第 18 步失败，原日志
`BlobNotFound`，新失败用例/根因未知。P2 是独立已授权的源码修复，不能认定它
就是该 CI 的根因。`89949bb…` 的精确 CI 仍未知；不绕过 Actions 访问拒绝，
不另行触发 workflow。旧 94/96 两图片包 SQLite 初始化/退出超时、c43561f 未知
步骤 CI 失败及先前 SQL 测试适配器失败日志继续保留，不被本次结果覆盖。

证据目录：`/workspace/aiyou-evidence/f06-proposal-save-receipt-20261016/`。
仅合成 LOCAL_ONLY/AI_NONE 数据；原始两份方案未改，没有真实模型调用、自建
后台、费用、凭据、权限变更或部署。

后续 CI 状态更正：父级已核 `c8e05bc…` 的 run `38859223699` / job
`116837464919` Android 编译失败（MainActivity 第 889 行 `key` 未解析）；
`5c5b6b9…` 的 Compose 第 118 行也已定位为 LazyColumn 离屏来源按钮查找问题。
上述“根因未知”保留为当时状态；两处最新定位、修正及验证限制详见
[后续 CI 定位与修正](F06_SOURCE_TASK_CI_FIX.md)。P2 保存提示的源码审查通过不能
替代 Android 编译/Compose，新修正仍需新 SHA 实际结果。

再后续精确证据：`1f15551e495268cdee710444068960dbd946e756` 的 run
`38862463368` / job `116847653927` 已构建 debug APK，`key` 编译问题关闭；
208 项 Compose 仍有 1 项失败，定位到冷恢复草稿打开按钮尚未被 LazyColumn
组合时的测试查找，instrumentation 编译及 lint 跳过。该定位已按现有列表滚动
模式修正并独立源码审查，冷恢复与另行确认断言全部保留。新 SHA 实际验证仍待
完成，详见 [精确 CI 与冷草稿定位修正](F06_SOURCE_TASK_CI_FIX.md)。
