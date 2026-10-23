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

## Runtime boundary

`MascotAssetProvider.Artwork` receives presentation state only. The illustration
cannot read or change records, tasks, permissions, or processing policy. The
bundled bitmap is decoded at a bounded size, without a network image loader.
The user can hide the companion and reduce decorative motion; the existing
system-animation, foreground, and power-save checks remain in force.

This development integration does not authorize app-store publication or change
any existing licence obligations. No publication is part of this slice.
