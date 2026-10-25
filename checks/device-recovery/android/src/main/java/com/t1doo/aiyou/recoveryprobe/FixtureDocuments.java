package com.t1doo.aiyou.recoveryprobe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** A real, local SAF provider belonging only to the independent probe APK.
 * It never substitutes Activity results, accesses AIyou's private directory, or holds passwords.
 * Roots are absent until the admitted instrumentation explicitly prepares synthetic fixtures.
 */
public final class FixtureDocuments extends DocumentsProvider {
    static final String TITLE = "AIyou D08";
    static final String BACKUP = "recovery.aiyoupk";
    static final String EXPORT = "verified.zip";
    private static volatile boolean armed;
    private static File directory;
    private static Uri image;
    private static String token;
    static final String ORIGINAL = "【合成恢复】我的常用水杯是绿色的🙂。";
    static final String TRANSCRIPT = "合成转写🙂恢复范围";
    static final String TOPIC = "合成水杯颜色";
    private static final String[] DOC_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES};

    @Override public boolean onCreate() { return true; }

    static synchronized void prepare(Context context, String runToken) throws Exception {
        if (Build.VERSION.SDK_INT != 35 || armed || !runToken.matches("[0-9a-f]{32}"))
            throw new IllegalStateException("Invalid fixture admission");
        directory = new File(context.getFilesDir(), "fixtures-" + runToken);
        if (!directory.mkdir()) throw new IllegalStateException("Fixture directory already exists");
        token = runToken;
        byte[] wave = new byte[44 + 44100 * 2];
        ByteBuffer b = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(wave.length - 8)
            .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
            .putShort((short) 1).putInt(44100).putInt(88200).putShort((short) 2).putShort((short) 16)
            .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(88200);
        try (OutputStream out = new FileOutputStream(new File(directory, "fixture.wav"))) { out.write(wave); }
        Bitmap png = Bitmap.createBitmap(new int[]{0xffe87624, 0xff198b65, 0xff1674bc, 0xffffffff}, 2, 2, Bitmap.Config.ARGB_8888);
        try (OutputStream out = new FileOutputStream(new File(directory, "fixture.png"))) {
            if (!png.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("PNG creation failed");
        } finally { png.recycle(); }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "AIyou-D08-" + runToken + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIyouD08-" + runToken);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        image = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (image == null) throw new IllegalStateException("Own MediaStore fixture unavailable");
        try (InputStream in = new java.io.FileInputStream(new File(directory, "fixture.png"));
             OutputStream out = context.getContentResolver().openOutputStream(image, "w")) {
            if (out == null) throw new IllegalStateException("Own image output unavailable");
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
        if (context.getContentResolver().update(image, values, null, null) != 1)
            throw new IllegalStateException("Own image publication unconfirmed");
        armed = true;
        context.getContentResolver().notifyChange(DocumentsContract.buildRootsUri("com.t1doo.aiyou.recoveryprobe.documents"), null);
    }

    static synchronized void retire(Context context) {
        armed = false;
        context.getContentResolver().notifyChange(DocumentsContract.buildRootsUri("com.t1doo.aiyou.recoveryprobe.documents"), null);
        if (image != null) {
            if (context.getContentResolver().delete(image, null, null) != 1)
                throw new IllegalStateException("Own fixture image cleanup unconfirmed");
            image = null;
        }
    }

    static synchronized JSONObject evidence() throws Exception {
        if (directory == null) throw new IllegalStateException("No fixtures");
        JSONObject result = new JSONObject().put("token", token);
        for (String name : new String[]{"fixture.png", "fixture.wav", BACKUP}) {
            File f = new File(directory, name);
            if (!f.isFile() || f.length() == 0 || f.length() > 80L * 1024 * 1024 + 16384)
                throw new IllegalStateException("Incomplete fixture/backup");
            result.put(name, new JSONObject().put("bytes", f.length()).put("sha256", hash(f)));
        }
        return result;
    }

    static String hash(File file) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) >= 0) d.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : d.digest()) s.append(String.format(java.util.Locale.ROOT, "%02x", x & 255));
        return s.toString();
    }

    private static File file(String id) throws FileNotFoundException {
        if (!armed || directory == null || !(id.equals("fixture.wav") || id.equals(BACKUP) || id.equals(EXPORT)))
            throw new FileNotFoundException("Unissued document");
        return new File(directory, id);
    }
    private static String mime(String id) { return id.equals("fixture.wav") ? "audio/wav" : id.equals(EXPORT) ? "application/zip" : "application/octet-stream"; }
    static File exported() throws FileNotFoundException { return file(EXPORT); }
    static File fixture(String name) {
        if (!(name.equals("fixture.png") || name.equals("fixture.wav"))) throw new IllegalArgumentException("Unissued fixture");
        return new File(directory, name);
    }
    private static void row(MatrixCursor c, String id) throws FileNotFoundException {
        File f = id.equals("root") ? directory : file(id);
        MatrixCursor.RowBuilder r = c.newRow();
        for (String column : c.getColumnNames()) {
            Object v = switch (column) {
                case Document.COLUMN_DOCUMENT_ID -> id;
                case Document.COLUMN_DISPLAY_NAME -> id.equals("root") ? TITLE : id;
                case Document.COLUMN_MIME_TYPE -> id.equals("root") ? Document.MIME_TYPE_DIR : mime(id);
                case Document.COLUMN_FLAGS -> id.equals("root") ? Document.FLAG_DIR_SUPPORTS_CREATE :
                    (id.equals(BACKUP) || id.equals(EXPORT)) ? Document.FLAG_SUPPORTS_WRITE : 0;
                case Document.COLUMN_SIZE -> id.equals("root") ? 0L : f.length();
                case Document.COLUMN_LAST_MODIFIED -> f.lastModified();
                default -> null;
            };
            r.add(v);
        }
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        if (!armed) return c;
        MatrixCursor.RowBuilder r = c.newRow();
        for (String column : c.getColumnNames()) r.add(switch (column) {
            case Root.COLUMN_ROOT_ID -> token;
            case Root.COLUMN_DOCUMENT_ID -> "root";
            case Root.COLUMN_TITLE -> TITLE;
            case Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY;
            case Root.COLUMN_MIME_TYPES -> "audio/wav\napplication/octet-stream\napplication/zip";
            case Root.COLUMN_AVAILABLE_BYTES -> 128L * 1024 * 1024;
            default -> null;
        });
        return c;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        if (!armed) throw new FileNotFoundException("Retired fixture root");
        if (!id.equals("root") && !file(id).isFile()) throw new FileNotFoundException("Missing document");
        MatrixCursor c = new MatrixCursor(projection == null ? DOC_COLUMNS : projection); row(c, id); return c;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) throws FileNotFoundException {
        if (!armed || !parent.equals("root")) throw new FileNotFoundException("Unknown root");
        MatrixCursor c = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        row(c, "fixture.wav"); if (file(BACKUP).isFile()) row(c, BACKUP);
        if (file(EXPORT).isFile()) row(c, EXPORT); return c;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!parent.equals("root") || !((mime.equals("application/octet-stream") && name.equals(BACKUP)) ||
                (mime.equals("application/zip") && name.equals(EXPORT))))
            throw new FileNotFoundException("Only the one owned backup name is admitted");
        try { if (!file(name).createNewFile()) throw new FileNotFoundException("Existing output retained"); }
        catch (java.io.IOException e) { throw new FileNotFoundException("Backup creation failed"); }
        return name;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancel) throws FileNotFoundException {
        if (cancel != null) cancel.throwIfCanceled();
        if (id.equals("fixture.wav") && !mode.equals("r")) throw new FileNotFoundException("Read-only fixture");
        if (!(mode.equals("r") || mode.equals("w") || mode.equals("wt") || mode.equals("rw") || mode.equals("rwt")))
            throw new FileNotFoundException("Unsupported mode");
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
}
