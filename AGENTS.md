# AIyou workspace rules

Read `docs/AIyou_Master_Plan.md` and `docs/AIyou_Product_Architecture.docx` before
changing product behavior. Preserve these original design documents.

## Scope and architecture

- Deliver one D00–D09 milestone at a time. D00 is a buildable native Kotlin/Compose
  skeleton with three entry points: 此刻、回忆、待办. Freeze D00 for independent
  review before D01. Do not claim later milestones are implemented.
- `core:model` holds pure Kotlin types; `core:domain` depends only on model.
  Future data and AI modules implement domain interfaces; app wires them together.
- Keep original records, AI-derived content, task proposals and confirmed tasks
  separate. Chat is not automatically a stable fact; AI suggestions cannot execute
  tasks without explicit user confirmation.
- Default processing policy is `LOCAL_ONLY`. Remote processing additionally needs
  matching, current consent. Local saving must never depend on AI or networking.
- No self-hosted backend for v0.1. Later optional network access is user BYOK.

## Assets, security and reproducibility

- Use neutral placeholders and synthetic examples. No 水豚噜噜 assets or role
  implementation without explicit authorization. Use system fonts.
- Never commit real keys, personal records, signing material, local SDKs or logs
  containing secrets. No credential setup, real model calls or TLS bypasses.
- Do not automatically accept new SDK agreements. Show the exact terms and ask
  for explicit approval; verify and reuse existing acceptance when available.
- Pin all tool/dependency versions; use the checksum-verified Gradle Wrapper.
  Keep Java and Kotlin bytecode targets aligned at 17.
- D01 will introduce semantic tokens and component states from the plan. Keep
  D00 placeholders simple and honest about missing functionality.

## Checks and delivery

- Run `:app:checkDebugAarMetadata`, `:app:assembleDebug`, `:core:domain:test`,
  `:app:lintDebug` through the wrapper. Use real assertions and inspect test XML;
  `NO-SOURCE` is not evidence that tests passed.
- Report commands as PASS / FAIL / NOT RUN with limitations. Device UI,
  accessibility, OEM reminders, 16 KB compatibility and performance require
  actual device verification. An APK build is not device evidence.
- Keep failure logs, retry only with a bounded justified correction, never kill
  unknown processes or remove unknown locks.
- Work on a development branch, commit and push normally; do not change main,
  force-push, deploy, publish a release or upload to an app store.
- Delivery includes exact SHA, remote verification, remaining uncommitted and
  unpushed changes, APK path and smallest remaining blocker.
