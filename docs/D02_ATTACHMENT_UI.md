# D02：已存原文版本的本地 JPEG / PNG 附件界面

这是附件状态机的小范围实际连接：当前原文详情 → 查看附件 → 系统单选图片 → 有界验证 → 现有加密文件库 → 同一附件认证读回 → 查看。历史版本详情也提供“查看附件”，仅读取该固定版本已有附件，不能新增或重试添加。目标是已经保存的原文 ID 与打开页面时的 revision；不是新的图文混合记录、完整媒体输入、录音、OCR、缓存、删除、导出或联网功能。原始 Master Plan 与 DOCX 保留。完整 D02 仍未验收。

## 真实连接与状态

`EntryRepository.attachments` 暴露可选能力；生产 `EncryptedEntryRepository` 返回已有的 `EncryptedAttachmentStore`。`LocalRecordsViewModel` 的同一 IO 命令队列拥有仓库、恢复、导入、读回与关闭，不另开数据库或第二个生命周期。单张图片暂存于私有内存，选择的 URI、名称、明文字节和 bitmap 不进入 SavedState、日志、数据库或明文缓存文件。仅短时读取明确选中的 content URI，不保存持久 grant。

每个页面与选择器有独立 session/request，实际 `ActivityResultRegistry` 注册直接使用本次选择的 VM 内存 UUID key，并捕获不可变 request；不恢复 rememberSaveable launcher key。迟到结果不能改挂新页面或重启后的新 controller，即使数字 request 计数相同。配置恢复保留当前 VM 时不重复 launch；进程重启不恢复旧来源权限或旧内存选择。持久状态由原生产库在 prepare/recover 中恢复。

- 首次导入：同一来源只打开一次，最多16 MiB，MIME必须与文件头匹配。PNG 保留既有 CRC、完整 zlib/扫描行（含Adam7）与实际 bitmap 解码验证；原字节原样加密，两个源尺寸各≤4096，拒绝 APNG 与 eXIf 定向元数据。JPEG 接受8位灰度/RGB baseline或progressive，源尺寸各≤8192且≤6400万像素；有界检查段长度、SOF/SOS/EOI、IFD0方向、扫描与段数，拒绝尾随字节、多EXIF及异常方向。按SOF采样因子与SOS交织方式计算DCT块数，baseline要求每块至少2载荷bit，progressive DC至少1bit，FF00只计一个载荷字节，restart/fill不计；baseline/首次DC须覆盖所有frame component。只是必要的最小载荷边界，未解Huffman/AC系数，容器检查不等于熵解码完整性证明。
- 确认成功：必须同一稳定 intent、READY、实际列表和完整认证 read；随后显示“图片已保存在本机 · 原文版本N”。文字保存成功不会代替附件成功。
- 确认失败：保留原 ID 与验证过的内存来源；重试先 recover/list/read，已有 READY 不重读 URI、不重写密文、不建第二份。
- 重启 FAILED：明确重新选择同一图片，原 ID/source/revision/MIME/最终保存字节hash/size 必须全部相同，名字或 URI 相同不足以证明同源。JPEG 的元数据选择影响保存字节，重启后须重新选回产生同一保存内容的方式；清理后被删除的元数据不参与最终hash，不能将此声称为原始完整来源字节相同。
- CORRUPT：不显示、不覆盖、不提供重新选择覆盖动作，原文件证据保留。
- 取消/退页：验证阶段立即撤销 owner，迟到结果丢弃；提交开始后禁用取消与返回，不承诺回滚。活动 Cipher 仍使用的 buffer 等操作结束后再清理。
- 原文版本变化：不静默迁移附件，保留原版本归属；全局文字草稿失败通过已有恢复动作处理，不清空附件页面/已准备来源。

历史查看使用不可变 history session/request、所选 record/revision 和读取列表时的当前 revision 授权。ViewModel与根界面共用权限门槛，实际IO前后另查已认证的原文/历史版本存在及精确归属；列表每行与read前再次查到的intent都必须同record/revision/ID，read bytes还需同hash/size。旧请求失效、换版本、缺失记录/历史或关闭重开，不发布迟到图片；新版本不会显示旧版本附件。当前导入仍要求准确当前revision，历史入口不能取得写权限；返回只关闭附件页，保留选中历史全文、编辑输入和此刻草稿。只读指用户不能新增/重试添加；既有recover/list/read仍可修复持久账本状态，不承诺内部零写。

系统来源 provider 可自身使用网络；`LOCAL_ONLY` 描述本应用处理和保存不外发，并不保证外部 provider 的实现离线。[Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker) 在API28+使用单选 `ImageOnly`，选回来的WebP/HEIC/GIF等在应用内拒绝，未增加格式承诺。API26/27明确只提供PNG筛选；JPEG需要Android9+，未用宽松解码偷偷兼容。没有picker时使用该系统契约的兼容回退，不取得广泛或持久图库授权。

JPEG 每次导入显示明确的“保留 JPEG 原图拍摄资料”选择，默认勾选，保留时完整原字节原样加密；提示可能包含位置资料，仅在本机加密保存。取消勾选才移除EXIF/GPS/XMP/IPTC/COM、未知APP及内嵌缩略图；重建最小方向标记，保留有界结构验证的JFIF/ICC/Adobe色彩资料，不重压缩熵扫描。色彩资料可能仍带说明，不能宣称所有元数据清零。这是本地保存选择，并非上传清理或原图库修改。选择方式在picker请求创建时固定，活动请求、错误和历史只读页不能改写。没有原图/处理图明文临时文件。

