# 第18次记录：run5 在 workflow 校验阶段失败

已按父批准的精确方案触发一次：run `41426641264`、workflow编号5、attempt1、push SHA `0807b4b74b0ac377fc022f6856653eb054e0aae4`。workflow配置的checkout source为 `908ad0023dd11a2c972d59204487787a2500eb30`，但未执行checkout，不能称该源码已编译。

正式API jobs=0、check-runs=0、artifacts=0；created/updated均 `2026-11-02T10:10:31Z`。GitHub运行页明确报Invalid workflow file：job级env第20/21行的 `runner.temp` 为未识别上下文，graph未生成。

- [正式运行与注解](https://github.com/T1doo/AIyou/actions/runs/41426641264)
- [GitHub官方context availability](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability)：`jobs.<job_id>.env`不允许runner；step env/run允许runner，运行中的step也可使用RUNNER_TEMP默认环境变量。

这是方案准备时漏检的上下文限制，非应用测试失败。未分配runner，新增runner job时间0；总记录从17次变为18次，累计job时间仍7h16m33。所有SDK/源核验步骤、Android编译、domain/lint/权限检查、105 host、12 app及最终XML计数均NOT RUN；195只是预计数，实际本次CI用例0。原本地主机105通过与cf54 Android通过不能用于认证本次。

没有artifact、远端cache写入或APK/package发布；既有四工件76,949,894 B/7天保留不动，不推定账户存储零费。一次准入已消费，未重跑、未推新触发提交、未改变原真实workflow的run5条件。

## 仅准备的修正与待父判定的新准入

`20261102-run5-context-only.patch`只移除job env的两个runner.temp值，在首个preflight Python中用RUNNER_TEMP写GITHUB_ENV，供后续三个Gradle步骤继承。它保持原run5条件，尚未应用。SHA256：`aadf7d90eb7a950c6881a236069c5a61154e8acec40dfd854421d613a829acfc`。

旧run5使用不可变0807b4b workflow，直接rerun仍会读旧错误，且attempt2被原准入拒绝。若父判定可以续一次未启动runner的修正验证，下一次新workflow run预计编号6、attempt1；因此另外准备 `20261102-run6-UNAPPROVED.candidate.yml` / `.patch`，**只是提案，未应用/提交/推送**。

新提案只比0807b4b多上面的环境修正及明确的新编号/消息：

- `github.run_number == 6`，`github.run_attempt == 1`。
- 精确消息 `[frontend-once-no-upload-context-fix] 908ad0023dd11a2c972d59204487787a2500eb30`。
- checkout source仍908ad00；标准ubuntu-24.04、单job20min、contents:read、原action pins、原11个host选择器/12 app/78 domain、无上传/远端cache/scan/发布全不变。
- candidate SHA256：`596e6a974151dfeb830ffa05b734d51af8d3d43b34a5c362ad92d78e3801656b`；patch SHA256：`32fce3a0ed8db9c2bd881eff21a932df961f3444f7b47df03b9e9a6754221901`。

本地静态核验：旧job.env规则准确检出两处错误，修正后该规则通过；嵌入Python仅compile语法检查通过（未执行）；两份patch均git apply --check通过（未应用）。未用GitHub试跑代替校验。父未核准前继续停止执行，原预算/存储冻结不解冻，也不自行把18次记为17次或把零job当作自动重试许可。

台账和这些说明仅在本地更新，未push：当前远端workflow解析无效，推新提交可能再次产生workflow记录；避免以保存说明之名再触发未经批准的运行。

## 后续精确修正授权与执行记录

父随后确认run5属尚未执行的配置失败，明确批准一次且仅一次环境修正及run6/attempt1；未授权第三次执行。正式执行前已再核公开、0开放PR、0在途、最新编号5、原jobs/artifacts均0、源与补丁hash不变。只应用已审run6补丁及必要台账，触发SHA `328ca761a86115cc1a3f1cc7d4a933208c443414`，仍checkout固定908ad00。实际run `41427828677`、job `125218642126`，标准ubuntu-24.04；2026-11-02T10:19:18Z开始。结果和实际时长待完成后记入原台账，不以预计数代写。

文件名中的UNAPPROVED保留为历史提案身份及hash核验依据；这一次后续批准仅对应其中的精确补丁，不解冻其他CI/storage。

修正run6最终success，job实际10:19:18Z–10:30:32Z共674秒；真实XML为25套件195项、0失败/错误/跳过，artifact API为0。总账19次/7h27m47。正式详情在docs/evidence/frontend-day-run6-20261102.json和FRONTEND_DAY_COVERAGE.md；这不使旧run5变为应用测试通过，也不授权再跑。
