# D04 transport 两阶段关闭

本切片接续 `4cd5fcbe670015e828755a15925dfcd2c2ebba78`，修复 transport 逐个调用 source exchange 的 `cancel()` 时，第一项同步数据库清理阻塞后续 HTTP 取消的问题。持久撤销边界沿用 [D04_ACTIVE_HTTP_REVOCATION.md](D04_ACTIVE_HTTP_REVOCATION.md)。完整 D04、App 生命周期接线及 D02 原生设备验收仍 **OPEN**。

## 关闭与归属

`close()` 在短 owner 锁中标记关闭并取出自有 exchange 快照，随后先停止全部快照项的 HTTP 和订阅，释放本 transport 自建的连接池与 dispatcher，最后逐项尝试持久清理。SQL 不在 owner 锁中运行，也不能阻挡其他快照项进入网络停止阶段。每个 transport 从注入 client 派生独立 dispatcher 与连接池，关闭仅处理本实例资源。

持久清理仍在 close 调用线程同步执行，不新增线程或遗留异步清理。数据库锁或平台 IO 仍可阻塞 close 返回，因此没有 close 完成时间上限。重复 close 立即返回，不等待首次清理完成；它不是清理已完成的凭据。

普通失败不阻止后续自有项的停止或清理，不输出原始异常内容。`VirtualMachineError`、`ThreadDeath` 和 `LinkageError` 的首个原对象保留，在全部停止、资源释放和持久清理均尝试后交回调用者。无法访问的存储仍不能保证成功写入终态，不能将“已尝试”视作持久化成功。

已准入消费回调可以结束；关闭后不准入后续预览，也不能借协议完成绕过新鲜持久检查和原子 commit。SQL 取消只清理本 job 的未完成状态，不删除或改写已 COMMITTED 的候选。新建、执行和显式取消的既有授权入口与规则保持一致，默认仍为 `LOCAL_ONLY`。

## 合同与证据

新增 `SourceBoundTransportCloseContractTest` 的四个实际 JUnit 方法；协议及故障矩阵不额外计数：

1. 同一实际 SQLite 文件的第二连接进程持有真实 writer 事务，两个自有 loopback call 先进入 ADMITTED。450毫秒观测窗口内，两项 call、仍持有的 socket 及订阅均停止，首次 close 仍等待 SQL；重复 close 返回。释放 writer 后，三个层级均为 Cancelled，两 job 持久为 CANCELLED，原文不变、候选为空。450毫秒是测试观测预算，不是生产关闭 SLA。
2. Started 或 Text 消费回调在 SQL 外暂停，另一自有 call 同时活动。close 可结束，已准入回调可返回，精确类型和文本事件序列无追加，最终不保存候选。
3. 实际 SQL 取消成功后注入普通或致命故障。执行 owner 的清理在 SQL 外等待 close 完成，防止 owner finally 补清理掩盖 close 缺陷；逐项断言 close 调用线程尝试两个 job。两个持久状态一致，致命故障对象原样传播，重复关闭安全，所有自有测试工作线程收尾。
4. 两个 transport 使用同一实际注入 client。关闭 primary 后，另一 transport 的活动 socket/call 和新请求继续；通过另一 transport 对 primary 数据库真实 COMMITTED 行恢复，零新 HTTP、零 secret 解析，完整候选行不变。

改动前在原基线上的锁屏障方法实际 **FAIL**：`Every owned call must cancel before the first durable cleanup can complete`，1方法/1failure，失败 XML SHA-256 `34adf94220d82aa20521332a2d86aee522683401d11849c281605c8e160e7438`。两阶段实现后相同方法实际 **PASS**，并加强到 source、guard、原始 HTTP 三层 Cancelled；通过 XML SHA-256 `80a9c8857e257eccdbf7ca6aa35339ef2e75a181b6ea573fccfa3cb57bd49857`。四方法首轮实际 **PASS**，XML SHA-256 `5e456a7744044b878812365cb38e0a5bcdad308e3cddc8eec8c86ab5c9e7166c`；独立复审随后要求第3项隔离 owner 清理并证明 close 自身尝试所有 job，最终执行证据以对应输入指纹和交付记录为准。

原失败、各轮实际 XML、运行日志及输入快照分别保存在被忽略的 `.local-evidence/d04/two-phase-transport-close/`，不提交日志、SDK、凭据或构建产物。开发分支正常提交与 push；最终完整 SHA、同 SHA CI、唯一方法数量及远端核验以交付证据为准。

加强第3项后的四方法重跑实际3通过、1失败：第2项的首次消费回调屏障未到达，尚未进入 close；失败 XML SHA-256 `cf08b14ba1f300f90d87c87e6f3ce32207f01b6a3b3f46e7cb089fdcc3b58c97`。现有证据不能确定首因。随后仅增加失败时的协议、暂停分支及执行结果诊断，保持原2秒预算和所有成功断言；限定第2项重跑实际 **PASS**，XML SHA-256 `d54eb9fea8999142e31403a90813189cb0a56f9193819a703d32d389f48e0f22`。没有把该轮失败改写为通过或据此改动生产授权行为。

最终冻结输入后，实际执行 `python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --offline --rerun-tasks` **PASS**，3分17秒，16 tasks全部实际执行。结果核验要求源方法清单与新鲜 XML 一一对应、无重复/缺失/跳过；14份XML实际 **250个唯一方法：AI178、domain13、remote-text59（原SQL28 + 原source-bound HTTP14 + 撤销合同13 + 本次关闭合同4）**，零failure/error/skip。92个输入文件指纹为 `aa89e489692decbae27b1974e0b864b5956ad0454a0992ec1427b0095e7ca75b`，完整日志SHA-256 `f8afd2548fd9264fba28790434b9c734ebf70c350e7b41d9eac817ce7617e5ba`，已解析HTTP依赖jar校验也 **PASS**。四个关闭方法均在该最终源码上重新实际执行，未沿用限定方法的XML。

独立只读审查未发现P0/P1；已补齐 close 线程逐 job 清理证据。实际 JDK17 `javap` 保持 source 桥原6业务入参，桥直接执行持久 repository 授权检查，没有 caller-supplied gate 或公开任意文本认证入口。Kotlin 的合成访问方法不是进程内安全沙箱。测试未覆盖原生 Room/SQLCipher、App 生命周期接线或设备 IO/调度性能。

## 原生边界

host SQL/loopback 合同不是 Room/SQLCipher 原生执行。原27业务方法与 storage/application 两个 probe 保持编译/DEX证据，设备运行仍 **NOT RUN**。本地无既有 SDK、adb、emulator、许可记录或 `/dev/kvm`，未安装或启动这些工具、未生成认证密钥。

最小设备恢复动作仍由平台在唯一已保存配置下接入已接受许可的官方组件和已授权设备或受支持加速 AVD，先只读核验身份、路径和状态。不得用 HOME、换状态目录、权限修改或挂载绕过已拒绝的目录限制。本切片没有扩大文件或安全权限。
