# D07 R2b 五组 P2 修复验证收据（待再次独审）

产品冻结提交：`9b49bf831cad0d7e9980d9cada8bddddafc241de`。
修复基于已送审候选 `236d0d8f1ca2dc14bdca03c0019c4cc40775a55e`；
远端/设计基线仍为 `9072bb941000a5956c9229bb29b9372657aa8c70`。
五组原因、修复与平台合同见 [修复说明](D07_R2B_REVIEW_P2_FIXES.md)。
父级此前暂不 ACK；本提交尚无再次独审 ACK，不推送、不启动 CI、不接续新功能。

| 本轮实际执行 | 结果 |
| --- | --- |
| `python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test :remote-text:test --rerun-tasks` | PASS：178 + 71 + 1082 = 1331；17 tasks 实际重跑，5m41s |
| `verify-ai-protocol-results.py` / `verify-ai-http-dependencies.py` | PASS；方法清单、实际 XML、执行前输入及 HTTP jar 一致 |
| `python3 scripts/jvm-gradle.py -p checks/record-queue :test --rerun` | PASS：188；52s；`verify-test-results.py --queue` PASS；59 份生产源码副本逐字节一致 |
| `python3 scripts/jvm-gradle.py -p checks/reminder-native :test --rerun` | PASS：26；17s；仅 test 实际重跑，12 个依赖任务 up-to-date；实际编译副本与冻结源码一致 |
| `verify-reminder-native-results.py` / `verify-reminder-runtime-schema.py --verify` | PASS；完整方法/XML/源码指纹，受控主机 KSP v15 与提交、打包合同逐字节一致 |
| `python3 -m unittest discover -s scripts/tests -v` / `generate-tokens.py --check` | PASS：168 / Compose tokens 与 JSON 一致 |
| 精确提交 Android AAR metadata / APK / lint / Compose / Preview / CI | NOT RUN；待父级再次独审后按已授权顺序推进；本环境未安装 Android SDK，未接受新条款 |
| 真实权限、通知、闹钟、进程死亡/重启、首解锁、OEM、设备 SQLCipher、16 KB、性能 | NOT RUN；受控主机结果不是设备验收 |

全部 Gradle 回归串行执行。主机全量 XML、输入与收据先封存，再运行队列，避免共享 core 输出被覆盖。
主机指纹 `e807817410f35c5121adfcec1c7a7883cc4d753c1b6b05aaca8d3940d6c22aa6`；
受控原生指纹 `8d2e6a5c7d0eb326df8b67adf08746da702654292baa851a61f03d033a94ed2b`。
26 项由 adapter 6、receiver/Job 链 4、schema/日志 2、撤销失败 1、交接清理 6、停止代次 3、普通释放 2、未返回工厂失败 2 组成。

[本轮证据 ZIP](evidence/d07-r2b-p2-controlled-host-20261020.zip)：282169 字节，218 个条目，
SHA256 `37d71e89b880dcb8fb20841b9835c43b53ad135c2c74be3a8c229a6c3f2e7c26`。
逐条字节数、哈希及 ZIP CRC 已核对；`archive-manifest.json` 记录各项。
`final-*` 是冻结产品的本轮终态；red-1/red-2 与早期 green-handoff-1 失败保持原样。
早期容量测试手工清理掩盖问题、旧测试等待条件过早成立的原因见修复说明，不能将它们充作最终通过证据。
旧候选归档与旧 Library 文件保持不变；新的审查包会携带完整 Git bundle、基线差异和 P2 增量差异。

2026-10-20 13:23 UTC 普通 `git ls-remote` 确认远端开发分支为上述基线，
远端 main 为 `7383e502418f5f9fc0c540ef2072742359343d40`；远端开发分支是冻结产品祖先，产品提交领先 5、落后 0。
本环境没有名为 main 的本地分支；`origin/main` 与只读 work 基线均保持该 SHA。没有推送、改 main、强推或部署。
本收据及 ZIP 是后续文档提交，未更改受测产品代码；产品 SHA 与交付 HEAD 分别报告。

原方案文件保持原字节：Master Plan SHA256 `a9c647e0555cd57a4511e58506a4ad8516d5c329ada7bc5329cdd61839a54e9b`；
DOCX SHA256 `2d28784231148af980512bd87115bb1c1d243be0c3825f61b441934f76727c94`。
所有数据/角色均为合成；保持 LOCAL_ONLY、无自建后台、无真实模型调用、无新费用/凭据/权限/SDK 条款接受。
没有重试被拒绝的 Azure 下载。D03 ASR/OCR、D04 绑定和 D07 整体验收仍 OPEN。
