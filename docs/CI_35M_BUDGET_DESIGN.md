# Android CI 有界预算：保留完整工作负载的最小修订设计

状态：修订设计待独审，未改 workflow、脚本、测试或产品，未启动 CI。
产品基线：`2645adf747ba210df8658d1c8e9aa1206dcec1c2`。
原运行：[39531711787 / job 118998885225](https://github.com/T1doo/AIyou/actions/runs/39531711787/job/118998885225)。
本版替代 `890e922495e65515ad77b5d19ba039b04c2505a2` 的 Compose 去重提案；原提案保留在 Git 历史，不实施其 XML 提取器或拆步骤改造。

## 结论与证据来源

父侧正常云浏览器核实原 run 整体 cancelled，注解为“The job has exceeded the maximum execution time of 35m0s”，
页面总时长36m6s。各验证门、归档及后处理均成功，唯一可见 skipped 为旧 lazy-node 独立复现。
原 run 继续标 cancelled，不将局部成功或归档成功当作整体 green；没有设备执行。
根侧 CLI401 已停，无认证或 Azure 下载重试；以下后段证据来自父侧页面转录。

| 步骤 | UTC 时序 / 页面耗时 | 已知结果 |
| --- | --- | --- |
| Preview 完整性守卫 | 02:46:58 → 02:47:05，7s | 394 tests / 394 PNGs，失败/错误/跳过0 |
| PNG 分包 | 02:47:05 → 02:47:18，13s | 10 ZIP，均 ≤17 MB |
| 逐包上传 | 02:47:19 → 02:47:27，8s | 全部 finalized |
| schema/lock 导出 | 02:47:27 开始，02:47:53 打印 D02_GENERATED_STATE，步骤52s | success；约02:48:19结束由耗时推算 |
| test/lint reports | 02:48:19 开始，02:48:24 finalized，页面步骤6s | 976 files / 94,650,822 B |
| debug APK 归档 | 02:48:25 → 02:48:26 | 19,591,541 B，success |
| D02 APK 归档 | 02:48:26 → 02:48:27 | 7,730,840 B，success |
| Activity APK 归档 | 02:48:27 → 02:48:28 | 411,141 B，success |
| Preview 最后归档 | 02:48:28 → 02:48:32，约4s | 788 files / 89,339,656 B，finalized |
| setup-java / checkout 清理 | 02:48:32 → 02:48:33 / 02:48:33 | success |

保存的 job 起点为02:12:28，名义 job-start+35m 为02:47:28；Preview 守卫完成时只剩23秒，
之后已观察的正常保全链持续87秒。最后归档结束比该名义截止晚64秒，尚不计后续清理。
这说明既定完整工作负载及必要收尾没有被原预算充分覆盖，不能解释成最后上传挂住。
schema 的52秒是已观察的步骤耗时，不能据此认定全为 Python CPU、网络或日志卡顿。
精确取消信号到达时点未知；上述名义截止算术不等同于调度器取消时点。

## 建议：单作业上限从35调整为40分钟

父侧已明确35分钟是当前工程配置，并非已找到的用户明示硬限，可审查合理的有界裕量。
建议只改 `jobs.d01.timeout-minutes: 35` 为 `40`，并更新原29m54s旧测量注释。
40分钟相对本次页面36m6s留3m54s余量，约为该次时长的10.8%；上限增加5分钟，仍为单个有界作业。
不承诺所有波动都能在40分钟内完成；这是依据已完成工作负载提出的预算修订，不是性能测量结果。

拟补丁仅包含这一语义配置及其事实注释：

```diff
-    # Actual v13 bootstrap plus evidence preservation measured29m54s.
-    # Budget the whole job; individual test deadlines and all gates stay fixed.
-    timeout-minutes: 35
+    # Run39531711787 took36m6s including evidence/post steps; prior35m bound cancelled it.
+    # One bounded40m job; individual test deadlines, resources and all gates stay fixed.
+    timeout-minutes: 40
```

保留原单 ubuntu-24.04 runner、JDK/SDK/依赖 pins、2GB heap、2 workers、job数量、权限及归档保留期。
不启用付费能力、更高规格 runner、额外并行 job、SDK 协议、凭据或自动重试；普通免费 CI 的既有授权保持。
实际工作不新增：同一批测试、构建和归档按原顺序执行，新增的是允许它们和收尾完成的有限时间窗口。

## 保持全部测试门和证据结构

Room/schema、完整 domain/wire、queue、Python 合同、受控原生、trace、SDK/环境检查、Android metadata、
组件/密文、APK/INTERNET、四轮筛选与完整 Compose、instrumentation 编译/DEX、license/ZIP、lint、
Preview 三道门与所有 XML 守卫均不改；旧 lazy-node 条件复现也不改。
全部 schema/lock、test/lint reports、三类 APK、394 PNG、INDEX、有界 ZIP、上传 action pins、
17 MB 上限及14天保留期均不改。PNG 自定义逐包上传本来已 compression0，不提出虚构的压缩修复。
不删除52秒 schema 导出或其他证据步骤来换取整体绿灯。

原 Compose 去重提案可减少重复工作，但需要新增完整类/专题 XML 选择与失败保全逻辑：
搜索类11项中选2项尤其需要额外守卫。当前完整工作负载已全部成功，预算仅超出工程配置约1分钟，
没有必要先承担这些测试编排改造风险。本案暂停去重、提取器、归档压缩和跨 job 拆分；不将其混入超时修订。

## 审查与实际验证路径

本轮只设计：没有 workflow 实施、测试执行、推送或 hosted CI。
设计获审后，最小实现只修改上述 scalar 和注释，核 YAML/精确 diff、全部门/条件/资源/产物路径未变，
不为一个可逆预算配置编写镜像单测，也不在本机重跑已通过的合同测试或缺 SDK 的 Android 路径。
实现差异可再供父侧限定核对；随后按既有授权正常推开发分支，让现有免费 CI 对新精确 SHA 验证一次。
不再要求用户重复批准普通免费 CI，不盲重跑旧 cancelled SHA，也不把本次已过步骤借作新 SHA 的 green。
若新运行仍超时或有失败，保全具体 job/step 时序后定位；不自动续跑或继续提高预算。
CLI401 未恢复时直接给准确 job/step 链接，由父侧正常页面核实，不将日志下载拒绝作为反复阻塞点。

本地 Android/Compose、设备、真实 SAF/音频/权限、SQLCipher 原生、16KB、可访问性与性能仍 NOT RUN。
