# 54ced 预览编译与分包交付修正

从 `54ced47c240ee8504bf39de7b70134f203e718f4` 接续。两项问题已在当前源码核实：
`RecordEventDatePresentationTest` 比较 `DpRect.height` 时缺少 extension import；
预览打包器固定将 review 118 张原 PNG 放进一个 ZIP，超过既有上限即失败。
外部 CI 摘要所述编译前失败及 17,072,008 字节仅作线索，本轮未查询 Actions、
下载 artifact 或换路获取这些原件。

- Compose 测试只补 `androidx.compose.ui.unit.height`，保留 `48.dp` 及全部断言。
- 原始清单仍为 394 张／9 组，review 仍为 118 张。按组和原文件名排序，逐组生成
  `d01-visual-<group>-001.zip` 等普通 ZIP_STORED 文件；不缩放、重编码、删图或提高
  17,000,000 字节上限。每包独立 README／MANIFEST 明确 part／parts、原尺寸、字节数
  与 SHA-256；INDEX 同时保留全套 394 项以及逐包对应。
- 预算计入 UTF-8 文件名、README、MANIFEST、ZIP 本地头、中央目录与 EOCD；按最终
  part／parts 位数求稳定分包，再核对写出的实际 ZIP 大小。单张图连同开销仍放不下，
  或 PNG 签名／结构／CRC／解压数据损坏、尺寸与清单不符、hash 改变，均拒绝。
  复用现有 bounded non-interlaced Layoutlib PNG 完整性校验，不宣称支持任意 PNG 格式。
- 全部包校验完才发布 INDEX；中途失败保留 staging，不覆盖不同的旧目标文件。
  上传只读当前 INDEX 的明确路径，旧 ZIP 或多余旧 part 不混入。
- 工作流先单独保留 INDEX，然后本地 Node20 action 逐包独立上传；每次仅一个 ZIP、
  压缩级别 0、保留 14 天、缺文件报错、不覆盖、不自动重试。它复用已固定 SHA 的
  [upload-artifact v4.6.2 入口定义](https://raw.githubusercontent.com/actions/upload-artifact/ea165f8d65b6e75b540449e92b4886f43607fa02/action.yml)，
  不安装新依赖或配置新凭据／权限。先核完整覆盖、source manifest hash、ZIP hash／
  大小及序号，再开始第一包。

上传 helper 依赖当前默认 checkout 布局：`github.workspace` 的父父目录下
`_actions/actions/upload-artifact/<固定SHA>/dist/upload/index.js`。会核对该 action 的
Node20 与入口定义，缺失或布局变化时明确失败，不下载替代代码或 artifact。
该真实 runner 路径及真实上传 **NOT RUN**；本机现有 Node24 仅执行本地假上传入口
的协议检查，不能替代 Node20 runner 验收。

本轮本机检查与独立只读核验：

- `python3 -m unittest discover -s scripts/tests -v`：**PASS，151/151**。
  定向打包／上传协议检查两轮各 **14/14**，不重复累计覆盖。
- 持久合成样本保留 394 张原 PNG，review 118 张分为两包：
  **16,888,464／292,130 字节**。10 个普通 ZIP 的原字节、尺寸、SHA、CRC 及总覆盖
  均复核一致；10 次本地假上传各仅接收一个对应包，无真实上传。
- `node --check` 与 `git diff --check`：**PASS**。Compose 源码除单一 `height`
  导入外与基线逐字节相同；这项静态核对不等于编译通过。
- SDK 前置只读检查：**FAIL**，本地缺少已接受许可的完整 Android 工具链；
  保留原始失败日志，未接受许可、安装组件或尝试绕过。

当前无实际 394 PNG／passing render manifest 原件，也无已接受许可的 Android SDK。
本轮封存的 PNG 是明确标记的合成打包控制样本，不能算 Preview、视觉、APK 或 Room11
通过。Android AAR/APK/lint、Compose 编译／执行、真实 KSP11、394 Preview、仪器与
真机 SAF／恢复／密钥重连／提醒演练均 **NOT RUN**；无新 APK。新 SHA 的 CI 未知。
既有 54ced 的主机 884／队列149 属前轮证据，本轮未重跑或冒充新的 Android 验收。
旧 94/96 SQLite 初始化／退出超时根因仍未知。
