# §5.5 / F09: default period for explicit local recaps

The unchanged Master Plan §5.5 (line 146) and architecture document paragraphs
160–161 list recap preferences. F09 requires user-initiated day/week recaps;
periodic recaps belong to v0.2. The existing controller already supports a date,
ZoneId, DAY/WEEK and a Monday-start calendar week. This slice only saves the
initial DAY/WEEK choice; it does not invent additional recap settings.

Settings offers one labeled radio target for each period, at least 48dp high.
Controls stay disabled until preferences have been read and while saving. The
last confirmed selection remains visible during IO and on failure; retry uses
the existing preference owner. Display switches preserve the confirmed period,
and the period controls preserve both existing display choices. Retained settings
callbacks use the latest confirmed state rather than overwriting unrelated fields.
Disposed settings rows cannot change preferences through retained click callbacks.

The existing ordinary `filesDir/display/preferences` AtomicFile stores this enum
alongside the two display booleans in strict `AIyou display v2` bytes. All four
legacy v1 combinations read as DAY without rewriting the file. Explicit saves
write v2, fsync, finish the atomic write and require exact readback. Unknown or
incomplete data remains an error and is preserved; controls cannot overwrite it.
This is an ordinary preference-file format revision, not a Room schema change.
No date, zone, record content, source proof, consent or secret is persisted here.

Opening the existing manual recap route copies the latest confirmed period once.
Before a preference has loaded, its initial DAY value allows the existing manual
view to remain usable; a late load does not rewrite that open range. The user can
always choose a different period for this view without changing the saved default.
Only a later open adopts a later confirmed preference. Each open still starts at
today in the controller's existing device zone; the date and ZoneId fields remain
explicit per-request inputs and are not saved as preferences.

A visible pre-generation preview and the actual generate action share the same
validated range parser. The preview shows the actual zoned start/end instants,
with an exclusive end, including DST gaps and calendar-day boundaries. Invalid
input has no valid preview and disables generation. Opening, changing a default
or changing inputs never generates a recap. Generation still requires the user's
explicit action and reads current evidence through the existing serial records
owner. A new open, manual range edit, cancel, route exit or disposal advances the
existing generation boundary; old results and source callbacks cannot populate
the new range. Captured-at membership, event-date semantics, source proofs,
complete-result budgets and original record bytes are unchanged.

No scheduled work, notifications, permissions, models, remote processing, new
dependencies, Room schema, background lock or authentication-bound keys are added.
LOCAL_ONLY remains the default. The existing recap is an original-evidence browser,
not a generated AI narrative. Persisted recap history and periodic reminders remain
outside this slice.

Targeted verification covers legacy bytes, confirmed save and new storage/VM
instances, write failure/retry, malformed data preservation, the production settings
and recap route at 320dp/200% font, a late preference load, actual pre-generation
boundaries, canceled/old range results and retained source/open callbacks. The CI
retains the two affected app classes' exact XML from the full app suite, without
running that subset a second time. Host/Robolectric/Compose and Preview results
are not device acceptance. Native SQLCipher/Keystore, actual process restart,
TalkBack, OEM behavior and performance still require device verification.
