# ColumnRay

A 2.5D raycasting engine in plain Java (Swing), no external libraries. The map is a 2D top-down
plan and one ray is cast per screen column; every height on screen comes out of the same projection
formula. The demo scene is part of floor F1 of a school: a classroom, corridors, a courtyard with a
pool, and a staircase.

## Running

Needs JDK 21 or newer (developed on JDK 26).

```bash
./run.sh                                   # open the windows, load maps/school.json
./run.sh maps/school.json --shot a.png     # headless, write one screenshot (from the spawn point)
./run.sh --shot a.png 16 21.6 -100 5       # pick the position x y, heading and pitch (degrees)
./run.sh --shot a.png 16 21.6 -100 5 100   # one more number: which column the ray view traces
./run.sh --bench                           # spin on the spot and time the renderer
./run.sh --size 1280x720                   # output resolution
./run.sh --ss 2                            # 2x supersampling (anti-aliasing)
```

One ray is cast per rendered column, so **the rendered width is literally the ray count**:
`--size` sets the output resolution and `--ss N` renders at N times that and averages back down,
which means `rays per frame = width x N`. Both combine with `--bench` and `--shot`.

Measured on an Apple Silicon Mac, whole frame including the downsample, excluding the blit to the
window:

| Rays (WxH) | ms / frame | fps |
|---|---|---|
| 320x180 | 0.23 | 4340 |
| 640x360 (default) | 0.65 | 1540 |
| 1280x720 | 2.23 | 450 |
| 1920x1080 | 5.11 | 196 |
| 2560x1440 | 9.50 | 105 |
| 3840x2160 | 23.7 | 42 |
| 7680x4320 | 120 | 8 |

There is no hard limit on the ray count beyond memory (the pixel buffer is `W * H * 4` bytes) and
the 16384x16384 sanity clamp. The *useful* limit for raw detail is one ray per horizontal pixel of
the window - past that, extra rays stop being new detail and become anti-aliasing, which is what
`--ss` is for.

## Anti-aliasing (`--ss`)

`--ss N` renders at N times the output size in both axes and box-filters each N x N block down to
one output pixel. `--ss 1` (the default) is a true no-op: the renderer writes straight into the
window image, no copy, byte-identical to having no supersampling code at all.

| Output | `--ss` | Rays | ms / frame | fps |
|---|---|---|---|---|
| 640x360 | 1 | 640 | 0.65 | 1550 |
| 640x360 | 2 | 1280 | 2.64 | 378 |
| 640x360 | 3 | 1920 | 6.39 | 157 |
| 1280x720 | 1 | 1280 | 2.88 | 347 |
| 1280x720 | 2 | 2560 | 11.7 | 86 |
| 1920x1080 | 2 | 3840 | 29.0 | 34 |

Cost scales with N squared, as it must - `--ss 2` is four times the rays. The clearest wins are
rooflines against the sky and the speckle in the ceiling-panel and stone textures, which alias
badly without it.

`--shot` also writes `a-rays.png`, the matching ray view.

Controls: WASD to move, drag the mouse or use the arrow keys to look (pitch is clamped to +/-30 deg),
Q / E to turn, Space to jump, C to crouch, Shift to run, M to toggle the minimap, Esc to quit.

- `[` `]` (or `-` `=`): change the field of view
- `F`: fisheye comparison - project by straight-line distance instead (the wrong way, on purpose)
- `R`: show / hide the ray view

## The ray view

The second window is a top-down map. The player always points up, so left and right there match
left and right in the main view.

- **Yellow fan**: one line per column, drawn out to the point where that column became full. This is
  where you can see each ray stop.
- **Pale yellow cells**: the grid cells the red ray's DDA walked. Only shapes registered in those
  cells are ever tested.
- **Cyan**: the camera direction and the camera plane, plus the **perpendicular distance** t to the
  first thing the red ray actually draws.
- **Red line**: the column the mouse is hovering in the main view (the main view marks the same
  column with a thin red line). Everything the ray runs into along the way is numbered: an orange dot
  is a shape that got drawn, a grey dot a shape that was already hidden, a blue square a crossing
  into another region. The list at the bottom gives every event in distance order and how many rows
  it actually filled.

