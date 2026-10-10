# Private notebook homepage revision — review pending

The user explicitly rejected the first white/blue draft as insufficiently
refined. Neither 217da058 nor its three small refinements at f445f6e5 constitute
user aesthetic acceptance. The parent actually inspected the earlier four PNGs;
that historical absence of obvious clipping does not approve this new revision.

This isolated visual worktree contains synthetic fixtures only. D02 real storage
is separately frozen at 33974f46 for independent review; this visual branch
does not claim to save data or schedule reminders. Original plans are retained.

## Frozen design intent

The actual normal phone Preview is 360 × 800 dp, not a 945 dp wide design.
The returned 945 × 2100 PNG reflects rendering density. The large-font phone
is 320 × 1000 dp with fontScale 2. Standard scale is intentionally recalibrated:
title 24/32 sp, section 18/24, original body 16/24, supporting and controls 14/20.
System fonts, readable dark colors and all existing contrast guards remain.

The top bar has a 56 dp minimum and natural height, light 22 dp navigation line
icons use 1.5 dp strokes, and the three navigation tabs have equal widths,
explicit labels, selected tab semantics and a pale blue selected indicator.
Controls retain at least 48 dp touch height. Safe system insets and IME handling
remain; minimum dimensions never impose a clipping maximum on large text.

Today/date/local processing form a compact opening. The pale blue composer
contains a white selected recording mode, an outlined white input surface and
one aligned attachment/save toolbar. Selected semantics remain without the
redundant visible “已选”. The white input starts at two lines and grows to six
visible editable lines with its normal internal text scrolling.
One complete 14 sp two-line explanation below the composer states that the
synthetic example does not save or schedule reminders and that asking/images/
audio are unavailable. No disabled feature is made operational by appearance.

The next block groups its heading/count and record with a smaller internal gap.
The record uses time/original/local status as supporting metadata, complete
16 sp original text and a flat, explicitly labelled AI-derived annotation.
No original or annotation uses maxLines/ellipsis to obtain a tidier card.
The intention is to show the whole composer and a complete synthetic first
record in a regular first screen; actual rendered pixels must confirm this.

Narrow large-font mode keeps vertical mode controls, attachments in a separate
row and a full-width save action. The page remains a LazyColumn. Actual scrolling,
keyboard, TalkBack and physical touch behavior remain device NOT RUN.
Tokens affect shared components; dedicated visual expansion of the other pages
waits for review of this homepage rather than claiming all pages redesigned.

## Validation boundary

Local PASS: 13 actual Python design/PNG/package contract tests and generated-token
equality. Android compilation, strict lint and all 216 genuine Preview renders
must run in CI on this exact source commit before a visual verdict is made.
Generating fresh reference images is render smoke, not an approved golden test.
Independent source review and the parent's actual new-pixel review remain
required. The cloud workspace has not claimed to view unmaterialized PNGs.

## Bounded follow-up after actual pixel review

CI 38040613367 at `fb0ef4702d39be0c805a0f56a93f63e005797f2a` passed compilation,
strict lint and 216 real renders. The parent and its independent reviewer then
actually viewed the four phone PNGs. They found no definite internal clipping,
overlap or horizontal overflow and presented the revised regular homepage to
the user for layout feedback. This is not final user aesthetic acceptance.

Their bounded feedback retains the homepage structure, removes the selected
mode's second strong outline, and restores one compact wrapping filter group
without individual square surfaces. The outlined white input is retained.
Asking already had `enabled = canEdit && askAvailable` with `askAvailable=false`
on these pages; image/audio require non-null callbacks, which these fixtures
do not provide. Material TextButton supplies disabled semantics and suppresses
pointer activation. Disabled modes now also state their unavailable status;
the visible ask label says “未开放”. Attachment line icons use the neutral
functional outline color while their text stays readable. The full availability
explanation remains. Controls have not become functional through this change.

Local token equality and 13 design/PNG/package checks pass for this follow-up.
Compilation, strict lint and fresh pixels are pending CI on the new commit.
