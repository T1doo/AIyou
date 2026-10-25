# Companion artwork in this development slice

The production `CompanionEntry` and `CompanionDialog` now use the bundled
`LuluMascotAssets` raster illustration. `NeutralCapybara` remains only as the
replaceable geometric fallback and in the explicitly separate component demo.

## Source and authorization

The user supplied 17 reference pictures of 水豚噜噜 and explicitly confirmed
authorization to use the character in the project. On 2026-10-23 they asked to
replace the geometric placeholder with AI-generated artwork faithful to those
references. They accepted the first generated direction and requested a smaller
head; this asset is the subsequent proportional revision.

The illustration was generated and then edited with Codex's image-generation
tool, using the user's reference images. It is not a traced Canvas/SVG drawing,
an official artist-delivered asset, or a claim that the character is open source.
The user's original screenshots, surrounding phone UI, and other private
reference material are excluded from the repository and APK.

- Asset: `core/designsystem/src/main/res/drawable-nodpi/lulu_hello.png`
- Format: 1254 × 1254 RGBA PNG, transparent background
- Bytes: 798797
- SHA-256: `34ee4a54e3a778c5ef31e030f9f3ee65c3eb6f7a5004fcd25b10a51c70b409bf`
- Pose: seated greeting; expression does not assert any record-processing result

## Explicit local interactions

Four additional reference-faithful poses were generated with Codex's image tool
and visually reviewed on 2026-10-23 under the same project authorization:

| Bundled PNG | Bytes | SHA-256 |
| --- | ---: | --- |
| `lulu_wave.png` | 903871 | `bb8c267d0d312d93a120c11fd4a5a1f7243fd7ed1d1ada6ebf4b23d4ac1c6232` |
| `lulu_encourage.png` | 883239 | `9cc2e5483f1d4f1d9abe9a4a76937d3d243ef981204ec437a75907a1e0371f6d` |
| `lulu_think.png` | 816756 | `ac2d3933539ba0cd6558cca40d4612bb91037aa0e6c57a5f99af07265f972b98` |
| `lulu_yawn.png` | 768364 | `dc1d45564da0ba8ceccf3fdde67cd255c1a882eba53daa4a16e22b1acdf287f9` |

All four are unchanged 1254 × 1254 RGBA originals in the same drawable-nodpi folder.
These are distinct still poses, not consecutive animation frames. Their full
canvas and alpha remain unchanged; very-low-alpha edge noise is present in the
originals. User-triggered motion is a finite Compose transform, not proof of
anatomically continuous animation or device smoothness. Thinking and yawning are explicit local companion actions; neither indicates
model processing, infers tiredness, or reads the contents of a record.

`CompanionPose` is separate from record-processing `MascotState`. The interaction
panel can only greet, encourage, think, yawn, return to quiet, or close; it receives no record,
task, model, permission, or storage ports. Reduced-motion mode shows a static pose
briefly; backgrounding or closing retires the current action without replay.

## Runtime boundary

`MascotAssetProvider.Artwork` receives presentation state only. The illustration
cannot read or change records, tasks, permissions, or processing policy. The
bundled bitmap is decoded at a bounded size, without a network image loader.
The user can hide the companion and reduce decorative motion; the existing
system-animation, foreground, and power-save checks remain in force.

This development integration does not authorize app-store publication or change
any existing licence obligations. No publication is part of this slice.