JPEG 使用API28新增的 [ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder) 从一次读取的内存ByteBuffer解码；头尺寸必须与IFD0方向后的预期相同，原生解码负责全部8种方向，不再二次旋转。软件/sRGB显示副本按2的幂采样，输出尺寸各≤1024、像素字节≤4MiB。明确拒绝平台报告的partial decode；此标志不证明平台识别全部恶意熵错误。验证用临时bitmap及迟到未发布bitmap回收，已发布图片按界面所有权使用。PNG 保留 [BitmapFactory](https://developer.android.com/reference/android/graphics/BitmapFactory.Options) 的既有实际bounds/采样路径。最终显示边界不等于progressive内部解码峰值内存或真机性能已验证。

首次同SHA CI实际发现此限制：[00d9f460](https://github.com/T1doo/AIyou/commit/00d9f460d65590dcf637784895d6bc6bdde33faa) 的 [run38150656004](https://github.com/T1doo/AIyou/actions/runs/38150656004) 中，73 App测试有1失败：固定API35 NATIVE ImageDecoder在拒绝partial设置下仍接受仅3字节扫描载荷的早EOI JPEG。不是测试应当视为PASS。保留原失败，后续回归直接调用该codec断言此已观察到的接受行为，并验证应用在保存之前由上述必要载荷边界拒绝它；明确不称为“原生熵解码拒绝”。[T.81](https://www.w3.org/Graphics/JPEG/itu-t81.pdf) Annex C/F的Huffman前缀和DC/AC过程支持此下界；progressive AC有跨块EOBRUN，不能套每块相同下界，也没有强制合法DC-only progressive必须有AC scan。更长的损坏熵载荷仍可能满足该下界并被平台宽容接受，此路径只提供有界格式检查与平台可解码范围，不提供任意JPEG熵流完整性证明。原文、原图库与来源字节不被修改。

## 实际检查的边界

新增13个 host 控制器/VM回归编译真实生产源码并连接实际 AES/file store；记录与附件账本和平台解码是显式测试边界，不宣称 Room/Android。覆盖取消、关闭后同ID重开、迟到解码、固定版本、已READY读回失败只认证重试、FAILED同源恢复、损坏保留、权限/MIME/头/零读/超限、全局可读性与活动 buffer 生命周期。

新增4个完整 PNG 压缩流/扫描行 host 负例与兼容性回归。App 新增7个真实 Compose/ActivityResultRegistry 交互用例，以及4个显式 `@GraphicsMode(NATIVE)` 的 ContentResolver/实际 Android bitmap 用例。后者要求实际像素值与采样尺寸，不接受默认 fake bitmap 作为 PNG 可读证据。App UI 的 repository 使用标明的合成边界；这些测试与 host 文件协议测试分别提供证据，不称为完整 SQLCipher/Keystore 端到端实测。

历史闭环新增8个实际控制器/VM/AES文件库的host测试（原文账本/解码为明确合成边界），覆盖先导入再实际编辑生成新版、冷会话读取旧附件、新版不串图、所有历史写入口拒绝、缺失/错误/重复身份、跨版本row、迟到请求及内存清理、损坏/失败保全；另加4个真实Compose根路由和NATIVE bitmap回归，涵盖历史进入/返回、保留两份输入、旧导航闭包拒绝、读取中换请求、新版隔离和320dp大字体只读恢复动作。

JPEG增加6个纯容器/元数据host用例、3个实际controller/AES文件库用例、4个API35 NATIVE ImageDecoder用例及1个真实Compose/registry选择用例；合成中性四角颜色、超宽固色和GPS数据不来自用户照片。覆盖两端序全部8方向、progressive、实际采样像素、GPS清理后的相同方向像素、marker合法但不足必要载荷的提前EOI、一次URI读取、固定选择、冷历史归属、认证重试无重写、迟到bitmap回收/字节清理。纯容器与NATIVE用例区分应用前置拒绝和平台仍接受的行为；使用真实controller/平台decoder及明确合成store边界核坏来源不会触发save，不冒称SQLCipher/Keystore实测。原生用例必须由对应修正提交的真实CI执行并检查XML后才能报PASS。

常规 CI 保留原75 host、68 App、372 render场景及全部独立PID/严格lint/schema/锁守卫；合同增加到84 host、73 App、380 render。JPEG Ready与取消保留选择各四尺寸，Ready截图实际调用生产ImageDecoder；layoutlib失败时不能替换成假bitmap。附件包60图与原108图页面包分开，每包仍≤17,000,000B；打包保留本次实际渲染PNG字节。静态截图不验证点击、来源权限或进程死亡。合同数字不等于执行结果，应以对应提交的真实XML、PNG、日志及独立审查报告为准。

本轮当前主机核实无已装 SDK/adb/可授权设备，未接受新协议、安装设备组件或改变安全设置。同步 provider 读取/平台解码不保证被即时中断；取消保证 owner 失效及结果不发布，输入/输出边界明确，阻塞 provider 的设备行为尚未验证。API26/27设备降级路径、17项原生测试、Android跨PID/Keystore/真实旧库升级/目录持久性/物理断电/16KB/性能/IME/TalkBack/UI进程重启与精确 JNIString NOTICE祖源继续保留各自 NOT RUN/UNKNOWN。此附件界面不替代这些门槛，也不代表 D03 完整实施。
