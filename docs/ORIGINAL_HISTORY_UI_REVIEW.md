# D02 只读原文历史切片：待本次源码与 CI 独审

原方案 F02、5.4、8.1/8.2 要求编辑版本保留、原文证据不可无痕改写、来源具有 ID 与版本。已接受的 4becc247 编辑切片将旧文本保留在现有 EntryRevision；本次仅接通既有 `EntryRepository.revisions(entryId)`，让用户在原文或编辑页查看只读版本列表和完整已保存原文。没有恢复覆盖、删除、合并、新 schema/迁移、AI、网络或依赖变更。

列表按版本号从新到旧显示，明确“当前版本 N / 历史版本 N”，摘录仅作列表预览。打开版本后重新读仓库，完整正文与复制都使用所选不可变版本，显示创建时间、当前/历史标识与只读说明，不把当前正文替代缺失旧版本。复用已接受的单一完整 Text、公开 SelectionState 与敏感剪贴板门禁；选择状态、历史正文、编辑输入均只在内存中，不进入 Bundle/rememberSaveable/SavedStateHandle/日志/明文文件。

历史读取与捕获草稿/编辑提交沿用同一 IO 队列。一次读取前后各读当前记录，期间变化要求重新读取，删除不暴露保留的旧文本；检查版本所属 ID、版本边界/重复、当前版本正文与当前记录一致。会话 ID + 请求 ID 保证关闭、重新打开、退回列表和旧请求结果不能替换新状态。读取失败/冲突仍发布已成功读取的最新当前记录快照，使用户无需重试就可返回已知最新原文；不覆盖编辑输入。任何历史读取失败会清空历史正文显示，保留输入并提供独立重试；不会把历史错误当成编辑保存失败。

编辑页可打开已保存版本；历史查看不写仓库、不保存修改、不消费此刻草稿。历史显示期间旧编辑回调不能输入/保存/取消；返回编辑沿用同一编辑会话和预期版本。历史读取发现源变化后，仍保留用户输入，原有保存版本检查继续拒绝覆盖。保存中/已提交待读回不允许打开历史。进入历史、选版、重读、返回、dispose 都更新导航门禁，旧显式或原生复制回调不能作用于另一个版本或关闭重开后的会话。

系统 Back 从版本全文返回版本列表，再返回原文或继续编辑；顶栏提供同样操作。版本列表独立 LazyListState、stable revision key，阅读后返回保留位置；此刻/回忆原记录列表与选中 ID 仍使用已存在导航。先前排队的此刻草稿写入晚失败时，历史页可重试本机存储，保留编辑和捕获两份输入；恢复时原子隐藏缓存历史并重新排队读版本，实际读取成功后才显示正文。没有调用会被历史门禁拒绝的 changeText。

## 实际验证与待执行

本机 21 个 queue 测试实际执行、XML 唯一成功/零失败错误跳过（原 14 + 新 7）；编译真实生产 VM/历史状态，使用合成存储边界，不是 Room/SQLCipher/Keystore 验收。59 个 Python 检查与 token check 实际通过。

新增 10 个真实 App/VM/Compose 用例使用领域仓库注入，实际点击、输入、Back、滚动、原生选择与复制；覆盖完整 100000 UTF16 Unicode/25000 行旧原文末端几何、当前/历史标识、编辑/草稿/原记录列表锚点、历史列表锚点、列表与全文读取失败/重试、源变化/删除、迟到结果与同 ID 重开/dispose、捕获晚失败、缺失选版与保存中门禁。守卫目标为 App 45、queue 21，34 个具名 App 必須唯一成功；预计全 JVM 去重 84，不以 NO-SOURCE/重复 guard 打印当执行。

新增正常列表、旧全文、加载、失败、不可用五种真实生产状态 × 四尺寸，预计 304 actual renders / PNG，review 92，七包 28/28/28/112/2/14/92。仅合成数据；初始渲染不是已批准 golden。Android/App 测试、严格 lint、APK 与截图须以本次冻结源码对应的既有 CI 终态为准，源码阶段尚未声称通过。原方案与 workflow 保留。

