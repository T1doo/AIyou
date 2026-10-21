# 35 分钟 Android CI 预算：去除重复执行的最小设计

状态：设计待独审，尚未改 workflow、脚本、测试或产品；未启动验证运行。
产品基线：`2645adf747ba210df8658d1c8e9aa1206dcec1c2`。
原运行：[39531711787 / job 118998885225](https://github.com/T1doo/AIyou/actions/runs/39531711787/job/118998885225)。

## 已有证据与未确定项

父侧通过正常 GitHub 云浏览器核实整体 `cancelled`，总时长 36m6s，注解为
“The job has exceeded the maximum execution time of 35m0s”。不是用户取消，不能报告整体 success。
父侧已核 APK、INTERNET absence、Compose、instrumentation 编译/DEX/license/ZIP、lint、
Preview 生成/验证/完整性守卫及全部归档、后处理和 Complete job 均显示 success；
唯一可见 skipped 是旧 lazy-node 独立复现。没有证据支持“归档失败”。

此前保存的认证元数据与父侧页面时长可确定以下串行段，不能把它们重叠计数：

| 来源 | 步骤 | 耗时 |
| --- | --- | --- |
| 根侧 HTTP401 前保存的元数据 | Room 导出 / domain-wire / queue / Python 设计合同 / 受控原生 | 2m8s / 6m31s / 1m52s / 32s / 2m |
| 父侧页面 | APK / Compose / instrumentation 编译 / lint | 1m54s / 5m48s / 15s / 4m6s |
| 父侧页面 | Preview 生成 / 验证 / 完整性守卫 | 1m59s / 3m6s / 7s |

这些已知段已合计 30m18s，尚不含 setup、trace、环境检查、Android 依赖/组件/密文检查、归档及收尾。
现有单作业把全部步骤串行累加，预算余量不足；具体超时发生在主步骤还是 post cleanup 的哪一瞬间仍待页面时间戳。
父侧还在补 Compose 五次 Gradle 调用的各自耗时及最后主步骤/post 时间，不在缺证据时承诺能节省几分钟。
根侧 CLI 已因 HTTP401 停止；未重试认证、Azure 日志或 artifact 下载。

## 选择：同一作业只执行一次完整 Compose 套件

现有普通路径依次执行四轮筛选和一轮完整 `:app:testDebugUnitTest`：

| 筛选轮 | 方法数（源码静态核对） | 完整套件中的保全要求 |
| --- | --- | --- |
| StorageUsagePresentationTest | 2 | 原始类 XML、两个准确方法、全部成功 |
| MixedCapturePresentationTest | 8 | 原始类 XML、原八项清单、全部成功 |
| LocalOriginalSearchPresentationTest 两个高亮方法 | 2 | 保留完整类 XML；该类现有 11 方法，回执单独精确列出所选两项 |
| AppAccess 三个类 | 4 + 3 + 1 | 三个原始 XML、原八项准确清单、全部成功 |

这四轮的 20 个方法随后还在完整套件中执行。计划去掉四轮 `--tests` 调用，
保留一次未筛选完整套件以及现有 `verify-test-results.py --ui` 的全部门。
减少的是重复的测试、Gradle 配置与 worker 启动；没有减少需要执行的方法、断言或边界覆盖。
旧 `[ui-lazy-repro]` 条件复现路径独立保留，不能混同为这四轮普通重复执行。

拟将完整执行与证据保全分成两个步骤，仍在现有 `d01` 作业内，`timeout-minutes: 35` 不变：

1. 原 Compose 步骤保留其名称及环境，增加可引用 id，只调用一次完整套件。非零退出直接记为失败。
2. 后继证据步骤用 `always()` 在本次 Compose 已被尝试时运行。先保存本次已有的原始 XML 和来源摘要，
   再执行严格完整套件与专题清单核验；缺失、重复、失败、error、skipped 或不完整资料不能形成完整 PASS。
   原测试步骤失败/取消的结论始终保留，不使用 `continue-on-error` 或后续成功覆盖它。

专题目录和日志标记继续保留，回执明确写本次 `GITHUB_SHA`、run id、完整套件步骤 outcome、
原始 XML 路径/bytes/SHA256、原类实际方法数、所选方法完整身份和结果，以及“来自同次完整执行，未另跑”。
现有 recap、migration、consent、backup 等已从同次完整 XML 提取的证据继续保留。

搜索专题必须按两项名称及 classname 各唯一匹配，并确认完整类 XML 无失败/错误/跳过。
完整 11 项 XML 原字节保全，回执将 `source_cases=11` 与 `selected_cases=2` 分开；
同时核本版源类 11 项的完整身份清单，缺少未选的九项也不能声称完整类来源。
不能把 XML 改写成只有两项，不能只改原有 `==2` 数字而丢掉准确方法断言。
其余三组保留现有准确类/方法集合与零失败守卫；StorageUsage 两项也补齐回执中的明确集合。

失败路径先保存全部可读原始 XML，再汇总校验错误并以非零退出；即使某专题成功，
整体测试失败或部分 XML 缺失也只能记录其有限范围，不能标整套成功。
不下载旧 run XML、不重放 cancelled run 的报告、不用历史已过测试伪装本次执行。

## 本案保持不变的门与产物

Room/schema、完整 domain/wire、queue、Python 合同、受控原生、trace、SDK/环境检查、
Android metadata、组件/密文、APK/INTERNET、完整 Compose、instrumentation 编译/DEX、
license/ZIP、lint、Preview 三道门及全部 XML 守卫均保留。不得删测、放宽阈值或把 NO-SOURCE 算通过。
所有 APK、报告、schema/lock、394 PNG、INDEX、独立有界 ZIP 和原有归档步骤继续保留。
上传 action pins、14 天保留期、17 MB 分包上限、应用权限与 SDK/许可不变。
逐包 PNG 自定义上传已显式 `INPUT_COMPRESSION-LEVEL=0`，本案不假设它存在重复压缩问题。
其他归档压缩级别、测试超时、worker/heap/并行数、runner 数量和规格均不调整。

## 拆分与验证边界

本轮选择先在单作业去重复，不新增 job。拆分 host/Android 会引入冷构建、跨 job 报告来源校验、
共享 schema 和制品交接，并可能扩大累计 runner 预算；当前缺少证明其必要性和预算不增的计时。
若后续确有余量不足证据，再另案审查拆分，不能仅把两个 job 各设 35 分钟当作预算未增加。
不自动扩大 35 分钟，也不因 cancelled 就盲重跑所有已过门。

设计通过后的实现验证限定为：

- 对证据提取器做必要离线定向测试：完整搜索类 11 项而选 2 项，缺失未选方法也拒绝完整来源；四组准确方法覆盖；
  空/缺失/重复/失败/跳过/部分 XML 均拒绝完整 PASS；原始 XML bytes/hash 不被改写；失败 outcome 不被覆盖。
- 对 workflow 做静态守恒核对：普通 Compose 执行由五次变一次，原方法清单、全套验证器、
  条件 lazy 复现、所有构建/lint/Preview/归档门、35 分钟和资源配置均保留；输入输出来源明确。
- 本机缺少已配置的许可 SDK 与 AGP/Compose 缓存，Android/Compose 不重试、不安装、不下载。
  合成 XML 只验证提取器，不是 Compose 测试结果。
- 实现再独审后，才决定必要的配置验证运行及精确计时，不能以现有 cancelled run 或离线 fixture 宣称新配置 green。
  若实际收益不足，保全新计时再独审后续方案，不自动堆积重跑。

本设计没有实现优化、没有运行测试或 hosted CI；D03/D07 设备、真实 SAF/音频/权限、
SQLCipher 原生、16 KB、可访问性与性能继续 NOT RUN。
