# Brand assets

StoreX is the product; Lumora Tech is the company. The mark is a shopping cart inside a circle
over a single underline bar, and the wordmark sets "Store" in near-black with the "X" in brand
blue.

| File                     | What it is                                                                         |
| ------------------------ | ---------------------------------------------------------------------------------- |
| `storex-mark-source.jpg` | The original supplied artwork. 2000×2000, brand blue on opaque white. Do not ship. |
| `storex-mark-512.png`    | Cut out, trimmed, square. The one to use.                                          |
| `storex-mark-256.png`    | Same, for window and installer icons.                                              |
| `storex-mark-64.png`     | Same, for favicons and title bars.                                                 |

## Why the source is not the shipped file

It is a JPEG on an **opaque white background**, and 86% of its area is padding — the mark occupies
754×852 of 2000×2000. Dropped straight into the terminal it renders as a white rectangle floating
on the dark appliance, which is exactly the surface the logo appears on most.

The PNGs are derived from it: cropped to the mark, recentred on a square canvas with even
breathing room, and cut out to transparency. Because the artwork is two-tone, per-pixel coverage
was recovered from the red channel rather than key-colour thresholding, so antialiased edges
survive instead of going crunchy.

## Brand blue

`#0FA0F3` — hsl(202, 90%, 51%). **Sampled from the source file, not eyeballed**: it is the modal
colour over the mark's area at 1,701 pixels, with the neighbouring values (`#0E9FFC`, `#119FF3`, …)
being JPEG ringing rather than real ink.

Do not use it as a button colour. It carries only **2.9:1 against white** and fails AA. See
`../src/tokens.css` for the split — `--lum-brand` for identity, `--lum-accent` (`#0973AF`, same
hue at 36% lightness, 5.2:1) for primary actions on light surfaces, and the unmodified blue on
`[data-surface='terminal']` where it reads 6.2:1 against the dark background.

## Still missing

A **vector** master. Everything here is raster, so the installer icon and any large-format use are
capped at 512px. If an SVG or AI original exists, add it — it should become the source of truth and
these PNGs regenerate from it. There is also no wordmark asset yet; only the mark was supplied as a
file.
