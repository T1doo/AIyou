# D06 合成回答与 inspection 提交边界：主机证据

基于独审基线 `700d6ab78a13f20446b6ebca9a2fe75a4b3f9d42`，新增21个唯一JVM方法：
inspection边界3项、回答18项。[原始证据包](d06-selected-answer-host.zip)归档原始XML、
input captures、完整方法清单、report、源码快照和命令日志，不依赖环境共享/Actions。
最终完整计数、源码指纹及包哈希见本文件末实际回执。

```bash
python3 scripts/verify-image-grid-evidence.py docs/evidence/d06-selected-answer-host.zip --repo-root .
```

复用原离线校验器，不放宽规则。`closure/` 只有最终完整执行的单一统计cohort：
原始XML、三模块capture、独立方法清单、report、原始ns时间和全部捕获源码字节。
测试实际HEAD是基线、源码当时待提交；指纹绑定工作树原字节，提交后逐项比对Git blob，
不以HEAD单独声称干净树构建。`method-delta.json` 对齐已核941及新增21，旧方法无移除。

实际命令：

```bash
python3 scripts/jvm-gradle.py -p checks/ai-protocol :remote-text:test --tests '*LocalEvidenceSelectionTest'
python3 scripts/jvm-gradle.py -p checks/ai-protocol :remote-text:test --tests '*LocalEvidenceAnswerTest' --tests '*LocalEvidenceSelectionTest'
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --rerun-tasks
python3 scripts/verify-ai-protocol-results.py
python3 -m unittest discover -s scripts/tests
python3 scripts/check-sdk.py
python3 scripts/inspect-device-test-environment.py
```

边界阶段专项19/19、合成回答及边界专项37/37均实际PASS，XML、输入capture和日志
保全于`logs/`，不能与最终完整执行重复累计。没有意外失败重试；SDK只读前置FAIL
日志与库存保留，没有安装或接受许可。测试注入的commit/read/consumer故障是PASS
回归场景，不是Android或真实服务故障。

`current-python/tests.log` 是本轮Python151/151实际复验，无新增方法。
队列149本轮未重跑，仅历史；`historical-queue/`、`historical-python/`从已核基线包
逐字节复制供既有校验器检查，其历史Python151不能与当前151相加。

主机执行完整选择确认→提交后合成消费→有界多源回答→引用校验→提交后可见发布→
SQL原文resolve，使用实际控制器/matcher/生产SQL及私有proof，provider只有内存中性
合成资料，不调用HTTP、密钥或真实模型。旧单源协议/ConsentGrant边界未扩大。
inspection明确分两阶段，false前绝不交packet，消费异常向上传递；不得接网络/文件
副作用，提交后撤权不能撤回已经读取的字节，后续结果/原文打开须重新核验。

真实Activity没有fixture；完整产品D06、真实多源发送/授权/订阅/提交仍OPEN。
Android AAR/APK/lint、Compose编译/交互、Room11/SQLCipher、Preview、真机、返回位置、
字体/TalkBack及性能均NOT RUN；新增3项Compose源码用例未执行，无新APK/许可/权限。
新CI未知。上层新增已核网格CI通知按各自提交记录于[状态说明](../D06_SELECTED_ANSWER_STATUS.md)，
没有具体原因，未访问被拒Actions或替代路径。新切片独立验收待上层。

## 最终实际回执

完整 JVM **962/962 PASS**（AI178、domain71、SQL/controller713），17任务实际执行，
5分54秒；原始XML全部0 failure/error/skip，已核941全保留，新增21包含于962。
三模块capture与精确输入门一致，指纹：
`aff4f3593fe4c5db43194e5e33d3d78063e4aa14b8fce86b95a572fa60579b64`。
Python151/151本轮实际复验、无新增；队列149仅历史。

包大小 **1,696,282 字节**，499个 ZIP 成员（498项 manifest 文件）、
362项捕获源码输入；ZIP SHA-256：
`87fe16d87b3fee3409e68869e92f2e7c079585d322e8d896de24aee67f86834a`。
既有离线校验器PASS，原始XML/capture/report/方法集/时间/完整哈希与源码字节匹配；
输入包含未编译Android源码用于绑定，不代表Android编译。提交后再逐项核对Git blob。

执行通道对完整运行曾返回exit=1，但Gradle原日志为BUILD SUCCESSFUL、shell捕获EXIT_CODE=0。
`logs/runner-observation.json`保留该不一致；全部962个原始方法及输入清单独立复核通过，
未将通道回执异常归因于代码或CI，未作推测性重跑。原始完整日志原样保留。
