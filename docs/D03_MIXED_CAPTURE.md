# F01 / D03: direct text, mixed and picture-only capture

The production Now route accepts text, JPEG/PNG images, or both. Empty text without images is rejected.
An image-only entry stores its real empty original text; “图片记录” is presentation text only. New entries
are AI_NONE and processing stays LOCAL_ONLY. There is no OCR, audio recording or model invocation.
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
AI_NONE access, initial manual labels, local-index job and a durable CAPTURE_SAVED snapshot digest,
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