4becc247 图片已由父独立正常下载并审阅放行；不重复旧包下载，也不代用旧图作为本次验收。新图须实际图审。设备 UI、输入法/长按/TalkBack/性能、原生数据库重启/跨 PID/迁移/16KB 运行时、未保存编辑进程终止恢复、JNI String 精确 NOTICE 祖源缺口继续保留，完整 D02 未验收。

## 父审续修：宽屏约束与列表返回诊断

父实际下载 a3e3275 原 XML/PNG 后核验81 JVM/304 renders，并指出历史列表和错误分支840dp贴左且铺满。通用历史页现复用正式阅读/编辑的 Box TopCenter + widthIn(contentMaxWidth=720dp)，不改变版本读取或存储合同。新实际App用例覆盖840dp列表与失败页的720dp最大宽度、居中及20dp内边距；既有320dp/font2用例补列表与错误页的对应约束和可达重试。

首轮01647d8末项返回四边可见断言确实失败，a3e3275只加诊断同断言复跑通过，尚未定因。当前新增两个单变量真实App用例：显式360dp宽/640dp高下编辑保存将末卡预览增至三行；另在正文不变、无IO/编辑时返回列表的同一受控帧把根视口从640dp缩至360dp。记录真实生产LazyListState的index/offset/layoutInfo/可前滚和实际IMEbottom，同时检查根实际尺寸、严格四边/末端/选中/草稿合同。此测试是受控Compose布局诊断，不能当成真实IME或设备证据；未通过之前不声称滚动同步修复。原中段位置和旧四边断言继续保留。新版本源码/实际CI尚待执行，不将上一SHA的图像或XML当成本版本证据。
## Measured list return correction (pending exact-SHA CI)

The first repair `ef09551f1873a1837c3f75ecbecd838456fda3e7`, run
38127155749, compiled but actually failed two of 45 App tests. The growth case
did not fail. The shrink case's card was fully visible at 68–273dp within
56–275dp, but its measured 205px height exceeded the 195px area remaining after
two 12px paddings, leaving 10px of forward scrolling. The original editor case
still failed its full-card assertion; its precise request/layout sequence remains
unknown. Lint and the native-test APK were skipped in this failed run.

The next correction uses the measured target card size for one additional
end-clamped scroll only when forward scrolling remains. It also matches the
attached list height to the actual Scaffold height minus its current content
paddings. When navigation is visible, the navigation bar must have a fresh
measured height and the current bottom padding must cover it. This is a layout
contract, not proof of the old failure's cause. A default-null, memory-only test
observer records numeric request/measurement/scroll/completion stages without
record text. All original full-card, at-end and middle-anchor assertions remain.

The diagnostic-only `9ccf4518b1a23fd1ccb0307620121bbf7b001cc7` run
38125457561 really failed the original strict editor return case. The selected
card ended at 988dp while the measured list ended at 915dp; the recorded IME
bottom was zero. Both new controlled cases stopped at their initial tail setup,
so they did not yet demonstrate a return failure. The accessibility range's
100 increment indicates further scrolling; it is not a physical pixel distance.

`466f7958a3c0732184c1a3e045b180de3644b83f` run 38126365867 corrected only
the two controlled fixtures using the actual last-item scroll action and a direct
open action. Both initial tail checks passed. At an unchanged 360x640dp root,
the saved three-line preview ended at 628dp beyond the actual list's 555dp end.
With only the root height changed from 640dp to 360dp, the selected last record
was absent from the composed visible items. Both recorded IME zero and further
scrolling remained possible. The original strict case failed again. All three
failures and full logs are retained; no assertion was removed or weakened.

The correction creates one memory-only request on a real reader-to-list return
only when that stable record ID remains the last displayed record. The request
belongs to its destination and navigation generation. A newly attached list
waits for its actual viewport measurement, uses the measured total item count,
then immediately scrolls to its last item. It acknowledges only a current owner
and an actual last-item key with no forward scrolling; if the card fits the
viewport, its full measured vertical bounds must fit too. A later dataset or
viewport change may re-evaluate a pending request, without a timer or busy retry.
Middle-record returns do not create this request; their existing anchor check
now also verifies the real first-visible index and pixel offset exactly.

This is a repair for the measured controlled return failures. The missing
before-measurement evidence for the first `01647d8` failure remains unknown, and
actual device IME timing and performance remain NOT RUN. Source review and the
next exact-SHA CI must pass before this correction is delivered as verified.
