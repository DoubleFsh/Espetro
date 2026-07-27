# Squad Wiki asset notices

The following UI reference assets were downloaded from the user-specified
Squad Wiki on 2026-07-18. They are kept isolated under
`assets/espetro/textures/gui/squad/` so a server pack can replace or remove
them without changing gameplay code.

| Local file | Source page | Direct media |
| --- | --- | --- |
| `radio.png` | https://squad.fandom.com/wiki/File:Icon_RadialRadioHubIcon.png | https://static.wikia.nocookie.net/squad_gamepedia/images/5/51/Icon_RadialRadioHubIcon.png/revision/latest?cb=20240318060756 |
| `hab.png` | https://squad.fandom.com/wiki/File:Icon_RadialHAB.png | https://static.wikia.nocookie.net/squad_gamepedia/images/7/70/Icon_RadialHAB.png/revision/latest?cb=20240319020747 |
| `ammo_crate.png` | https://squad.fandom.com/wiki/File:Icon_RadialAmmoCrateIcon.png | https://static.wikia.nocookie.net/squad_gamepedia/images/4/49/Icon_RadialAmmoCrateIcon.png/revision/latest?cb=20240319014936 |
| `construction_supply.png` | https://squad.fandom.com/wiki/File:Icon_Supplies_construction_square.png | https://static.wikia.nocookie.net/squad_gamepedia/images/8/81/Icon_Supplies_construction_square.png/revision/latest?cb=20240319015906 |
| `ammo_supply.png` | https://squad.fandom.com/wiki/File:Icon_Supplies_ammo_square.png | https://static.wikia.nocookie.net/squad_gamepedia/images/e/e6/Icon_Supplies_ammo_square.png/revision/latest?cb=20240319015818 |
| `rally.png` | https://squad.fandom.com/wiki/File:Rallypoint.png | https://static.wikia.nocookie.net/squad_gamepedia/images/e/e8/Rallypoint.png/revision/latest?cb=20240210120143 |

The files were converted from the CDN's WebP response to PNG without visual
modification. Fandom states that non-text media may have file-specific terms
instead of automatically inheriting the wiki text license. Review each linked
file page before public redistribution.

## Role icons

The role icon source PNGs were supplied by the user from the Squad Wiki's
[Kit Role Selection](https://squad.fandom.com/wiki/Kit_Role_Selection) page on
2026-07-20. The packaged files live under
`assets/espetro/textures/gui/roles/`.

The source artwork was not redrawn. Its flat yellow background was converted
to alpha, the remaining line art was normalized to white, and every output was
resized to a 128x128 RGBA PNG. Packaged slugs are:

`automatic_rifleman`, `crewman`, `grenadier`, `heavy_at`, `infiltrator`,
`lead_crewman`, `lead_pilot`, `leader`, `light_at`, `machine_gunner`,
`marksman`, `medic`, `pilot`, `raider`, `rifleman`, `sapper`, and `sniper`.

The Espetro-only artillery class intentionally uses `sapper`, as selected by
the project owner. As with the other Squad Wiki media, review the linked file
pages before public redistribution.

## Formation selection images

The formation artwork was supplied by the user from the Squad Wiki's
[Factions](https://squad.fandom.com/wiki/Factions) page on 2026-07-20. The
original files remain unchanged in the user's source folder. The packaged
copies were resized proportionally into a 512x270 canvas-sized texture without
cropping or redrawing:

| JSON formation | Source file | Packaged texture |
| --- | --- | --- |
| `pla_heavy_brigade` | `PLAAGF.png` | `assets/espetro/textures/gui/factions/plaagf.png` |
| `us_cavalry` | `USarmy.png` | `assets/espetro/textures/gui/factions/us_army.png` |

Other built-in formations deliberately have no image mapping yet. Their cards
keep the same dimensions and show the in-game fallback text requested by the
project owner. A server pack can configure more images with the faction JSON
field `selection_image`.


## Map markers (mark1)

Squad-style map icons from the user pack under `/home/shu/下载/mark1` are packaged into ESPoints
`assets/espoints/textures/gui/map/` (radio/hab/hab_activated/mainspawn plus map_* set).
Attack/defend tactical markers use generated `mark_attack.png` / `mark_defend.png`.

## Role icon reprocess

`/home/shu/图片/Icon/*.png` yellow backgrounds were chroma-keyed to alpha and line art normalized
to white (128×128), then written back and packed into `assets/espetro/textures/gui/roles/`.
