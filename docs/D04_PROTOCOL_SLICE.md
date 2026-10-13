# D04 pure JVM protocol/SSE slice — full D04 remains OPEN

Baseline: `74c78326e51f6497306f032730b413c7c94a4b01`, development branch
`codex/d04-jvm-protocol-sse-20261013`. Original Master Plan and Architecture
documents are unchanged. This slice follows Master §§7, 11 and 17 and is
independent of the still **NOT RUN** D02 Android native/cold-start gates.

## Delivered scope

- Real `core:ai` Kotlin/JVM module, with the existing model/domain architecture.
- Text request descriptions for Chat Completions and Anthropic Messages;
  proxy/version prefixes are preserved without double encoding. HTTPS root
  validation rejects userinfo/query/fragment, traversal and operation URLs.
- Incremental SSE/UTF-8, preview text, cumulative usage, strict protocol
  completion, sanitized failure and cancellation; bounded input/output and JSON
  nesting. No partial stream can become completed memory.
- Bounded HTTP/network failure recommendations including Retry-After seconds
  and HTTP dates. No automatic retry or provider fallback.
- Independent synthetic wire contracts and a standalone Gradle entrypoint that
  references the actual production modules, rather than copied implementations.
- JUnit XML method-inventory verification, execution input fingerprints and
  generated dependency locks. Existing model/domain lockfiles are preserved.

The module does not send HTTP, handle API keys, integrate App configuration or
Keystore, run model capability probes, create tasks, save AI output, or process
multimodal/tool content. Two real Mock HTTP server/transport contracts, connection
configuration and secret integration remain future acceptance work. Synthetic
wire fixtures do not satisfy those gates or close full D04.

## Reproduction and evidence

Toolchain: official Temurin17.0.17+10, Gradle9.8.1 and existing Kotlin2.4.21 /
JUnit4.13.2. The new JSON dependency is pinned to kotlinx.serialization1.9.0,
Apache-2.0; all actual compile/test transitive dependencies are lockfile-backed.

Official archive checksums reused from BUILD.md:

- JDK tar SHA256: `992f96e7995075ac7636bb1a8de52b0c61d71ed3137fafc979ab96b4ab78dd75`
- Gradle distribution SHA256: `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`
- Wrapper JAR SHA256: `3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`

`scripts/jvm-gradle.py` verifies the compiler and Wrapper, reuses existing proxy
and system Java truststore, and does not initialize Android state, install SDKs,
accept licenses, configure credentials or alter network/security permissions.

```sh
python3 scripts/jvm-gradle.py -p checks/ai-protocol :core:ai:test :core:domain:test --offline --rerun-tasks
python3 scripts/verify-ai-protocol-results.py
```

XML: `core/ai/build/test-results/test/TEST-*.xml` and
`core/domain/build/test-results/test/TEST-*.xml`. Verified inventory, XML hashes
and source fingerprint: `checks/ai-protocol/build/reports/verified-contracts.json`.
Runtime evidence is ignored build output, not committed as fabricated results.

Initial runner configuration failed because Gradle9.8.1 requires an existing
intermediate `:core` directory. Mapping it to the actual repository `core/`
corrected that failure; actual module compilation then passed. Initial 90 AI
and 3 existing domain tests passed. Independent review identified and corrected
empty control-event handling, error/DONE completion, final Anthropic usage-only
updates and decoder cleanup; each has a result-based regression. Two additional
request tests cover outbound Unicode integrity and token budget field choice.
The final main integration and independent offline rerun both passed 92 AI tests
(18 request, 19 SSE framing, 41 protocol streams, 14 failure policy) and 3 existing
domain tests, with zero failures/errors/skips. The independent rerun executed all
10 tasks; its before/after source fingerprint was unchanged:
`4b10f7d6a3b526122cc8bdc3482140dd1f6698ebb32ec77589126e61aa59dd0f`.
No source/build/lock files change during the frozen
compile/test runs; the test-task fingerprint is an additional stale-output guard,
not a standalone proof of compiler provenance outside that controlled execution.

Existing CI now runs this real JVM/domain entrypoint before Android checks; no
new device workflow, installer, permission or real model request is introduced.

Android assemble/metadata/lint/device checks were **NOT RUN** in this SDK-less
environment. No APK is produced by this JVM slice. D02 stays NOT RUN, full D04
stays OPEN, and no real API/paid/model/credential/ADB action was performed.

## Protocol sources

- [Master Plan](AIyou_Master_Plan.md), §§7/11/17.
- [WHATWG SSE](https://html.spec.whatwg.org/multipage/server-sent-events.html).
- [OpenAI Chat streaming](https://developers.openai.com/api/docs/guides/streaming-responses).
- [Chat request fields and token budgets](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create).
- [Anthropic Messages streaming](https://platform.claude.com/docs/en/build-with-claude/streaming).
- [Anthropic stop reasons](https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons).
- [Pinned JSON POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.9.0/kotlinx-serialization-json-jvm-1.9.0.pom).