Controls: mouse wheel to zoom, `N` to switch between "follow the player's turn" and "north up".

## Fisheye

Projection always uses the perpendicular distance t - the length of the hit point projected onto the
view direction - so there is no fisheye distortion. Press `F` to switch to straight-line distance and
compare. Things near the edge of the screen do look wider, which is what an ordinary perspective
projection does; the wider the FOV the more obvious it gets, so try turning it down with `[`.

## Files

| File | Contents |
|---|---|
| `src/engine/Renderer.java` | camera, projection, DDA grid walk, interval filling, region boundaries, shading |
| `src/engine/Geometry.java` | ray vs segment / circle / convex polygon; distance helpers for collision |
| `src/engine/World.java` | region and shape data, JSON loading, acceleration grid, point queries |
| `src/engine/Materials.java` | procedural textures (to be replaced by image textures and normal maps) |
| `src/engine/Main.java` | window, input, player physics (steps, jumping, crouching), HUD, minimap |
| `src/engine/RayView.java` | the top-down ray view window |
| `src/engine/Json.java` | minimal JSON parser (`//` comments allowed) |
| `maps/school.json` | the demo map |

## How it works

1. **Camera**: `r = dir + plane * camX`, `PL = 0.66`, `F = (W/2)/PL`. r is deliberately not
   normalised, so the intersection parameter t *is* the perpendicular distance and there is no
   fisheye. -> `Renderer.Column.render`
2. **Projection**: `rowZ(z, t) = hz - (z - eye) * F / t`, with `hz = H/2 + pitch` (y-shearing).
   -> `rowZ`
3. **Intersection**: segments, circles (enter t1, exit t2), convex polygons (test every edge, take
   the smallest and largest t). A negative t1 means the eye is inside the shape's footprint.
   -> `Geometry`
4. **Interval filling**: each column tracks which rows are still empty; things are drawn near to far
   and only paint into empty rows, and the column stops as soon as it is full. -> `paint`
5. **Three parts per shape**: the side, the top face (when the eye is above it) and the bottom face
   (when the eye is below it). -> `drawHit`
6. **Floors and ceilings**: invert the projection to get a distance from a row,
   `t = (eye - z) * F / (y - hz)`, then use `pos + r * t` for the world position to texture with.
   Ceilings clip anything taller than them (`min(h, ceil)`). -> `surfaces`, `flat`
7. **Region boundaries (portals)**: when the ray crosses into the next region, compare the heights on
   both sides. A lower ceiling on the far side becomes a lintel, a higher floor becomes a step riser.
   Open-air regions (the courtyard) have no ceiling, so you see sky. -> `cross`
8. **Shading**: a segment's normal is its edge vector rotated 90 degrees, a cylinder's is
   (hit point - centre) / radius; both are then multiplied by distance fog. -> `lambert`, `fog`

### How shapes and regions get ordered

The DDA steps one cell at a time and adds that cell's shapes to a pending list. By the time the ray
reaches the cell's exit distance `tout`, the order of everything with `t1 <= tout` is final: a shape
that is only discovered in a later cell must enter beyond that point. Those shapes and the
"cross into the next region" events are then interleaved by distance, and the floor and ceiling of
the current region are drawn for the stretch between each pair of events. That is why the floor in
front of an object is drawn first and the floor behind it is hidden by the object.

## Map format

See the comments at the top of `maps/school.json`. In short:

- **Regions** are convex polygons with a `floor` and a `ceil`. Omitting `ceil` (or writing `null`)
  makes the region open to the sky. `top` is the top of the wall above the opening, as seen from an
  open-air region looking in.
- **Shapes** are `wall` (a segment), `circle`, `box`, `poly` or `ngon`, each with `z0` (bottom) and
  `h` (top). `maxDist` stops a shape being drawn beyond a given distance.
- **array** repeats its `items` `count` times, translated by `step` (desks and chairs, colonnades).
- Polygons must be convex; this is checked on load. An L shape has to be split into several pieces.
