# F01 / D03: direct text, mixed and picture-only capture

The production Now route accepts text, JPEG/PNG images, or both. Empty text without images is rejected.
An image-only entry stores its real empty original text; “图片记录” is presentation text only. New entries
use the product's LOCAL_ONLY default. There is no OCR, audio recording or model invocation.
Full D02/D03 and device/SAF/Keystore/fresh-install/TalkBack/performance acceptance remain OPEN / NOT RUN.

## Draft and save contract

The existing encrypted SQL owner stores one versioned CAPTURE_CURRENT draft in its FK-free operation
ledger. On the first use, the old text capture draft is atomically moved with its stable ID and exact
text; unknown/unreadable data fails visibly. Text is bounded at 100,000 UTF-16 units and preserves
unfinished IME surrogate units in the encrypted draft; saving requires complete valid Unicode.
The manifest includes ordered, stable image UUIDs, hashes, lengths, MIME and WRITING/READY/FAILED/
REMOVING states. At most 20 images, 16 MiB each, 64 MiB total. No URI, permission or plaintext file is
persisted. The existing independently wrapped media key, AEAD, fsync, atomic rename and file lock are
reused. File ownership is recorded before IO and final creation proofs protect uncommitted cleanup.

JPEG/PNG selection reuses the same platform picker and validators/metadata choice. Draft thumbnails
are scaled to at most 160px in memory; encrypted originals are preserved. Selecting, canceling,
removing, discarding and saving are separate actions. Picker results are bound to request and draft
identity. Canceling a selection does not silently remove a durable failed image intent; it must be
reselected, retried or explicitly removed. Failed images prevent a text-only fallback save.

An entry does not exist during image staging. Save authenticates every READY encrypted original while
holding the existing media lock, then atomically inserts entry/revision, all READY attachment rows,
LOCAL_ONLY access with no connection or consent, initial manual labels, local-index job and a durable CAPTURE_SAVED snapshot digest,
and consumes the exact draft. File IO stays outside this short database transaction. The success
notice follows durable save plus entry/tag/favorite readback. Lost ACK freezes the original request;
retry consults its stable receipt without another entry, image ID or overwrite. The receipt survives
permanent deletion and cannot recreate a trashed/erased record. Current text and image intent remain
available after failed persistence; refresh and image removal do not replace unacknowledged text.

Startup authenticates draft media and resumes removal/discard using exact registered ownership.
Complete staged images become READY; incomplete images become visible FAILED intentions. Original
bytes or permissions may need to be selected again after a genuinely incomplete copy. Removal and
discard first record intent, then clean files, then finalize metadata. Cleanup is reentrant and does
not enumerate/delete unknown files. Removed-image/discarded-draft receipts reject delayed old requests.
Published thumbnail objects follow Compose/bitmap lifetime; unpublished images and prepared original
bytes are wiped/released by their IO owner before storage closes.

## Other product surfaces and compatibility

Timeline and detail label image-only records honestly and retain the attachment viewer. Manual tags,
favorites, recycle/restore and permanent erasure accept empty text only with the matching image ledger.
Local search indexes the real text and manual labels; no content is inferred from the picture. The
capture command refreshes favorite snapshots and schedules the existing index after actual entry
changes. Text-based derived candidates are unavailable for empty/AI_NONE records. Original-text
editing retains its existing text constraint; this batch does not change revision-bound image editing.

The earlier direct-capture implementation wrote AI_NONE, conflicting with the master plan §14.1,
ProcessingDefaults and the prior text save path. As a result even freshly typed text was blocked from
the existing explicit local-excerpt, source-tag and source-memory flows. The correction only changes
the initial policy of future self-authored captures; it does not migrate old AI_NONE records, loosen
candidate eligibility, or change encrypted-package/recycle restoration's AI_NONE policy. A replayed
save acknowledges its original receipt without replacing a subsequently restricted policy. Empty
image-only originals remain empty and cannot become text excerpts. LOCAL_ONLY still cannot pass
the remote-processing gate, with or without an unrelated consent flag. No excerpt extraction or remote
processing starts automatically.

Open Markdown/JSONL export keeps an empty body and its metadata/tags; its existing explicit scope
excludes image bytes and remains an incomplete plaintext export. Use the encrypted image package to
move pictures. OriginalExportRecord includes a source image count in its validity/snapshot binding.
v2 alone calls the record codec with explicit image-only support, and requires each blank record to
have a same-source/version image descriptor whose complete bytes authenticate. v1 remains strictly
text-only; its existing fixed Node/OpenSSL fixture is unchanged. Existing text v2 byte fixtures remain
unchanged. Restored image-only copies get new record/image IDs and current-device encryption.

