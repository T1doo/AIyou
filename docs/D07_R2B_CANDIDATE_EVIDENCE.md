# D07 R2b 候选验证收据（待实现独审）

产品提交 `ba7f27214ea04580eef2a466236c2f9c713b7e3d`；基线/远端开发分支
`9072bb941000a5956c9229bb29b9372657aa8c70`。
父级只对基线设计做过限定 ACK；本实现尚未取得独立实现 ACK，不能称 D07 完成。
本收据/证据归档不改产品行为。详见 [实现说明](D07_R2B_IMPLEMENTATION.md)。

| 实际执行 | 结果 |
| --- | --- |
| `:core:ai:test :core:domain:test :remote-text:test --rerun-tasks` | PASS：178 + 71 + 1081 = 1330；17 tasks 实际重跑，5m02s |
| 方法清单/XML/输入指纹、HTTP jar 校验 | PASS；失败/错误/跳过均 0 |
| `checks/record-queue :test --rerun` | PASS：188；源码副本逐字节核对，失败/错误/跳过均 0 |
| `checks/reminder-native :test --rerun` | PASS：13；receiver→JobService 四项、adapter 六项、撤销失败一项、schema/日志两项 |
| 实际主机 Room KSP v15 与提交/打包冷合同 | PASS；前置 v14 字节一致性对照，旧导出未改 |
| 独立 Node crypto fixture 重生成 | PASS：与提交 properties 字节一致 |
| Python / tokens | PASS：168 / JSON-Compose 一致 |
| Android AAR metadata / APK / lint / Compose / Preview 精确提交 CI | NOT RUN；按父级要求先实现独审，再正常推送；未启动新 CI |
| 真实权限/闹钟/通知/重启/OEM/SQLCipher/16KB/性能 | NOT RUN；受控 JVM 不是设备验收 |

主机全量指纹：`d1a6a99a074e83994f7de98767f4f890fc56c3555eae2d822e3ebe91289a32e7`。
原生组件受控指纹：`99f76999e54e69b180f3b948801eed511fc261061fefbb405f5d70fb72bd9165`。
后者实际编译 data 与 receiver/service/adapter/broker；未运行的 Activity 导航目标和 R 是壳。
API 条件在固定 API35 JVM 上模拟，不能称实际 API26/31/33/37 设备运行。

[完整证据 ZIP](evidence/d07-r2b-controlled-host-20261020.zip)：347505 字节，
SHA256 `479aacfc6b6fab08dc89a9c0c3271812e7cb5f0bfe22d1e2aacb6b25d487c575`，263 个条目，逐条哈希/CRC 已复核。
其中 final-host / final-queue / final-native 为冻结产品提交证据；包括实际 XML、源码输入和原始日志。
`host-full-1` 的早退休关闭回执失败已修复；`host-full-2` 与定向 Gradle 重叠、出现旧/新类混合，明确作废，不能当最终证据。
其余本地 API/fixture/JSON 缺省格式/编译错误与修正日志保留，不通过删除失败结果声称成功。

2026-10-20 12:23 UTC 普通 Git 只读复核：远端开发分支仍 `9072bb941000a5956c9229bb29b9372657aa8c70`，
main 仍 `7383e502418f5f9fc0c540ef2072742359343d40`，远端是本地产品祖先。没有推送、强推、改 main 或部署。
下一步是父级对具体实现独审，随后按既有授权正常推送该开发分支并运行一次有界 CI。
没有权限审批拒绝；推送/CI 是按明确顺序待执行，真实系统权限/通知试运行仍未授权。

原方案文件保持原字节：Master Plan SHA256 `a9c647e0555cd57a4511e58506a4ad8516d5c329ada7bc5329cdd61839a54e9b`；
DOCX SHA256 `2d28784231148af980512bd87115bb1c1d243be0c3825f61b441934f76727c94`。
LOCAL_ONLY、无自建后台、无真实模型调用；没有新增 SDK 接受、费用/凭据或未获准安全网络权限。
D03 ASR/OCR、D04 绑定和 D07 整体验收仍 OPEN。
