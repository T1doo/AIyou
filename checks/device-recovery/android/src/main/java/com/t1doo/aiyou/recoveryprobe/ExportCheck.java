package com.t1doo.aiyou.recoveryprobe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic comparison of the actual post-restore production ZIP to issued fixtures.
 * No production-private directory reads, ciphertext/key export, or text-only hash substitutes.
 */
final class ExportCheck {
    static String sha(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    static JSONObject verify(File file, String imageHash, String waveHash, String original, String transcript) throws Exception {
        Map<String, byte[]> entries = new HashMap<>(); long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || name.contains("..") || name.contains("\\") || name.startsWith("/") || entries.containsKey(name) || entries.size() >= 8)
                    throw new IllegalStateException("ZIP membership invalid");
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                while ((n = zip.read(buf)) >= 0) { total += n; if (total > 2 * 1024 * 1024) throw new IllegalStateException("ZIP bound"); out.write(buf, 0, n); }
                entries.put(name, out.toByteArray()); zip.closeEntry();
            }
        }
        if (entries.size() != 6) throw new IllegalStateException("Exact six ZIP members required");
        for (String name : new String[]{"records.md", "records.jsonl", "human-transcripts.jsonl", "manifest.json"})
            if (!entries.containsKey(name)) throw new IllegalStateException("Required text member missing");
        JSONObject manifest = new JSONObject(string(entries, "manifest.json"));
        if (manifest.getInt("formatVersion") != 5 || manifest.getInt("humanTranscriptCount") != 1)
            throw new IllegalStateException("Wrong explicit transcript format");
        JSONArray inventory = manifest.getJSONArray("files");
        if (inventory.length() != 5) throw new IllegalStateException("Manifest membership differs");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject item = inventory.getJSONObject(i); String path = item.getString("path"); byte[] bytes = entries.get(path);
            if (path.equals("manifest.json") || !names.add(path) || bytes == null || item.getLong("byteSize") != bytes.length || !item.getString("sha256").equals(sha(bytes)))
                throw new IllegalStateException("Actual member/hash differs");
        }
        JSONObject record = single(entries, "records.jsonl");
        if (!record.getString("originalText").equals(original) || record.getString("entryId").isEmpty() ||
            record.getLong("revision") != 1 || !record.getString("contentHash").equals(sha(original.getBytes(StandardCharsets.UTF_8))))
            throw new IllegalStateException("Original Unicode/version/hash differs");
        JSONObject text = single(entries, "human-transcripts.jsonl");
        if (!text.getString("sourceId").equals(record.getString("entryId")) ||
            text.getLong("sourceRevision") != record.getLong("revision") ||
            !text.getString("sourceHash").equals(record.getString("contentHash")))
            throw new IllegalStateException("Human text source binding differs");
        JSONObject range = text.getJSONObject("range");
        if (!text.getString("text").equals(transcript) || text.getInt("sourceFrames") != 44100 || text.getInt("sampleRate") != 44100 ||
            range.getLong("startFrame") != 0 || range.getLong("endFrameExclusive") != 4410)
            throw new IllegalStateException("Human text/exact PCM range differs");
        long images = 0, waves = 0;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().startsWith("images/") && e.getKey().endsWith(".png")) {
                images++; if (!sha(e.getValue()).equals(imageHash)) throw new IllegalStateException("Restored PNG differs");
            } else if (e.getKey().startsWith("audio/") && e.getKey().endsWith(".wav")) {
                waves++; if (!sha(e.getValue()).equals(waveHash)) throw new IllegalStateException("Restored WAV differs");
                if (!e.getKey().equals(text.getString("audioPath")) || !waveHash.equals(text.getString("audioSha256")) || text.getLong("audioByteSize") != e.getValue().length)
                    throw new IllegalStateException("Transcript does not bind the actual original audio");
            }
        }
        if (images != 1 || waves != 1) throw new IllegalStateException("Actual attachment set differs");
        return new JSONObject().put("actualPngSha256", imageHash).put("actualWavSha256", waveHash)
            .put("sourceFrames", 44100).put("rangeStart", 0).put("rangeEndExclusive", 4410).put("members", entries.size());
    }
    private static String string(Map<String, byte[]> entries, String name) {
        byte[] bytes = entries.get(name); if (bytes == null) throw new IllegalStateException("Missing member");
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static JSONObject single(Map<String, byte[]> entries, String name) throws Exception {
        String[] lines = string(entries, name).strip().split("\\R");
        if (lines.length != 1) throw new IllegalStateException("Exactly one logical record required");
        return new JSONObject(lines[0]);
    }
}
