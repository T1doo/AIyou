# Protocol read-boundary correction

Parent review found a P1 in the first JVM slice at
`d38da8041af1329f1f2dde82cd2e09e5504bcc7c`: the adapter processed frames only
after the decoder had validated the whole caller read. A valid terminal followed
by invalid UTF-8 or a long line failed if coalesced, but completed if the suffix
arrived in a later push. Already decoded preview frames also disappeared on a
late failure in the same batch.

## Red evidence

Four independent synthetic tests were executed against the unchanged old
production implementation: Chat/Anthropic × invalid UTF-8/513-character tail.
The targeted Gradle command exited 1: 4 assertions failed, zero errors/skips.
Prefix-only and terminal-boundary splits had Started/Text/Usage/Completed;
single-read variants had only INVALID_UTF8 or LIMIT_EXCEEDED failure.

Observed stdout, captured inputs, assertions and copied failing XML are retained
in `.local-evidence/d04/partition-red/`. Failing XML SHA256:
`98fdfe220598f2d95fff8e6749944606d21ff6e80dda51ec6db1b17b1fa15ed4`.
These are actual red results, not simulated failure descriptions.

## Chosen terminal policy

The first fully dispatched and protocol-validated terminal event ends this
response prefix. Chat still requires `stop` and `[DONE]`; Anthropic still requires
closed text blocks, accepted stop reason and `message_stop`. The delimiter and
every byte before it are strictly decoded, framed and budgeted. Bytes after it
are ignored in both the same push and later pushes, including malformed UTF-8,
long lines and additional/error events. Failure/cancellation also remain final.

This matches the [OpenAI SDK's explicit DONE break and response close](https://raw.githubusercontent.com/openai/openai-python/main/src/openai/_streaming.py).
[Anthropic describes message_stop as final](https://platform.claude.com/docs/en/build-with-claude/streaming);
this project chooses to close at that application-protocol boundary. Anthropic's
[general raw SDK stream](https://raw.githubusercontent.com/anthropics/anthropic-sdk-python/main/src/anthropic/_streaming.py)
continues to EOF, so the consumption strategies are not claimed identical.
[WHATWG SSE](https://html.spec.whatwg.org/multipage/server-sent-events.html)
defines blank-line dispatch, not these provider terminal tokens. An undispatched
terminal at EOF is still truncated; invalid pending UTF-8 at EOF still fails.

## Implementation and evidence boundary

The decoder visits frames in wire order through a callback and uses a fixed
four-byte/two-char incremental UTF-8 buffer. It stops at the dispatched terminal
delimiter, without decoding or precharging the rest of a read. Before-terminal
errors preserve already dispatched events and append a sanitized failure.
ProtocolStream normalizes arbitrary caller reads into bounded decoder windows;
the standalone raw decoder's batch limit remains unchanged. Total-byte, line,
event, frame, JSON-depth and text limits remain strict before the terminal.

Tests compare complete typed event summaries for identical wire bytes under
different partitions, with before/after-terminal, cancellation and EOF controls.
Main integration and the first independent rerun both passed 113 cases. A final
internal close-state guard additionally ensures a callback that closes the raw
decoder cannot continue consuming even if it returns true. Its direct regression
and the full frozen offline rerun passed **111 AI + 3 domain = 114** cases, zero
failures/errors/skips, with all ten tasks executed. Before/after source fingerprint:
`c51d53fc035b52522787b4e00915a1c49fc18e34cab299c41ea4f89e3e786f63`.

Final stdout, copied XML, input captures and independent verification are retained
in `.local-evidence/d04/partition-final-independent/`; red and intermediate green
evidence are preserved separately. Exact corrective commit is reported in the
task delivery. The previous 95-case/CI115253926169 result belongs
to the first slice, not this corrective source. Existing CI was not rerun.

The next HTTP/Mock-server preparation is specified separately in
[D04_HTTP_BOUNDARY.md](D04_HTTP_BOUNDARY.md). No real API, key, App, Android/device
or permission change is part of this correction. D02 remains NOT RUN; full D04
remains OPEN.
