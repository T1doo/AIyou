# D06 本机出处选择/确认：主机证据

本片基于已核 `4785176f900f90c428a03e7477fdf4d05e70c459`，新增 16 个唯一 JVM 方法。
[原始证据包](d06-evidence-selection-host.zip)包含最终完整 XML、原始输入 capture、
逐方法清单、verified report、源码快照及原始命令日志，不依赖环境共享或 Actions。
最终包大小、哈希、完整测试数和指纹以本文件末的实际回执为准。

复用既有离线校验器，不更改或放宽规则：

```bash
python3 scripts/verify-image-grid-evidence.py docs/evidence/d06-evidence-selection-host.zip --repo-root .
```

`closure/` 为最终一次完整执行的唯一统计 cohort：原始 XML、三模块输入 capture、
独立方法清单、report、原始 ns 文件时间和全部捕获源码字节。测试执行时 HEAD 仍为
基线、行为变更待提交；输入指纹绑定实际源码，提交后再核对最终 Git blob，不以 HEAD
单独声称干净树构建。`method-delta.json` 比对已核基线 925 与本轮新增 16，不重复累计
专项 15 的运行；完整回归中的 16 包括加强后的原15及新增迟到 inspection 用例。

实际命令：

```bash
python3 scripts/jvm-gradle.py -p checks/ai-protocol :remote-text:test --tests '*LocalEvidenceSelectionTest'
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --rerun-tasks
python3 scripts/verify-ai-protocol-results.py
python3 -m unittest discover -s scripts/tests
python3 scripts/check-sdk.py
python3 scripts/inspect-device-test-environment.py
```

首轮专项 15/15 PASS，XML/输入 capture 与完整日志保留在 `logs/`；没有意外失败重试。
本轮 Python 151/151 实际复验 PASS、无新增方法，日志在 `current-python/tests.log`。
队列149本轮未重跑，仅历史；为复用既有校验器，`historical-queue/` 与
`historical-python/` 从已核基线包原样复制，其历史 Python151不能与当前151相加。

新测试覆盖默认空选择、非空显式确认、子集/排除、身份/receipt 伪造和 ABA、精确
码点/字节合并预算、日期/版本、混合来源逐源失效、撤权恢复、输入/筛选/账号切换、
确认和 inspection 的迟到回调、控制器隔离、读取/提交失败及原单源 consent 边界。
“provider”只指内存记录器；无 HTTP/真实模型调用。

只读 SDK preflight 实际 FAIL，原日志与库存保留；没有新许可/配置/通知权限/APK。
Android、Room11/SQLCipher、Compose 编译/交互、Preview、设备、200% 字体/TalkBack/
返回位置/性能均 NOT RUN。两项新增 Compose 源码用例未执行；新切片独审待上层。
完整 D06/真实多源发送仍 OPEN，没有网络请求类型或转换器。新 CI 未知，未访问 Actions。

## 最终实际回执

完整 JVM **941/941 PASS**（AI178、domain71、SQL/controller692），17任务实际执行，
4分50秒，原始 XML 全部0 failure/error/skip。新增16项包含于941，已核925无移除。
精确方法清单与执行输入门 PASS；三模块 capture 指纹一致：
`a4caacefee218712e65289e31bfae875b478bece54cfa9a05d5932fae365e3dd`。
Python151/151本轮实际复验；队列149未重跑，仅历史。

证据包 **1,677,617 字节**，490个 ZIP 成员（489项 manifest 文件），359项
捕获源码输入。ZIP SHA-256：
`0a8296feeb47e84cf5c5f4fe407531fbc972151ff80f3d8507a5eb9b839c22bf`。
复用既有离线校验器实际 PASS，完整哈希、原始XML/capture、report、方法集、时间与
源码逐字节一致；提交后再次对齐最终 Git blob。SDK只读前置FAIL日志完整保留。
