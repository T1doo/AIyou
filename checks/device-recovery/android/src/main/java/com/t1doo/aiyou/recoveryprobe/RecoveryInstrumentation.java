package com.t1doo.aiyou.recoveryprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;

/** One real UI/SAF round trip against the immutable production APK. Self-targeted:
 * production signing keys and private app APIs are neither needed nor accessed.
 * Success requires every checkpoint and exact owned-install cleanup.
 */
public final class RecoveryInstrumentation extends Instrumentation {
    static final String PRODUCTION = "958ab2a08536161d8a0776ef155b6368d7df09c6";
    static final String APK_HASH = "dadce271185cc1ff1e8a577f1986d4b4290fbcb60daf7dd187c7088b5d1b693a";
    static final String APP = "com.t1doo.aiyou";
    static final String TEST_NAME = "freshInstallSafRestoreLookupAndDelete";
    private final JSONArray trace = new JSONArray();
    private final Set<String> packages = new HashSet<>();
    private UiAutomation ui;
    private Bundle arguments;
    private File evidence;
    private String token, apkPath, phase = "admission";
    private long deadline;
    private boolean ownsApp;

    @Override public void onCreate(Bundle args) { arguments = args; start(); }

    @Override public void onStart() {
        boolean passed = false;
        char[] password = null;
        JSONObject result = new JSONObject();
        try {
            token = arguments.getString("run_token", "");
            String budgetInput = arguments.getString("budget_ms", "0");
            if (!budgetInput.matches("[0-9]{1,6}")) throw new IllegalStateException("Invalid bounded budget");
            long budget = Long.parseLong(budgetInput);
            if (!PRODUCTION.equals(arguments.getString("production_sha")) || !token.matches("[0-9a-f]{32}") ||
                budget < 1000 || budget > 960000 || Build.VERSION.SDK_INT != 35 ||
                !Arrays.asList(Build.SUPPORTED_ABIS).contains("x86_64")) throw new IllegalStateException("Admission refused");
            deadline = SystemClock.elapsedRealtime() + budget;
            ui = getUiAutomation();
            AccessibilityServiceInfo service = ui.getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            ui.setServiceInfo(service);
            if (!shell("getprop ro.kernel.qemu").trim().equals("1")) throw new IllegalStateException("Owned emulator only");
            apkPath = "/data/local/tmp/aiyou-d08-" + token + "/app.apk";
            if (!shell("sha256sum " + apkPath).startsWith(APK_HASH + " ")) throw new IllegalStateException("Production bytes differ");
            if (!shell("pm path " + APP).trim().isEmpty()) throw new IllegalStateException("Existing package retained");
            evidence = new File(getTargetContext().getFilesDir(), "evidence");
            if (!evidence.mkdir()) throw new IllegalStateException("Existing evidence retained");
            packages.add(APP); packages.add(getTargetContext().getPackageName());
            addSystemHandler(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"));
            addSystemHandler(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"));
            FixtureDocuments.prepare(getTargetContext(), token);
            byte[] entropy = new byte[24]; new SecureRandom().nextBytes(entropy);
            StringBuilder secret = new StringBuilder();
            for (byte b : entropy) secret.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            password = secret.toString().toCharArray(); Arrays.fill(entropy, (byte) 0);
            install(); launch();
            checkpoint("create-source");
            setField("写下此刻，也可以只添加附件", FixtureDocuments.ORIGINAL);
            click("添加图片"); pickImage();
            requireContains("图片已加密暂存在草稿");
            click("导入原音文件"); document(false, "fixture.wav");
            requireContains("已加入本机草稿");
            click("保存这条记录"); requireContains("已保存在本机");
            click("阅读全文"); requireText(FixtureDocuments.ORIGINAL);
            checkpoint("source-persisted"); screenshot("01-source.png");
            addTranscript(); addFact();
            home(); backup(password);
            result.put("fixtures", FixtureDocuments.evidence());
            checkpoint("backup-acknowledged");
            uninstallOwned(); install(); launch();
            requireContains("还没有记录");
            checkpoint("fresh-reinstall-empty");
            restore(password);
            checkpoint("restore-acknowledged");
            home(); click("回忆");
            setField("关键词或精确片段", "水杯"); requireText("找到 1 条原文");
            click("阅读全文"); requireText(FixtureDocuments.ORIGINAL);
            requireText("记录原文"); screenshot("02-restored.png");
            verifyRestoredDerived();
            result.put("restoredExport", verifyExport());
            home(); click("回忆"); setField("关键词或精确片段", "水杯"); requireText("找到 1 条原文"); click("阅读全文");
            checkpoint("lookup-and-exact-source");
            click("更多工具"); click("移入回收站"); requireText("移入回收站？"); click("确认移入");
            requireContains("已移入回收站");
            home(); click("回忆"); setField("关键词或精确片段", "水杯"); requireText("没有匹配的原文");
            // Also verify derived references disappear, not only the timeline row.
            click("统一关键词找回");
            setField("关键词（最多64字）", "水杯"); click("查找当前内容");
            requireText("当前所选内容没有找到逐字匹配。可修改关键词或范围。");
            setField("关键词（最多64字）", "合成转写"); click("查找当前内容");
            requireText("当前所选内容没有找到逐字匹配。可修改关键词或范围。");
            click("返回回忆时间线");
            screenshot("03-deleted.png"); checkpoint("delete-propagated");
            passed = true;
        } catch (Throwable failure) {
            // No exception message, UI dump, input, password, URI grant or app data in public evidence.
            safePut(result, "failureType", failure.getClass().getSimpleName());
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            try { if (ownsApp) uninstallOwned(); }
            catch (Throwable failure) { passed = false; safePut(result, "appCleanup", "UNCONFIRMED"); }
            try { FixtureDocuments.retire(getTargetContext()); }
            catch (Throwable failure) { passed = false; safePut(result, "fixtureCleanup", "UNCONFIRMED"); }
            try {
                result.put("source_sha", PRODUCTION).put("run_token", token).put("lastPhase", phase)
                    .put("result", passed ? "PASS" : "FAIL").put("trace", trace)
                    .put("deviceScope", "one API35 x86_64 owned AVD; not physical/OEM/16KB/audio/OCR acceptance");
                if (evidence != null) {
                    write("result.json", result.toString(2));
                    write("junit.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"D08PlatformInstrumentation\" tests=\"1\" failures=\"" +
                        (passed ? "0" : "1") + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"" + getClass().getName() +
                        "\" name=\"" + TEST_NAME + "\">" + (passed ? "" : "<failure message=\"" + phase + "\"/>") + "</testcase></testsuite>\n");
                } else passed = false;
            } catch (Throwable failure) { passed = false; }
            Bundle terminal = new Bundle(); terminal.putString("d08_result", passed ? "PASS" : "FAIL");
            terminal.putString("d08_source", PRODUCTION); terminal.putString("d08_token", token);
            finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, terminal);
        }
    }

    private static void safePut(JSONObject result, String key, Object value) {
        try { result.put(key, value); } catch (Exception ignored) { /* terminal still fails */ }
    }
    private void checkpoint(String name) throws Exception {
        limit(); phase = name;
        trace.put(new JSONObject().put("phase", name).put("elapsedRealtimeMs", SystemClock.elapsedRealtime()));
    }
    private void limit() { if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("Probe deadline"); }
    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = ui.executeShellCommand(command);
             InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) >= 0) { if (out.size() + n > 8192) throw new IllegalStateException("Shell output bound"); out.write(buffer, 0, n); }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
    private void verifyOwned() throws Exception {
        if (!ownsApp) throw new IllegalStateException("Not an owned installation");
        String path = shell("pm path " + APP).trim();
        if (!path.matches("package:/data/app/[A-Za-z0-9_~=/.-]+/base\\.apk") || path.contains(".."))
            throw new IllegalStateException("Installed package identity unavailable");
        if (!shell("sha256sum " + path.substring(8)).startsWith(APK_HASH + " "))
            throw new IllegalStateException("Installed APK changed; do not uninstall");
    }
    private void install() throws Exception {
        if (!shell("pm path " + APP).trim().isEmpty()) throw new IllegalStateException("Existing package retained");
        // Record the owned creation intent before a possibly lost installation ACK.
        ownsApp = true;
        if (!shell("pm install " + apkPath).trim().equals("Success")) throw new IllegalStateException("Install unconfirmed");
        verifyOwned();
    }
    private void uninstallOwned() throws Exception {
        if (ownsApp && shell("pm path " + APP).trim().isEmpty()) { ownsApp = false; return; }
        verifyOwned();
        if (!shell("pm uninstall " + APP).trim().equals("Success") || !shell("pm path " + APP).trim().isEmpty())
            throw new IllegalStateException("Uninstall unconfirmed");
        ownsApp = false;
    }
    private void launch() throws Exception {
        verifyOwned(); shell("am start -n " + APP + "/.MainActivity"); requireContains("此刻");
    }
    private void addSystemHandler(Intent intent) {
        ResolveInfo resolved = getTargetContext().getPackageManager().resolveActivity(intent, 0);
        if (resolved == null || (resolved.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0)
            throw new IllegalStateException("Required system picker absent");
        packages.add(resolved.activityInfo.packageName);
    }
    private List<AccessibilityNodeInfo> nodes() {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !packages.contains(root.getPackageName().toString())) return out;
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>(); queue.add(root);
        for (int i = 0; i < queue.size(); i++) {
            if (queue.size() > 2048) throw new IllegalStateException("UI tree bound");
            AccessibilityNodeInfo n = queue.get(i); out.add(n);
            for (int c = 0; c < n.getChildCount(); c++) { AccessibilityNodeInfo child = n.getChild(c); if (child != null) queue.add(child); }
        }
        return out;
    }
    private static String text(CharSequence s) { return s == null ? "" : s.toString(); }
    private AccessibilityNodeInfo find(Predicate<AccessibilityNodeInfo> predicate, boolean scroll) throws Exception {
        long end = Math.min(deadline, SystemClock.elapsedRealtime() + 30000); int moves = 0;
        while (SystemClock.elapsedRealtime() < end) {
            List<AccessibilityNodeInfo> found = nodes().stream().filter(predicate).toList();
            if (found.size() > 1) throw new IllegalStateException("Ambiguous UI selector");
            if (found.size() == 1) {
                AccessibilityNodeInfo n = found.get(0);
                if (n.isVisibleToUser()) return n;
                n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            } else if (scroll && moves < 24) {
                List<AccessibilityNodeInfo> scrollers = nodes().stream().filter(n -> n.isScrollable() && !n.isEditable() && n.isVisibleToUser()).toList();
                AccessibilityNodeInfo largest = null; int area = -1;
                for (AccessibilityNodeInfo n : scrollers) { Rect r = new Rect(); n.getBoundsInScreen(r); if (r.width() * r.height() > area) { area = r.width() * r.height(); largest = n; } }
                if (largest != null) largest.performAction(moves < 8 ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                moves++;
            }
            SystemClock.sleep(80);
        }
        throw new IllegalStateException("UI condition not observed within deadline");
    }
    private void requireText(String value) throws Exception { find(n -> text(n.getText()).equals(value), true); }
    private void requireContains(String value) throws Exception { find(n -> text(n.getText()).contains(value), true); }
    private void click(String value) throws Exception {
        AccessibilityNodeInfo n = find(x -> text(x.getText()).equals(value) || text(x.getContentDescription()).equals(value), true);
        for (int i = 0; i < 8 && !n.isClickable(); i++) { n = n.getParent(); if (n == null) throw new IllegalStateException("No click owner"); }
        if (!n.isClickable() || !n.isEnabled() || !n.isVisibleToUser() || !n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            throw new IllegalStateException("Real click refused");
        SystemClock.sleep(80);
    }
    private void setField(String label, CharSequence value) throws Exception {
        AccessibilityNodeInfo n = find(x -> x.isEditable() &&
            (text(x.getText()).contains(label) || text(x.getHintText()).contains(label) || text(x.getContentDescription()).contains(label)), true);
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (!n.isEnabled() || !n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw new IllegalStateException("Real input refused");
        hideIme();
    }
    private void hideIme() throws Exception {
        for (AccessibilityWindowInfo w : ui.getWindows()) if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
            shell("input keyevent 4"); break;
        }
    }
    private void home() throws Exception {
        hideIme();
        for (int i = 0; i < 8; i++) {
            if (nodes().stream().anyMatch(n -> text(n.getContentDescription()).equals("设置"))) { click("此刻"); return; }
            shell("input keyevent 4"); SystemClock.sleep(100);
        }
        throw new IllegalStateException("Home route unavailable");
    }
    private void pickImage() throws Exception {
        AccessibilityNodeInfo tile = find(n -> text(n.getViewIdResourceName()).endsWith(":id/icon_thumbnail"), false);
        if (!tile.isEnabled()) throw new IllegalStateException("Photo picker fixture unavailable");
        while (!tile.isClickable() && tile.getParent() != null) tile = tile.getParent();
        if (!tile.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException("Photo picker click refused");
    }
    private void document(boolean create, String name) throws Exception {
        boolean rootVisible = nodes().stream().anyMatch(n -> text(n.getText()).equals(FixtureDocuments.TITLE));
        if (!rootVisible) {
            AccessibilityNodeInfo drawer = find(n -> Set.of("Show roots", "显示根目录", "Open navigation drawer", "打开导航抽屉")
                .contains(text(n.getContentDescription())), false);
            if (!drawer.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException("SAF roots unavailable");
        }
        click(FixtureDocuments.TITLE);
        if (!create) { click(name); return; }
        AccessibilityNodeInfo title = find(n -> n.isEditable() && text(n.getViewIdResourceName()).equals("android:id/title"), false);
        Bundle input = new Bundle(); input.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name);
        if (!title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input)) throw new IllegalStateException("SAF filename input refused");
        hideIme();
        AccessibilityNodeInfo save = find(n -> text(n.getViewIdResourceName()).endsWith(":id/button1") && n.isEnabled(), false);
        if (!save.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException("SAF create refused");
    }
    private void openBackup() throws Exception { click("设置"); click("完整备份与恢复"); requireText("完整资料库备份"); }
    private void backup(char[] secret) throws Exception {
        checkpoint("saf-backup"); openBackup(); click("核对完整备份范围");
        requireText("我已核对完整范围、排除项与上限。");
        AccessibilityNodeInfo check = find(AccessibilityNodeInfo::isCheckable, true);
        if (check.isChecked() || !check.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException("Scope confirmation refused");
        setField("独立恢复口令", new String(secret)); setField("再次输入恢复口令", new String(secret));
        click("加密并选择保存位置"); document(true, FixtureDocuments.BACKUP);
        requireContains("完整资料库快照已加密写入，并完整读回核对");
    }
    private void restore(char[] secret) throws Exception {
        checkpoint("saf-restore"); openBackup(); click("选择完整备份恢复"); document(false, FixtureDocuments.BACKUP);
        setField("独立恢复口令", new String(secret)); click("认证并预览范围");
        requireContains("完整备份已认证"); click("确认导入空资料库"); requireContains("已核对完整资料库恢复回执");
    }
    private void addTranscript() throws Exception {
        checkpoint("manual-transcript"); click("查看附件"); click("人工补转写／纠正");
        setField("人工文字（最多1000字）", FixtureDocuments.TRANSCRIPT); click("自设片段");
        setField("起点（秒，最多3位小数）", "0.000"); setField("终点（秒，结束不含）", "0.100");
        click("保存人工转写"); requireText("人工内容版本 1"); click("关闭人工转写");
        click("返回原文");
    }
    private void addFact() throws Exception {
        checkpoint("manual-fact"); click("更多工具"); click("普通事实自述");
        setField("原文片段", FixtureDocuments.ORIGINAL); setField("主题/上下文", FixtureDocuments.TOPIC);
        AccessibilityNodeInfo check = find(AccessibilityNodeInfo::isCheckable, true);
        if (check.isChecked() || !check.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException("Fact affirmation refused");
        click("仅保存为待确认候选"); requireText("确认仅存为待确认候选？"); click("确认这次操作");
        click("审阅并单独采纳"); requireText("单独确认采纳这条普通自述？"); click("确认这次操作");
        requireText("用户已采纳自述"); click("关闭事实自述");
    }
    private void verifyRestoredDerived() throws Exception {
        click("查看附件"); click("人工补转写／纠正"); requireContains(FixtureDocuments.TRANSCRIPT);
        requireText("人工内容版本 1"); requireText("绑定原文版本 1 · 人工片段 · 约 0–0.1 秒（结束不含）"); click("关闭人工转写"); click("返回原文");
        click("更多工具"); click("普通事实自述"); requireContains(FixtureDocuments.TOPIC);
        requireText("用户已采纳自述"); click("关闭事实自述");
    }
    private void toggle(String label) throws Exception {
        AccessibilityNodeInfo n = find(x -> text(x.getText()).contains(label), true);
        for (int i = 0; i < 8 && !n.isCheckable(); i++) { n = n.getParent(); if (n == null) throw new IllegalStateException("Missing checkbox owner"); }
        if (!n.isCheckable() || n.isChecked() || !n.isEnabled() || !n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            throw new IllegalStateException("Fresh explicit confirmation refused");
    }
    private JSONObject verifyExport() throws Exception {
        checkpoint("verify-restored-byte-content"); home(); click("回忆");
        if (nodes().stream().noneMatch(n -> text(n.getText()).equals("资料管理 · 收起"))) click("资料管理 · 展开");
        click("导出原文、待办与草稿"); click("原文与媒体 ZIP");
        toggle(FixtureDocuments.ORIGINAL); toggle("包含当前人工转写与片段范围（明文，可不选）");
        click("预览选定原文"); requireContains("合成转写🙂恢复范围");
        toggle("我确认导出以上快照"); click("选择导出目的地"); document(true, FixtureDocuments.EXPORT);
        requireContains("所选文件已写入并读取核对，与确认快照一致");
        JSONObject verified = ExportCheck.verify(FixtureDocuments.exported(), FixtureDocuments.hash(FixtureDocuments.fixture("fixture.png")),
            FixtureDocuments.hash(FixtureDocuments.fixture("fixture.wav")), FixtureDocuments.ORIGINAL, FixtureDocuments.TRANSCRIPT);
        click("关闭"); return verified;
    }
    private void screenshot(String name) throws Exception {
        if (nodes().stream().anyMatch(AccessibilityNodeInfo::isPassword)) throw new IllegalStateException("Password view screenshot refused");
        Bitmap bitmap = ui.takeScreenshot(); if (bitmap == null) throw new IllegalStateException("Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(new File(evidence, name))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Screenshot failure");
        } finally { bitmap.recycle(); }
        if (new File(evidence, name).length() > 512 * 1024) throw new IllegalStateException("Screenshot evidence bound");
    }
    private void write(String name, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(evidence, name))) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
    }
}
