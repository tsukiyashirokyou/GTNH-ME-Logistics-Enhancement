# ME EU appearance sources

The production textures live in `src/main/resources/assets/gtnhmelogisticsenhancement/textures/`:

- `items/me_energy_monitor.png`: 32×32 RGBA, nearest-neighbour export of `monitor-source.png`.
- `blocks/me_eu_badge{,_active,_high,_high_active}.png`: 16×16 RGBA, recessed metal corner brackets and four ME circuit/status displays with a transparent 8×8 centre. Purple is inactive (including NEI); cyan is AE-online. Amber pips distinguish amperages above the native non-laser port artwork range. Native voltage-coloured EU input/output ports remain visible in the centre.
- `blocks/me_eu_group_{frame,stripe}.png`: 16×16 RGBA, separate perimeter paint channel. The neutral keyline contrasts against both black and white paint, while the white stripe is tinted at runtime using the exact GT dye palette. It is drawn on all six faces after the controller casing and port layers. Unpainted hatches omit it; painted hatches keep it even after joining a multiblock. The centre, ME status lamps and high-amperage markers remain unobscured.

Run `powershell -File art/energy/export.ps1` from the project root to reproduce the PNGs and editable badge SVGs. The script performs only technical resizing of the generated card and rasterizes the code-native pixel corner shapes. It preserves alpha and does not bake upstream GT/AE textures into addon resources.

Use `powershell -File art/energy/export.ps1 -BadgesOnly` to export only the hatch artwork. The metal brackets, dark inset channels and paired status pixels follow the native GT hatch and ME interface visual language. All four MTE classes initialize the shared icon containers from `registerIcons`, before GregTech drains its icon queue for the block atlas. Initializing these containers on the first tooltip or render misses the initial atlas stitch and leaves the custom layer without an icon; merely including its PNG in the JAR is insufficient. The registration regression exercises each class as the first hatch loaded, checks the real icon queue, and repeats registration for resource reload.

All 162 hatch variants compose the actual pinned GT5U voltage-coloured input/output ports, native ME activity overlay and these new corner traces. Energy 2/4/16/64A and dynamo 1/4/16/64A use their corresponding native ports. Dynamo 256A artwork exists only from UV onward: the upstream array's IV–ZPM entries are UV-coloured placeholders, so those ME tiers use their actual 64A ports instead. Higher amperages retain the highest non-laser port with real artwork for that voltage and add amber pips. Exact voltage and amperage remain in the item name and hatch UI. No laser optics are used. These are shared layers, rather than 162 duplicate PNGs.

## Provenance and image prompt

The card source was generated with the built-in ImageGen tool on 2026-11-13, with transparency enabled. The original native AE2 `ToolMemoryCard.png` was supplied as a style/silhouette reference, not as an edit target. Native GT hatch overlays were inspected from the pinned GT5U source/artifact. The card artwork is an original meter/card design; upstream ports are referenced at runtime and remain in their original mods.

Prompt:

> Use case: stylized-concept. Asset type: Minecraft GT New Horizons 1.7.10 inventory item sprite for an ME EU power-network monitor. The supplied AE memory-card texture is a STYLE and silhouette reference, not an edit target. ONE compact front-facing memory-card-sized handheld EU meter/diagnostic card, gray metal AE card inspiration, dark charcoal outline, pale silver stepped edge, tiny teal-blue instrument screen, clear amber/yellow bolt, gold contact teeth at the bottom. Flat orthographic, no perspective, no outside shadows, glow or blur. Crisp 32×32 logical pixel grid, limited palette, centered with one pixel transparent margin. Transparent background, no letters, numbers, watermark, checkerboard, border, captions or extra icons. Intended final 32 pixel asset, hard pixel blocks for nearest size export.

ImageGen returned 1254×1254 RGBA. The 32×32 export samples pixel centres with nearest-neighbour scaling and retains the alpha channel. Previewed at native and enlarged sizes. The editable SVG corner frames are authored directly in the project and exported by the same script. In-game mipmaps, resource packs and item rendering still belong to the user's game acceptance pass.
