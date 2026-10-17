# 图片网格主机运行证据

[下载完整证据包](f05-image-grid-host-evidence.zip)（3,423,424 字节）。该文件随开发分支
提交，可经普通 Git 取回，不依赖环境共享或 GitHub Actions。

ZIP SHA-256：`36b79803159a473403ef31c17fe2ad1f6dbae5d304e2ad1d11815be035b7d492`

本轮测试源码输入指纹：
`fa00d7d23841b47438d0d715cbc857a0f195bcb533122f5ab526df491b45a14b`

## 离线核验

在仓库根目录运行；只使用 Python 标准库，不解压到磁盘、不联网、不需要 SDK：

```bash
python3 scripts/verify-image-grid-evidence.py docs/evidence/f05-image-grid-host-evidence.zip --repo-root .
```

也可取出包内 `verify.py`，对 ZIP 运行；省略 `--repo-root` 时仍会核对包内原始证据和
源码快照。仓库参数额外逐字节核对本轮捕获的源码输入与当前 checkout。

已实际执行并 PASS。校验包括完整文件清单／SHA-256／大小、源码输入指纹、capture、
原始 XML 与 report 中的 XML 哈希、零失败／错误／跳过、源码／实际／report／单列清单
之间的精确方法集合，以及保存的原始 XML 与 capture 文件时间先后。
另用四份测试副本在重算外层清单后分别篡改 capture、方法清单、源码及 XML 方法名
（连同 report 内 XML 哈希一起修改）；四者均被语义校验拒绝。原包未修改。

## 包内分组

| 分组 | 内容 | 唯一方法数 |
| --- | --- | ---: |
| `baseline-full/` | 原完整运行的原始 XML、capture、预队列 verified report、方法清单与源码输入快照 | 907 |
| `baseline-after-queue/` | 上一轮最终 report 与队列重跑后更新的 18 份 AI/domain XML、保留的 remote-text XML 精确配对 | 907 |
| `closure/` | 本轮完整重跑的原始 XML、capture、verified report、方法清单与源码输入快照 | 911 |
| `historical-queue/` | 旧 149 项原始 XML、日志和质量门结果 | 149，历史 |
| `historical-python/` | 旧 151 项 unittest 原始日志 | 151，历史 |

这些分组不能相加。第二组不是新覆盖；本轮 911 为既有 907 加新增 4，网格专项
16 项已包含其中。149 队列和 151 Python 本轮没有新增用例、未重跑，不重复累计。
每个完整运行分组包含 `verified-report.json`、`methods.json`、`captures/*.json`、
`xml/<module>/TEST-*.xml` 和 `file-times.json`。capture 与 XML 保持原字节；纳秒文件时间
另存，因为 ZIP 时间精度不足。两组基线 report／XML 均保留原样，未改写成新运行。

report 保留实际执行时 Git HEAD。当时有待提交的测试源码，执行 HEAD 不能独自证明
干净树构建；完整源码输入字节、指纹和实际 XML 方法集合绑定测试输入。本轮提交后
再次运行生产 `verify-ai-protocol-results.py`，核对当前相关源码指纹仍与 capture 一致。

## 实际执行及边界

本轮命令：

```bash
python3 scripts/jvm-gradle.py -p checks/ai-protocol :remote-text:test --tests '*CurrentImageGridControllerTest' --tests '*CurrentImageGridQueueTest'
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --rerun-tasks
python3 scripts/verify-ai-protocol-results.py
```

专项 16/16 PASS；完整 AI178、domain71、SQL/controller662，合计 911/911 PASS，精确方法
和输入指纹门 PASS，无失败／错误／跳过。本轮无失败重跑；既往首轮夹具失败 XML、
失败／修正日志和 SDK 前置失败日志仍原样放在 `logs/`。

真实生产 ViewModel 的 Channel/IO actor 与公开保存、回收站控制器已执行；保存记录的
SQL 适配、生命周期、附件字节与图像解码使用明确的主机测试边界，查找证明与回收站
事务为生产 SQL。它不证明 Android、Room／SQLCipher、Compose、真机、多行原文返回
位置、窄屏大字体或快速滚动内存峰值。SDK／通知权限未批准，不配置；无新 APK。
本轮提交的 CI 状态未知，未访问 Actions。新证据交上层独立运行签核。

据上层从 GitHub 通知原邮件核实，旧提交
`01584dd7df71d9ffa3754ffa2555fe4580c6abc0` 的 D01 run `38980866184` 于
2026-10-16 23:43:47 UTC 失败，4 annotations，未给出原因。该结果不属于
`cd7108e06a96650f64da135a69e78f267d4c7b59` 或本轮提交；不归因于旧 height／分包
问题，不据此盲改或重跑，也不访问被拒绝的 Actions／日志／artifact 或替代路径。
