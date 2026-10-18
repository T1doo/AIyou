# F08: explicit original-edit draft migration

Source: original Master Plan §14.4 and Product Architecture §14, with the original
version/deletion and separate file/SQL transaction requirements. The two original
documents are unchanged. This is a bounded selected-record migration, not a full
library backup or device restart acceptance.

## User contract

The existing encrypted package screen has a default-off choice to include retained
record editing drafts. Enabling it also selects the complete original history of
each selected record. Turning history off clears the edit choice. Current capture
input, unacknowledged editor input, discarded/committed edits, model results,
credentials, grants and device keys are excluded. Task proposal drafts retain their
separate existing scope and wording. Limits remain 20 selected records/images,
128 revisions per record / 256 total, the existing 2,129,856-byte metadata cap, 16 MiB per image and
64 MiB total images. Text and all scopes share the existing aggregate metadata
limit: exceeding it refuses the whole package. Draft text is at most 100,000 UTF-16
units per field, including unfinished IME surrogate units without substitution.

Every included draft is authenticated, durable, ACTIVE, based on the selected
current revision, and its original text exactly matches that revision. Preview and
write revalidation compare the complete selected active set, including a draft
created after an initially empty preview. Changed, discarded, committed, deleted,
unreadable or oversized content invalidates the export; nothing is silently skipped.

After authenticating the entire imported package, the user separately confirms
new copies. Original histories/current pointers remain original facts; editing text
is placed in a fresh independent encrypted draft with a fresh entry/draft ID and
version 1. Its base/source revisions are preserved through complete history. The
normal recovery list is refreshed, and the user explicitly resumes and saves the
next version. No current capture input, existing editor session, existing record,
newer revision or unrelated draft is replaced. New copies retain the existing
AI_NONE restore policy; ordinary capture still defaults to LOCAL_ONLY. No models,
network authority, permissions, dependencies or Room schema are added.

For the first local import, every locally present selected source record must be
live and at the package's exact current version, including records with no active
draft. A source with a draft must be at the draft's exact base,
and its complete durable edit snapshot must still equal the package snapshot. A
missing/different/terminal draft refuses old input. If no source row exists, any
local source edit file or permanent-erasure receipt also refuses restoration. A
fresh device with neither source nor local evidence can explicitly restore copies;
an offline archive cannot know subsequent changes/deletions made on another
device. The screen asks the user to review every draft.

## Format and consistency

Package format v7 wraps the existing authenticated v4–v6 history metadata and adds
the explicitly selected editing scope. Existing v1–v6 read/write behavior remains
unchanged when this option is off. Existing PBKDF2/streamed AES-GCM framing and
terminal/EOF authentication are reused. Edit fields use bounded UTF-16 code units,
matching the production encrypted edit file protocol; no plaintext disk cache.

The existing `record_package_receipts` journal gains an internal plan version 5,
holding stable destination IDs and mapped draft digests, not draft plaintext. The
file lease order is MEDIA → EDITS → SQL, matching permanent deletion. Complete
authenticated device ciphertext and directory sync precede the single SQL commit
for entries, revisions, READY attachments, package receipt and plan DONE. Source
version/deletion is checked again inside that final transaction. The formal editor
also uses EDITS → SQL, checking the exact existing formal commit receipt before
requiring an active draft so lost acknowledgements remain idempotent.

Recovery holds both file leases, rechecks an unchanged PENDING plan, absence of the
final receipt and every destination row, and only then removes registered matching
files and changes the plan to ABORTED. Draft canonical files require full expected
identity/digest authentication. Deterministic draft partials require matching
CREATE_NEW inode/nonce ownership proof or full authentication; unknown/replaced
files are preserved and fail visibly. Missing files still cross a directory sync
barrier. Explicit retry uses the original mapping. No file IO occurs inside the
final SQL transaction. A crash before an ownership proof can be acknowledged may
require preserving an unverified partial and reporting recovery failure.

DONE/replayed receipts take precedence over old source eligibility. Replaying
verifies the original immutable record/media mapping without writing, repairing or
recreating any edit draft, including one later edited, committed, discarded or
erased. A lost final SQL acknowledgement therefore cannot create a second batch.

## Verification boundary

Host tests exercise production package encryption/codecs, file leases, SQLite
transactions, controller and ViewModel. The formal revision port in the ViewModel
host test is explicitly the test SQLite adapter; it is not Room instrumentation.
Compose interactions run in JVM/Robolectric. Android native process restart,
actual device lock/restart, SAF providers, accessibility and memory/performance
remain device checks. Local Android SDK terms are not accepted or configured by
this work. Delivery evidence records actual failures, XML, independent review,
exact source CI, branch/remote state and artifact metadata separately.
