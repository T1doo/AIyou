# D02：已存原文版本的本地 PNG 附件界面

这是附件状态机的小范围实际连接：原文详情 → 查看附件 → 系统单选图片 → 有界验证 → 现有加密文件库 → 同一附件认证读回 → 查看。目标是已经保存的原文 ID 与打开页面时的 revision；不是新的图文混合记录、完整媒体输入、录音、OCR、缓存、删除、导出或联网功能。原始 Master Plan 与 DOCX 保留。完整 D02 仍未验收。

## 真实连接与状态

`EntryRepository.attachments` 暴露可选能力；生产 `EncryptedEntryRepository` 返回已有的 `EncryptedAttachmentStore`。`LocalRecordsViewModel` 的同一 IO 命令队列拥有仓库、恢复、导入、读回与关闭，不另开数据库或第二个生命周期。单张图片暂存于私有内存，选择的 URI、名称、明文字节和 bitmap 不进入 SavedState、日志、数据库或明文缓存文件。仅短时读取明确选中的 content URI，不保存持久 grant。

每个页面与选择器有独立 session/request，实际 `ActivityResultRegistry` 注册直接使用本次选择的 VM 内存 UUID key，并捕获不可变 request；不恢复 rememberSaveable launcher key。迟到结果不能改挂新页面或重启后的新 controller，即使数字 request 计数相同。配置恢复保留当前 VM 时不重复 launch；进程重启不恢复旧来源权限或旧内存选择。持久状态由原生产库在 prepare/recover 中恢复。

- 首次导入：同一来源只打开一次，最多16 MiB，真实 MIME/header/PNG CRC、完整 zlib/扫描行（含Adam7）与实际 bitmap 解码验证；原字节原样加密。两个尺寸各≤4096，查看 bitmap 采样后各≤1024。当前拒绝 APNG 与 eXIf 定向元数据。
- 确认成功：必须同一稳定 intent、READY、实际列表和完整认证 read；随后显示“图片已保存在本机 · 原文版本N”。文字保存成功不会代替附件成功。
- 确认失败：保留原 ID 与验证过的内存来源；重试先 recover/list/read，已有 READY 不重读 URI、不重写密文、不建第二份。
- 重启 FAILED：明确重新选择同一图片，原 ID/source/revision/MIME/hash/size 必须全部相同，名字或 URI 相同不足以证明同源。
- CORRUPT：不显示、不覆盖、不提供重新选择覆盖动作，原文件证据保留。
- 取消/退页：验证阶段立即撤销 owner，迟到结果丢弃；提交开始后禁用取消与返回，不承诺回滚。活动 Cipher 仍使用的 buffer 等操作结束后再清理。
- 原文版本变化：不静默迁移附件，保留原版本归属；全局文字草稿失败通过已有恢复动作处理，不清空附件页面/已准备来源。

系统来源 provider 可自身使用网络；`LOCAL_ONLY` 描述本应用处理和保存不外发，并不保证外部 provider 的实现离线。采用 [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker) 的 `PickVisualMedia.SingleMimeType("image/png")`，无 picker 时由该系统契约兼容回退。查看采用 [BitmapFactory](https://developer.android.com/reference/android/graphics/BitmapFactory.Options) 真实 bounds 后采样解码。

## 实际检查的边界

新增13个 host 控制器/VM回归编译真实生产源码并连接实际 AES/file store；记录与附件账本和平台解码是显式测试边界，不宣称 Room/Android。覆盖取消、关闭后同ID重开、迟到解码、固定版本、已READY读回失败只认证重试、FAILED同源恢复、损坏保留、权限/MIME/头/零读/超限、全局可读性与活动 buffer 生命周期。

新增4个完整 PNG 压缩流/扫描行 host 负例与兼容性回归。App 新增7个真实 Compose/ActivityResultRegistry 交互用例，以及4个显式 `@GraphicsMode(NATIVE)` 的 ContentResolver/实际 Android bitmap 用例。后者要求实际像素值与采样尺寸，不接受默认 fake bitmap 作为 PNG 可读证据。App UI 的 repository 使用标明的合成边界；这些测试与 host 文件协议测试分别提供证据，不称为完整 SQLCipher/Keystore 端到端实测。

常规 CI 保留原50 host、53 App、320 render及全部独立PID/严格lint/schema/锁守卫；增加到67 host、64 App、352 render。新8附件页面状态各四尺寸，使用实际生产 Screen 与合成状态；Ready 状态以实际 decoder 读固定合成 PNG。静态截图不验证点击、来源权限或进程死亡。检查数字是必须满足的合同，执行结果应以对应提交的真实XML、PNG、日志及独立审查报告为准。

本轮当前主机核实无已装 SDK/adb/可授权设备，未接受新协议、安装设备组件或改变安全设置。同步 provider 读取/平台解码不保证被即时中断；取消保证 owner 失效及结果不发布，字节与解码资源有界，阻塞 provider 的设备行为尚未验证。17项原生测试、Android跨PID/Keystore/真实旧库升级/目录持久性/物理断电/16KB/性能/IME/TalkBack/UI进程重启与精确 JNIString NOTICE祖源继续保留各自 NOT RUN/UNKNOWN。此 PNG 界面不替代这些门槛，也不代表 D03 完整实施。
