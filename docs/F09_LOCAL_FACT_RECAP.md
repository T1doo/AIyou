# F09 / §10.1: explicit local day and week evidence review

Memories offers an explicit local day/week review. The selected ISO date and ZoneId define
calendar boundaries; weeks start Monday. The half-open interval uses each boundary's
`atStartOfDay(zone)`, including DST gaps, repeated hours and skipped civil dates. Membership
uses the persisted recording instant, not an inferred event date or the original capture zone.
The page names this distinction. Dates are bounded to 1900–2100; invalid input fails visibly.

This is a derived browsing view of current visible originals, never an AI summary, stable
fact, task, psychological interpretation or picture description. AI_NONE originals remain
eligible for this explicit local viewing. No models, OCR, network, notifications, scheduled
work, new database schema or persisted recap are introduced.

The same encrypted SQL owner supplies a borrowed FactRecapRepository. A single counted
transaction authenticates each current original hash and current manual-tag AEAD, reads its
visibility generation and same-revision image ledger, and sorts by recording instant then ID.
The complete range has at most 1000 records, 2,000,000 UTF-16 units, 20,000 image descriptors
and 64 descriptors per source. An initial byte bound prevents oversized body loading. A range
that exceeds these limits fails as a whole; no truncated count or false empty result is shown.
Snapshots store only bounded, exact 240-code-point opening excerpts, hashes, metadata and
authenticated tags. Empty image-only bodies remain empty; presentation labels are not originals.

The page groups sources by selected-zone day, shows exact record/image-ledger counts and
manual-tag occurrence counts, and provides original/full-text and same-version image-source
navigation. Image state is explicit; ledger READY does not claim physical file authentication.
Opening an image reuses the existing actual attachment viewer and full file AEAD/hash checks.
No file IO or media lock is taken inside a recap SQL transaction.

Private owner-bound proofs re-read the complete range on publication, source navigation and
reader return. New membership, revision/hash, recording metadata, access generation, manual
tag version/content and image identity/state changes reject old proofs. Trash and permanent
erasure fences exclude records. A fresh revision is never passed off as the old excerpt.
The reader requires the selected source version/hash; unavailable or changed sources show
a visible failure. Its separate LazyListState preserves the review position when returning.

The controller uses the existing serial records IO actor and fresh generation tokens for
each explicit request. Cancel, changed input, route exit, backgrounding and disposal retire
late results. A short ownership gate binds generation changes to final publication and source
navigation; the StateFlow CAS checks generation and current availability again. SQL queries
and queue waiting stay outside the gate. Validation failures cannot retire a newer request.
Cancellation discards publication; the bounded in-flight read releases its
existing transaction normally. The VM clears old visible results before source-affecting
commands and synchronously on source-list/tag/readiness changes. No recap, source text,
tags, range-input undo history or proof is put in a Bundle/Saver, export or plaintext cache.
Retry always reads fresh evidence; storage failures are distinct from a genuine empty range.

Settings now distinguishes the available selected-record/original-image encrypted package
and restoration from full-database backup, recording and system reminders that remain open.

Verification covers actual host SQL and tag cryptography, exact source proof and range
membership, timezone/DST/week boundaries, private controller retirement and actual VM wiring.
Compose checks exercise the production Memories route, reader and image decoder with controlled
ports at 320dp and 200% font. These host checks do not establish native SQLCipher/Keystore,
production process-restart, TalkBack, OEM, performance or full D02/D03/D09 acceptance.
Device execution remains NOT RUN; unfinished milestone acceptance remains OPEN.
