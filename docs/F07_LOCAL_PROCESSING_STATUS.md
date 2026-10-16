# F07：详情逐条本机处理范围（2026-10-16）

开发分支 `codex/f07-local-processing-20261016`，基于已核验远端
`3b5bed96a10f7164abbaa216342f9a0360abcd15`，该提交是已独审
`d1231dc962940d39b95b9088b344c292a67c8683` 的后代。
`main` 保持 `7383e502418f5f9fc0c540ef2072742359343d40`。
原始 Master Plan 与 Product Architecture 文件未修改。

详情新增“处理范围”，只提供这条记录的 `AI_NONE ↔ LOCAL_ONLY`。
先读取当前范围，再单独选择、确认；取消不写入。不提供远端授权。
图文、纯图和纯文字记录均使用相同入口。导入与回收恢复默认 `AI_NONE`
保持原样，改变这一条记录不会改变默认策略。

`EntryRepository.localProcessing` 借用现有加密存储 owner，SQL 不增加表或迁移。
确认请求绑定来源、版本、正文哈希、旧策略、generation、目标和 mutation ID。
一个事务完成 CAS、现有触发器的派生失效、清除 connection/consent、精确回执。
原文、不可变版本、附件、收藏、人工标签（包括此前明确采纳的原文标签）、人工
待办和草稿保留。`AI_NONE` 仍支持人工浏览与原文词法查找；这不触发 AI。

两次切换也会递增 generation，即使原文版本和最终策略相同。旧摘录、来源标签、
来源记忆、搜索和事实回顾 proof 失效。VM 刷新 generation 元数据，退役旧私有
预览及确认，阻止未观察中间态的 ABA；读取失败时关闭派生入口，保留原文浏览。
再次允许本机处理不会自动恢复旧结论或生成新候选。

保存 ACK 或读回不明时不显示成功，保留同一不可变请求供核对重试。只有当前
来源仍是精确提交结果才承认回执；后续切换、编辑、回收恢复或永久删除 fence
均拒绝旧请求。关闭窗口退役其回调，不承诺撤销已提交的事务；重新打开读取实况。

独立只读审查通过，发现并修正了 Compose ambient 引用命名问题；审查本身未
执行构建、未访问 Actions、未修改文件。四项新 Compose 测试使用实际详情路由、
Dialog 和控制器，覆盖窄屏双倍字体与触控尺寸、两方向明确确认、取消、未知读回、
同请求重试、恢复/导航后的旧回调、私有状态不进 Bundle、纯图记录。
四个方法均已加入 `verify-test-results.py --ui` 的精确方法清单。

本地完整主机回归 **645/645**：AI 178、domain 42、实际 SQL/VM 合约 425；
逐项 XML 与源码方法清单、执行输入指纹均通过验收，0 failure/error/skip。
队列 **148/148**、Python **123/123**、生成 tokens 检查与 HTTP 依赖校验通过。
这些结果包含 6 项新 SQL 测试、4 项新实际 VM 测试，以及新增的实际 VM 同版 ABA
测试；不把尚未运行的 4 项 Compose 测试计入本地通过数量。

本次故障保全位于当前环境
`/workspace/aiyou-evidence/f07-local-processing-20261016/`：

- `red.log` / `red-xml/`：实际 VM 同版 ABA 复现，6 测 1 失败。
- `targeted-1.log`：刷新先发布 entry 后核 generation 的竞态，10 测 1 失败；
  已修正发布顺序，`targeted-2.log` 通过。
- `targeted-3.log`：14 测 1 失败；故障注入钩子误命中打开后的元数据事务，
  已限定至被测 SQL 写事务，`targeted-4.log` 15 测通过。
- `queue.log` / `queue-xml/`：真实队列回归；`python.log`：Python 123 项。
- `full-host.log` 与最终 XML/指纹验收回执记录实际完整范围。

SDK 预检失败：无 `ANDROID_HOME` 指向已接受条款的 SDK。有界离线 wrapper
尝试在 Android 插件初始化时因 `/home/agent/.android` 只读而失败，四个 Android
任务尚未执行（`android-attempt.log`）。未更换受限路径、安装 SDK 或接受条款。
本次 Compose、AAR metadata、assembleDebug、lint、Preview、新 APK 均未在本地
完成，必须等待精确新 SHA 的 CI 证据。schema 的 `--verify` 也因缺少实际 Android
生成回执而失败，不能作为生成验证通过的证据；本次未变更 schema。

用户转交祖先 `3b5bed…` 的 CI `38838169390` 成功，193 Compose、394 Preview、
构建及 lint 通过；这是转交的祖先证据，不代表本次提交通过。本任务未查询
Actions、下载 artifact 或更换访问路线。正常推送仅触发现有有界 CI，不额外
调度或重复运行；新提交 CI 状态在访问限制下须保留为未知。

历史 **94/96** 中两项图片包 SQLite 初始化/退出超时原因仍未确定；旧 c43561f
未知步骤 CI 失败也保留，后续成功不抹去这些事实。原生 SAF、Keystore、SQLCipher、
设备重启、TalkBack、真机视觉与性能均未执行；主机测试和 CI 不是真机验收。
全部测试使用合成数据，无真实模型调用、权限扩张、新费用、凭据或部署。
