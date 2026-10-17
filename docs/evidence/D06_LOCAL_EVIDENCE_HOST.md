# D06 本机出处候选：主机证据

[下载原始证据包](d06-local-evidence-host.zip)（1,667,436 字节），随本开发分支交付，
不依赖环境共享或 Actions。ZIP SHA-256：
`d2720fee0ba75ce55381b6fe3c975234d17b97dacd3e4f82dc97774501e95211`

本轮源码输入指纹：
`94a65465aa287db2510f34d024790e03136a1e0b92c6f8c4476fb22482e43fc0`

复用已有校验器，不新增／放宽校验器规则：

```bash
python3 scripts/verify-image-grid-evidence.py docs/evidence/d06-local-evidence-host.zip --repo-root .
```

已实际 PASS：完整文件哈希／大小清单、357 项捕获源码输入、原始 XML 与 report 哈希、
原始 capture、源码／实际／report／独立方法清单的精确集合、0 failure/error/skip、
捕获与 XML 的原始文件时间，以及最终 checkout 与捕获输入的逐字节一致性。
`closure/` 包含 `xml/`、`captures/`、`methods.json`、`verified-report.json`、
`file-times.json` 和 `source-inputs/`，没有伪造／改写实际 XML。

实际执行时 Git HEAD 仍为基线 `fa42ab07f62af29ee8c22700b388e3c0a8073d61`，新增代码
当时待提交；测试绑定的是完整捕获输入和指纹，不以 HEAD 单独声称干净树构建。
提交后再次核验相关源码指纹与上述 capture 一致，Git blob 与证据包原字节一致。

## 实际执行

```bash
python3 scripts/jvm-gradle.py -p checks/ai-protocol :remote-text:test --tests '*LocalEvidenceControllerTest'
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --rerun-tasks
python3 scripts/verify-ai-protocol-results.py
python3 -m unittest discover -s scripts/tests
```

完整 JVM **925/925 PASS**：AI178、domain71、SQL/controller676。新增 14 个出处候选
方法，已包含于 925；已独审基线 911 方法完整保留，没有重复累计专项运行。
`method-delta.json` 给出完整新增方法清单。专项首轮 12 中 2 项夹具断言失败，原
XML／日志保留于 `logs/`；修正后的 14/14 及最终强化 join 的完整回归均通过。

本轮 Python **151/151 实际复验 PASS**，没有新增 Python 方法，原始日志位于
`current-python/tests.log`。历史 149 队列本轮未重跑；`historical-queue/` 和
`historical-python/` 从已核 Grid 证据包原样复制，供既有校验器核对历史数据，
其打印的历史 Python 151 与本轮 151 不能相加。包内 `manifest.json` 分别注明这些范围。

## 范围与未过门项

复用已有词法／全部筛选及私有检索 proof，以最多 5 条、1200 个引用码点生成仅本机
候选；不生成答案、不理解自然语言语义、不发送模型。字面查找失败、不合法问题与
真正无可引用证据分别显示。来源保留原日期、revision、精确引文和码点范围，点击
走既有当前来源租约及原文定位。引文预算不等于进程堆上限；原检索有界预算不变。

本机来源包不依赖单源模型发送合同。远端多源问答仍需模型配置、逐源当前授权及
多源引用／提交合同，未过门不会启用；完整 D06 仍 OPEN。
Android AAR／APK／lint、Compose 编译／交互、Room11／SQLCipher、Preview、真机、
返回位置、窄屏大字体与性能均 NOT RUN；2 项新增 Compose 源码用例仍受原精确门禁
约束。没有新 APK、SDK 接受、通知权限、凭据、模型调用、schema 或依赖变化。
新提交 CI 未知；旧01584 D01失败原因未知，不归因或盲改，未访问 Actions／日志／artifact。
