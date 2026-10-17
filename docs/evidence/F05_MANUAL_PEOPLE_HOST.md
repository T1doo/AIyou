# F05 手工人物关联主机证据

开发基线 `cbaf2618f7d8c2cb6f85d6dd621c001944958ffc`，分支 `codex/f05-manual-people-20261017`。本切片仍待独立审查；[范围与原生阻塞](../F05_MANUAL_PEOPLE_STATUS.md)。

可携证据：[f05-manual-people-host.zip](f05-manual-people-host.zip)，1,787,679字节。SHA-256：

```
f171ea518ca30d0a8b61a0b8748dcec3b6c2ae30ee6641e52f4817a090b53369
```

最终执行输入指纹：

```
0bec2e709de81535b80f8d6a9a5411c75c86a243466c29ce65410f7eeda7eea2
```

ZIP有539个成员，manifest列出538个文件的原始哈希和字节数，包含382份执行输入源码、全部原始 XML、纳秒时间、输入捕获、精确方法清单、报告、早期失败日志与本轮 Python 日志。使用未修改的既有验证器：

```bash
python3 scripts/verify-image-grid-evidence.py docs/evidence/f05-manual-people-host.zip --repo-root .
```

也可仅下载ZIP及其中的 `verify.py` 离线校验，不需提取、SDK、网络或新增依赖。`--repo-root` 额外要求本地源码原字节一致。执行时 HEAD 为尚未提交工作树的已接受基线；不能以这个旧 HEAD 代替输入指纹。交付再将所有382份捕获输入与最终提交 Git blob逐字节比较，并验证远端提交。

| 实际执行 | 结果与边界 |
| --- | --- |
| `python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --rerun-tasks` | PASS，5m12s，17个任务实际执行，shell退出0；AI178、domain71、remote-text739，共988个唯一方法；XML失败/错误/跳过均0 |
| `python3 scripts/verify-ai-protocol-results.py` | PASS，输入捕获指纹、XML哈希/时间和源码方法清单严格相等 |
| `python3 -m unittest discover -s scripts/tests -v` | PASS，本轮153项，25.252s，新增2项真实历史字节的 schema拒绝控制；合成协议测试不代表设备执行 |
| 人物相关定向执行 `:remote-text:test --tests '*ManualPeople*Test'` | PASS 26项；包含SQL11、备份恢复6、实际ViewModel7、实际App搜索控制器2；不重复叠加到988 |
| `python3 scripts/verify-ai-http-dependencies.py` | PASS，既有已解析依赖原始哈希不变，无新依赖 |
| `python3 scripts/generate-tokens.py --check` / `git diff --check` | PASS |
| 原有离线 ZIP 验证器 | PASS，988方法、源指纹、报告、原始XML、输入原字节一致 |
| `python3 scripts/check-sdk.py` | FAIL：`ANDROID_HOME must point to an SDK with developer-accepted licenses.` 未接受新条款、安装或配置SDK |
| `python3 scripts/verify-manual-people-schema.py --capture` | FAIL：真实已提交Room11导出/fixture缺失，生成12版前拒绝继续；未创建或伪造11/12版JSON |

`method-delta.json` 严格证明962个已接受方法无删除、新增26个。历史队列149、历史Python151从已接受基线ZIP原字节复制，仅供旧验证器核验。队列149本轮未重跑；本轮Python153单列，不与历史151或定向26重复计数。

新增覆盖：11→12产品SQL相对真实10版导出和产品11版SQL的保全、独立加密域/版本、提交丢ACK、CAS、版本/哈希/generation变化、AI_NONE、读取损坏显式失败、旧匹配与空结果及跳转失效、删除/恢复ABA、永久清理、用户输入保留和串行队列、关闭/换所有者后的迟到回调、仅人物筛选、当前源私有证明更新，以及真实加密包v6的当前/历史/事件日期组合、新副本恢复/回执重放/原子失败、明文导出范围和认证篡改拒绝。名称随记录，不冒充身份或历史版本事实。

失败保全在ZIP `logs/`：首轮新测试夹具的可空属性smart cast/匿名类型编译错误；第二轮24项中一项新增测试误将回收站计入永久删除；第三轮修正中测试误用结果字段名被编译器拒绝。已按既有契约修正，并核对回收状态及稳定副本ID，保留所有失败XML/日志。定向最终26和完整988均PASS。源码审查另外修正了仅人物条件误走BROWSE和缓存需比较人物快照身份的问题，用实际App控制器/SQL证明回归。

Room11→12/SQLCipher原生迁移、Android AAR metadata、assemble、lint、Compose、真Preview及设备/可访问性/内存性能均 **NOT RUN**。新增3项Compose、2项原生测试只有源码并注册精确方法门禁，尚未编译或执行。无新APK。主机驱动是测试用SQLite，平台是JVM替身，不能视作Android或真机验收。

真实Room11前置必须先在获准工具链从已接受11版源码生成、提交且核验，再在本分支生成真实12版并提交、另做精确HEAD CI。SDK新条款和通知权限未获准，未接受或配置。Actions此前拒绝访问，本次未访问也没有替代路径；01584、cd7108、fa42失败通知只归属各自提交、原因未知，较新分支及本次CI **UNKNOWN**。D06实际Activity仍无提供者，普通用户不能回答；引用存在不等于语义真实，本切片未扩展合成问答。

默认LOCAL_ONLY、原图读取/解码内存边界与原有来源跳转保持。无模型调用、新依赖/权限/凭据/费用/明文缓存。不改main、不强推、不部署。未知Git锁保留。
