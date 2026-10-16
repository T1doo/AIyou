# §5.2 当前版本图片筛选

从 `747158cfe69829e026b0bdceed1237f095f95b09` 接续两份原方案 §5.2 的图片筛选。
原始方案文件保留；本片沿用本机原文查找，不解释或修复未知 CI 根因。

- 默认关闭“仅看当前版本含图片记录”，仅在当前页面内保留开关；清空查找条件时一并
  关闭，重新创建页面后恢复默认。关键词／精确原文、记录日期或事件日期、手工标签、
  收藏及图片条件全部 AND。事件日期仍不回落捕获日期。
- 复用 `LocalSearchRequest`、同一 records owner 与 `SqlLocalSearchRepository`，
  在分页与候选上限前通过附件 EXISTS 限定 `entryId + currentRevision + PNG/JPEG`。
  仅历史版本有图片、纯文字或只有非图片附件的记录不匹配；纯图片正文保持空字符串。
- 不新增 schema／迁移、索引、权限或依赖。只读取附件关联元数据，不读取或解密图片
  文件，不做 OCR／内容识别，不发送网络请求。LOCAL_ONLY、BYOK 和无自有后台保持。
- PNG/JPEG 关联存在但附件处于 FAILED／CORRUPT 等状态，仍属于含图片记录；查看附件
  时沿用现有读取失败／损坏提示，不将图片关联筛选冒充文件可读性验证。元数据读取
  失败则整次查找明确报错，不以空结果或全量文字浏览兜底；可关闭筛选或重试本机读取。
- 私有检索证明绑定当前图片 ID、源版本、媒体类型、hash、字节数与状态。发布结果及
  打开原文前，在同一个 counted SQL write lease／事务内重跑有界候选范围，再复核原文、
  标签与事件值。附件变化没有文字索引 epoch 变更时也拒绝旧结果，包括空→非空成员变化。
  原文编辑、回收／恢复、永久源删除仍受既有版本、可见性代次与 owner 检查约束。
- 延续每次最多 1000 候选、正文预算和扫描上限，图片关联每条最多 64 项；超限整次拒绝，
  不截断。该筛选不新增网格、缩略图、人物筛选、语义检索或 AI 问答能力。

## 验证与边界

首轮专项实际主机 SQL **7/7 PASS**，使用生产 SQL／FTS／matcher／私有 proof 与
测试用有界 Python SQLite transport，不能代替 Room／SQLCipher／Android。
最终源码实际执行 **895/895 JVM**：AI 178、domain 71、remote SQL／controller 646，
原始 XML 均 0 failures／errors／skips，精确方法清单与执行输入指纹门通过。新增 SQL 7
和 controller 4 均逐项 actual=1；首轮专项 7 已包含在 646 内，不重复累计。独立队列
**149/149 PASS**，原始 XML 和既有精确方法门通过；该命令重复执行的 AI／domain
不重复计覆盖。Python **151/151 PASS**。本轮无测试失败或修正重跑；SDK 失败单列保留。
2 项新增 Compose 用例为源码覆盖，未执行。
Compose 方法已登记精确质量门，未删除旧断言或降低 D00–D09 门槛。

本地 SDK 前置只读检查 **FAIL**：缺少开发者已接受许可的完整 Android 工具链。
未接受新条款、安装组件或申请新通知权限。Android AAR／APK／lint、Compose 编译／执行、
真实 Room11/KSP、394 Preview、仪器与真机 SAF／恢复／密钥重连／原生提醒均 **NOT RUN**。
无新 APK。新 SHA 的 CI 状态未知；未访问已拒绝的 Actions、下载 artifact 或换路获取。
既有主机证据保留；旧94/96超时根因仍未知，本片不作解释或修复。