## Verification

Host tests exercise real SQL, JCA, encrypted files, cold reopen, injected failures, stable IDs, ACK
loss, explicit cleanup, pure-image lifecycle and v2 roundtrip. An actual ViewModel regression verifies
post-save favorite completeness and immediate local indexing. Compose tests use the production Now
route/controller and thumbnail decoder with controlled storage ports at 320dp/200% font. Native
assertions are appended to an existing instrumentation method without implying device execution.

## 此刻最近记录补齐（2026-10-17，主机范围）

两份原方案 §5.1 都要求“今天/日期、当天最近记录、输入区”和显式新内容提示。
基线 `75e130d146e77ab722ea6a498a0a9b65ca0b5143` 的实际非演示此刻入口进入
`MixedCaptureScreen`；保存已发布持久化后的 `entries`，但该页面没有读取记录列表，
也没有原文点击回调。这里只声明生产源码可证的入口缺失，没有把未运行的基线 Compose
测试说成红灯。

此刻现在显示设备当前时区内、按记录时间属于今天的最近五条，保留仓储原顺序和当前原文版本。
日期区间是 `[当天开始, 次日开始)`，支持夏令时的 23/25 小时日；手工事件日期不改变此范围。
日期/时区仅在页面存在时每分钟于内存中核对。空正文仍显示图片记录，点击复用既有原文阅读及附件入口；
更多记录留在回忆页。没有新增图片解码、明文磁盘缓存、schema、SQL 写入或 AI 调用。

主入口复用已有 `nowList`。列表为记录、草稿及各控制项使用稳定 key；新内容到达时提供明确提示，
只在用户点击提示或底部“继续记录”时调用滚动。草稿、保存确认身份和清空流程不变，
没有把正在确认的保存提前作为新卡片发布，也不以重新保存来恢复阅读。
滚动位置、键盘、字体与阅读返回的实际 Compose/设备效果仍待原生执行，不能由这些源码推断已经验收。

新增四项主机测试使用实际控制器/VM、产品保存/回收/中文检索 SQL 与真实加密图片文件：
保存后立即读取当前记录、下一份草稿保持、实际确认移入和恢复、同一原图字节、恢复 AI_NONE；
以及保存读回失败后的同身份重试、来源顺序/五条上限/当前版本、日期边界及不可用所有者。
首轮针对用例实际 10 项通过、首轮完整 1012 项通过后，源码复核确认：进入阅读触发原有
`onDispose -> cancelSelection()` 时，没有选图也会清掉未确认草稿警告。实际控制器回归先复现红灯；
现在只有真实存在的选图操作才执行取消，保存失败提示保持到用户明确保存恢复。
修复后针对实际控制器/VM 的 21 项通过（新 4、图文 7、回收 10）。
首轮通过和这一失败均保留。最终完整主机 **1013 个唯一方法**通过：AI 178、domain 71、
remote-text 764，保留父独审准入 75e 的全部 1009 项并新增 4 项，失败/错误/跳过均 0。
两个完整运行对应不同源码版本，没有同源码完整重试；本片仍待父线程独立审查。
原有两项 Compose 方法扩充，一项新 Compose 方法加入精确库存；均尚未编译/运行。

本片最终主机结果与原始 XML、源码闭包见 [证据ZIP](evidence/f01-now-recent-records-host.zip)。
这不代表 F01、D03 或 §5.1 的完整体验已完成。真实 Room11 导出及 fixture 必须先在获准工具链上
从已接受的 v11 源码生成并提交原字节，然后才可生成/提交 v12 对并做真实迁移/SQLCipher 验证；
现有缺失门禁未改。Android metadata/assemble/lint、Compose/Preview、设备/可访问性/性能
均 NOT RUN，没有新 APK；SDK 新条款和通知权限尚未批准，没有接受、下载、配置或重复环境诊断。

保存草稿恢复、清空重试、中文双字片段与单字/精确回退已有实现，没有另造一套功能。
系统提醒的实际 driver 仍返回 `PLATFORM_UNAVAILABLE`，需要另行获准的权限与设备集成；
生产 `bindAi` 与选定出处回答 provider 仍未接入，合成主机注入不等于用户可用模型。
语音/OCR 也分别涉及尚未授权的权限或依赖，不能把这些产品缺项统称为仅缺 SDK。
父线程原始邮件确认 246/cd5/771 的 Android CI FAIL，原因 UNKNOWN；75e 及本片新提交 CI UNKNOWN。
未访问被拒 Actions 或替代路径，旧 AI 首轮准备断言失败的根因仍 UNKNOWN，原始证据保留。
