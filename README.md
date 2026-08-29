# ColumnRay

A 2.5D raycasting engine in plain Java (Swing), no external libraries. The map is a 2D top-down
plan and one ray is cast per screen column; every height on screen comes out of the same projection
formula. The demo scene is two storeys of a school: a classroom, corridors, a courtyard with a
pool, and a staircase you can climb to the first floor walkway and look back down from.

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
./run.sh --feet 3.6                        # start on the upper storey instead of the ground one
./run.sh --shear                           # look up / down the old way (y-shearing), for comparison
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

## Where the frame time actually goes

`--bench` times the raycaster only. With both windows open the loop also has to blit the main view
and redraw the ray view, and those dominate. Measured in the real windowed loop, 640x360, both
windows open:

| | before | after |
|---|---|---|
| raycaster | 1.0 ms | 0.9 ms |
| main window blit + HUD + minimap | 6.6 ms | 2.3 ms |
| ray view redraw | 19.4 ms | 5.3 ms |
| **whole frame** | **27.6 ms (36 fps)** | **8.5 ms (117 fps)** |

Closing the ray view with `R` roughly triples it again - it is a debugging window, not part of the
engine. What made it expensive was redrawing several hundred translucent antialiased shapes every
frame; the grid, regions and shape footprints never move, so they are now drawn once into an image
(rebuilt only when you zoom) and blitted with the view transform.

## Anti-aliasing (`--ss`)

`--ss N` renders at N times the output size in both axes and box-filters each N x N block down to
one output pixel. `--ss 1` (the default) is a no-op: the pitch warp (see below) writes straight
into the window image and no downsample runs.

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
- `P`: pitch comparison - look up / down by y-shearing instead of a true tilt (the old way)
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

## Looking up and down

A column renderer can only draw columns that stay vertical, so on its own it can only look up and
down by **y-shearing**: slide the horizon and keep every vertical edge vertical. A real camera that
tilts back sees verticals converge towards the top of the picture. Without that, the top and bottom
of the screen get stretched - looking down from the first floor, the pool comes out far too wide -
which feels like a vertical fisheye.

So the renderer still y-shears, and `Main.warp()` turns the result into a real tilt. Both are
pinhole cameras at the same eye point, one with an upright image plane and one with a tilted one, so
the tilted picture is an exact projective warp of the upright one, and for pitch alone that warp
works row by row. Output row `v` (measured up from the centre) reads a single upright row `v'`,
stretched horizontally about the centre by `s`, where `p` is the pitch:

```
z = F cos p - v sin p      s = F / z      v' = F (F sin p + v cos p) / z
```

Looking up, the top rows have `s > 1` - they need rays outside the normal field of view - so the
renderer draws a slightly wider and taller upright image (overscan), and only the columns and rows
the warp will actually read. At pitch 0 the warp is an exact 1:1 copy: the frame is pixel-identical
to the old y-sheared one.

| 640x360 | rays | ms / frame |
|---|---|---|
| pitch 0 | 644 | 0.82 |
| pitch 30, true perspective | 946 | 1.10 |
| pitch 30, y-shearing (`--shear`) | 644 | 0.69 |

Press `P` to switch to the old y-shearing and compare; `--shear` does the same for `--shot` and
`--bench`. The red line marking the traced column in the main view leans with the tilt: one ray is a
vertical line in the world, so it converges like every other vertical.

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
   -> `rowZ`. The renderer only ever y-shears; `Main.warp()` turns that into a true tilt (see
   "Looking up and down").
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

On a tie the shape wins, so a wall sitting exactly on a region boundary is drawn while the ray is
still in the near region and gets that region's light level and ceiling clip. For that tie-break to
be reachable the shape has to already be in the pending list, which is why shape bounds are grown by
a tiny epsilon before being registered into grid cells (`World.CELL_PAD`): a wall flush against a
cell line belongs to both cells. Without that padding the wall is only discovered one cell later
than the boundary event, and which of the two is handled first comes down to floating-point noise -
visible as vertical streaks of two different brightnesses along a wall seen at a grazing angle.

## Map format

See the comments at the top of `maps/school.json`. In short:

- **Regions** are convex polygons with a `floor` and a `ceil`. Omitting `ceil` (or writing `null`)
  makes the region open to the sky. `top` is the top of the wall above the opening, as seen from an
  open-air region looking in.
- **Shapes** are `wall` (a segment), `circle`, `box`, `poly` or `ngon`, each with `z0` (bottom) and
  `h` (top). `maxDist` stops a shape being drawn beyond a given distance.
- **array** repeats its `items` `count` times, translated by `step` (desks and chairs, colonnades).
- Polygons must be convex; this is checked on load. An L shape has to be split into several pieces.

## Storeys

Regions stack. `World.regionsAt(x, y, out)` returns every region containing a point, lowest floor
first, and a ray carries that whole stack rather than a single region. Two rules follow from it:

- **Anything not inside one of the stack's `[floor, ceil)` ranges is solid.** The gap between the
  ground floor's 3.2 m ceiling and the first floor's 3.6 m floor *is* the slab; nobody models it.
- **A hole is an absence.** The courtyard and the stairwell simply have no upper-storey region, so
  the stack there is one deep and you see - and fall - straight down.

Drawing every storey in the stack needs no depth sorting. For any given row, a higher floor is
always seen at a nearer distance than a lower one, and a lower ceiling nearer than a higher one,
because `t` scales with `|z - eye|`. Segments are handled near-to-far and `paint()` only fills rows
that are still empty, so the surface you ought to see always claims the row first.

Crossing a boundary is one rule: **wall wherever open space on the near side meets solid on the far
side**. That produces a lintel where the far ceiling is lower, a step riser where the far floor is
higher, and the edge of a floor slab seen from either storey. The sky above a region's `top` only
opens up for a viewer who is themselves under open sky - indoors, your own ceiling is in the way.

### Not paying for the other storeys

The grid is 2D, so the cells a ray walks upstairs are full of the furniture downstairs, and vice
versa. Nothing is decided per storey up front - the courtyard proves you *can* see the other floor -
so it is left to the row test: a shape whose every possible row is already painted cannot show, and
is rejected before any intersection (`skip()`). Two things make that test bite:

- **Floors are painted a cell at a time** (`paintAhead`). They used to be painted only when the next
  shape or boundary came along, so crossing an empty room upstairs left the rows your own floor was
  about to cover still open, and the desks below were intersection-tested only to be hidden.
- **Each cell bundles its shapes by the region they stand in** (`World.Group`), so one room's
  furniture in that cell gets a single test on the union of their bounds. Full-height walls belong
  to no single storey and stay on their own.

Neither changes a pixel (the frame hashes are identical before and after, at pitch 0 and +/-30).
Measured per 640-column frame, standing in the first-floor classroom above the ground-floor desks:

| | before | after |
|---|---|---|
| per-shape `skip()` tests | 11,634 | 349 |
| group tests (shapes rejected by them) | - | 3,463 (15,508) |
| ground-floor shapes intersected, all hidden | 7 | 0 |

The same holds looking up from the ground floor. On this two-storey map the saving is below the
noise in frame time; it matters because the cost grows with every storey stacked on the grid.
