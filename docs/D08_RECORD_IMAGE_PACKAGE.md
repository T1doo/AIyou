# F08 / D08: selected current records and original images

This slice extends the existing user initiated encrypted record package, with LOCAL_ONLY processing.
It is not a whole database, history, tasks, candidate history, consent or key backup. Full D08 and
fresh installation/SAF/Keystore/device restart acceptance remain OPEN / NOT RUN.

## Product flow

Select up to 20 current records. Preview full text, manual tags, original image MIME, source revision,
count, byte size and SHA-256. Only current-version JPEG/PNG originals are supported. Any missing,
non-READY, corrupt, unsupported or oversized selected media blocks export; it is not silently omitted.
Confirm scope and enter a separate recovery passphrase. The existing content document picker saves
the package and streams a complete ciphertext readback comparison before success. A partial selected
document is retained after cancellation/failure; the app has no document deletion capability.

Restore authenticates the complete package, including a terminal frame and exact EOF, before preview.
A separate confirmation creates text, tags and images as new IDs. Images use current device attachment
encryption, records start AI_NONE, and no connection, consent or key is imported. Reconfirming the same
backup uses its first stable receipt and never overwrites edits, recreates removed images, or revives
trashed/permanently deleted records. File provenance is not proof of author identity.

## Bounded v2 format, with unchanged v1 reader

The legacy v1 format retains its 2 MiB envelope bound and parser/KDF/AEAD byte contract. Production
exports v2; production reads v1 and v2. v2 uses a flat binary framing, not ZIP or package-controlled
filesystem paths. No decompression or extraction names exist.

Limits: 20 records, 20 images, 16 MiB per image, 64 MiB combined images, legacy text bound plus
32 KiB metadata, and a fixed total file upper bound. The complete package is never materialized in
memory. One bounded original image is authenticated/processed at a time; existing attachment storage
itself authenticates bounded complete images. Selected document ciphertext readback uses 8 KiB.
No plaintext temporary files or durable package passphrases/keys are written.

Header: `AIYOUPK2`, format=2, PBKDF2 iteration count=600000, random 16-byte salt, random 8-byte nonce
prefix. The existing maintained JCA PBKDF2-HMAC-SHA256 (256 bits) and AES-256-GCM (128-bit tag) are used.
Frame nonce is that prefix followed by the checked 32-bit frame index. Every frame AAD contains the
header, manifest binding (zero for metadata), and index. Metadata frame is index 0; image frames are
1..N; terminal frame N+1 contains the 32-byte metadata SHA-256. Metadata declares v2/schema1 scope and
exclusions, embeds the unchanged bounded v1 plaintext record descriptor (including algorithm/KDF
parameters and per-record hashes), and lists source ID/revision, original attachment UUID, allowlisted
MIME, exact length and SHA-256 for every image. The outer scope includes images; the inner v1 record
descriptor describes only the text/tag subpayload. The metadata hash is the stable package digest,
binding image metadata and all original byte hashes independently of fresh encryption randomness.

All lengths/counts are bounded before allocation; the first frame length is checked before KDF.
Descriptors are validated before image allocation. MIME must also agree with PNG/JPEG signatures.
Each frame must pass AEAD, original length and hash checks. No capability is released until all frames
and exact EOF authenticate. Import reopens and reauthenticates the selected document under the same
header and digest; replacement/truncation cannot commit a prefix. Cancellation retires in-memory keys.

## File / database coordination

The existing encrypted database `record_package_receipts` holds a durable PACKAGE_MEDIA_INTENT with
package digest, stable new record/image IDs, immutable image intents, first import time and phase.
No new schema or device key is needed. A bounded startup scan cleans incomplete PENDING/ABORTED
batches before records are presented. Cleanup only touches exact registered UUID files, authenticates
final ciphertext, verifies owned partial creation proofs, and is reentrant. Unknown/corrupt ownership
fails explicitly without deleting arbitrary files. At most 64 unfinished batches are recovered per
opening; a larger workload fails visibly. At most 4096 batch receipts are accepted.

The existing attachment file lock remains outermost. Each image is reencrypted under a new local ID
and its full new attachment AAD, fsynced, authenticated and atomically renamed; the directory is synced.
Only then does a short SQL transaction insert records, revisions, encrypted manual tags, READY image
rows, origins and final restore receipt, and change the media intent to DONE. File IO does not run
inside this SQL transaction. A database failure rolls back every visible record/attachment row;
registered ciphertext remains recoverable/cleanable. Lost commit ACK is resolved by stable DONE +
group receipt, without restaging files or resetting later deletion/policy/tag changes. Interrupted
uncommitted batches are cleaned on reopen, marked ABORTED, and may be explicitly retried from the
original authenticated document using the same mapping. Missing documents/passwords require the
user to select/authenticate again; unfinished records were never exposed as complete.

Existing permanent erasure fences and package provenance proofs remain authoritative. A deleted ID
cannot be inserted again. A DONE replay never calls the file publisher, so removed data is not
resurrected. The shared repository drains existing file/SQL operations before retiring device keys.

## Verification boundaries

Host tests exercise actual JCA, SQL and encrypted file operations with synthetic content and fault
injection. Compose uses production UI/controllers with explicit test ports; host SQL verifies durable
semantics separately. Existing native tests and new Room wiring require actual Android compilation.
No host or build result establishes real SAF provider behavior, Keystore, fresh installation restore,
TalkBack, OEM behavior, 16 KiB compatibility or device performance.
