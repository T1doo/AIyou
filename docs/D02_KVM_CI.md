# D02 加速模拟器单次验收

本切片只新增受控设备 CI，不新增产品阶段。用户已明确批准固定模拟器/镜像安装及本次临时 CI 用户必要的 KVM ACL。原两方案、产品代码、依赖版本和 b13cdb9 既有 app instrumentation 锁保持本切片输入；该锁此前确实变化，不声称全部锁一直未变。

标准公开 `ubuntu-24.04`、固定 Temurin17.0.17+10、既有 Platform37.0 r2 / BuildTools36.0.0。运行时严格核既有 SDK 接受记录和官方 License API；固定 emulator37.2.12、API35 default x86_64 r2 的归档由既有 metadata/URL/size/SHA1核验，另记录实际SHA256；不升级其它SDK，不接受新协议，不用第三方自动yes installer。新条款/缺现有记录即停止：[完整SDK条款](https://developer.android.com/studio/terms)。

只有 `/dev/kvm` 已存在且当前用户无读写权时，给当前 UID 增加最小 ACL；先证明其它主体的有效权限完全不变。若mask变化将影响其它主体则停止；不全员chmod、不改udev/组/网络/宿主配置。前后ACL及节点身份保存，结束只撤本次UID/mask变化；外部ACL变化停止并报告，不整份覆盖。实际 `emulator -accel-check` 必须确认KVM；不回退旧TCG。

同一job实际 `checkDebugAarMetadata`、三APK assemble、原domain非空单测及lint；从真正编译DEX收集原精确19方法和两个跨进程probe。新建专用合成AVD/自有独立前台adb server，一个模拟器；记录真实API/ABI/PAGE_SIZE。现有严格入口执行13+4+2原生方法、storage write/read/confirm/cleanup及production write/read，匹配实际PID/token/唯一terminal/零skip，真实Room/SQLCipher/AndroidKeystore不替换。六阶段是app进程强停恢复，不能冒称Android OS重启或断电。

新任务只监听专用 `device/d02-kvm-acceptance-20261012` 分支的显式请求文件变更，避免旧所有codex push的工程job顺带上传已满artifact。`contents:read`，无main修改/强推/release。源码冻结独审后正常推送一次；普通实现修正不自动重跑，需明确有界故障依据和新请求。并发单实例，不取消未知旧任务。

首轮真实 [CI38242307025](https://github.com/T1doo/AIyou/actions/runs/38242307025) 绑定 `83f6334dfa74b75dd86b86c1d5e4b2f3cbba364b`：实际build/非空domain单测/lint、两固定SDK归档安装和已有协议核验通过；当前UID最小KVM ACL已恢复、协议文件hash不变。`emulator -version` 却选择GUI `qemu-system-x86_64`，动态加载缺 `libpulse.so.0`，exit127；未启动AVD，19+6 **NOT RUN**。完整job日志140076 bytes、SHA256 `9a2431ba6b1f130a24c59d4fbb62a378382e2e0a8a02b6b7bfbbf3dbb9742aa5`，77份文本边界/hash验证已保全。

本次请求attempt2仅修三项preflight加 `-no-window`，与既有boot参数一致；[官方launcher源码](https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android/emulator/main-emulator.cpp) 的 `isHeadless` 与 `qemu-system-%s-headless` 分支提供修正依据，仍以固定37.2.12实际执行为准，不把上游源码当运行通过。请求绑定首轮SHA/run/log hash，经新冻结独审后至多一次有界重跑；不安装系统音频库，不改变SDK/协议、ACL权限范围或产品代码。首轮FAIL保留，后续未运行门槛不改为PASS。

第二轮 [CI38244244711](https://github.com/T1doo/AIyou/actions/runs/38244244711) 绑定 `081288232680a77ee120bf0be987d1cb46ad2d8a`：实际headless37.2.12、KVM12 usable、AVDcreate/GPUhelp已PASS；前台adb `-L tcp:127.0.0.1:<port>` 却报告hostname监听不支持，exit-6，尚未启动模拟器或19+6。完整日志166150 bytes、SHA256 `de9e14a99c2443ede72fe51c1eb13f25f722a0f0e9eb7f27db81609c8796dcbb`，95份文本校验保全；24项自有清理成功、ACL恢复、协议不变。

attempt3只将adb **监听**spec改为 `-L tcp:<port>`，客户端仍明确 `tcp:127.0.0.1:<port>`。[官方AOSP监听实现](https://github.com/aosp-mirror/platform_system_core/blob/android-10.0.0_r47/adb/socket_spec.cpp) 区分监听和连接grammar；未开 `-a` 的无hostname spec调用loopback server，不安装/升级/配置服务或防火墙。历史源码仅作修正依据，实际执行另以既有iproute2 `ss -H -lntp` 核唯一监听地址确为127.0.0.1、PID是本次自有前台adb；检查前后复用原观察的starttime/pidfd绑定，核ppid为自有哨兵、非僵尸、signal0仍活及identity不变，再允许模拟器启动，不能用状态文件exit_code代替内核活性。缺证据/wildcard/别的PID停止。新请求绑定第二轮SHA/run/log hash；新冻结独审后只执行这次有界纠错，两轮FAIL继续保留。

workflow总40min，driver35min含120s自有进程/ACL cleanup；单命令/阶段沿用既有240s ceiling，compile最多900s，boot最多240s，poll和sleep严格夹在boot硬截止内。剩余预算只能收紧，不能增加；实际未结束步骤NOT RUN。失败保全完整文本/partial，不擦未知数据、SDK目录、锁或进程。自有Linux subreaper保持归属，即使命令leader先退出/后代另建session，也只通过pidfd绑定身份终止自有后代并要求清理证明，不杀未知/复用PID或PGID。先实际验证pidfd系统调用、通信可写与READY；父绑定哨兵并确认后才允许command启动。状态发布失败也先清理自有后代，缺完整通信证明仍FAIL。新AVD合成目录/SDK归档留在临时VM用于诊断，不上传密钥、数据库、签名或SDK。

artifact额度已满，不使用artifact/cache上传。每条真实命令记录argv、退出码、时间和stdout/stderr；每阶段文本日志及RESULT使用 `D02_CI_FILE/CHUNK/END` bytes/SHA256/base64边界输出到完整job日志，摘要仅索引/状态。`d02_ci_evidence.decode_files` 拒绝丢块、乱序、重名、未闭合、错误hash；日志不完整不能签PASS。APK只在job内验证/安装，不塞日志；**二进制交付另需正常可用渠道**。

设备结果以实际CI证据为准；编译/收集不是设备执行。即使x86_64/4KB用例通过，16KB实际运行、arm64硬件Keystore/性能、IME/TalkBack/OEM、断电/fsync、真实旧库与JNIString祖源仍保持各自未验门槛。D02不自动全阶段签收，不启动D03。
