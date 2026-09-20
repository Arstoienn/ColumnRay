# Engineering notes

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
./run.sh --bench                           # spin on the spot and time the renderer (see ./bench.sh)
./run.sh --size 1920x1080                  # render resolution to start at (default 1280x720)
./run.sh --window 2560x1440                # window size (default 1920x1080); independent of --size
./run.sh --fps 30                          # frame-time target for dynamic resolution; --fps 0 turns it off
./run.sh --ss 2                            # 2x supersampling (anti-aliasing)
./run.sh --feet 3.6                        # start on the upper storey instead of the ground one
./run.sh --shear                           # look up / down the old way (y-shearing), for comparison
./run.sh --flat                            # skip the lightmap bake, use the old flat shading
```

## Controls

`WASD` move, drag the mouse or the arrow keys to look, `Space` jump, `C` crouch, `Shift` run,
`Q`/`E` turn. `R` ray view (closed at start), `M` minimap, `L` baked lighting on and off, `F` fisheye,
`P` pitch model, `[` `]` field of view, `,` `.` render resolution by hand, `V` dynamic resolution on
and off, `Esc` quit.

**Those are places on the keyboard, not letters.** AWT will not say which key was pressed: on macOS
it works out the key code for a letter key from the character that key produces under the current
layout, so on Colemak the key in the S position arrives as `R` and the one in the D position as `S`,
and WASD would answer the wrong way round.

So `Keys` does not go through AWT. macOS gives every key position a fixed number - the scan code the
hardware sends, 13 for the key a US board prints `W` on, whatever a layout later makes of it - and
`CGEventSourceKeyState` answers whether the key at a given number is down right now. The game asks
that once a frame for the keys it cares about, through the FFM API (hence
`--enable-native-access=ALL-UNNAMED` in `run.sh`). Nothing has to know which layout is selected, so
nothing has to keep asking: switch to Bopomofo mid-jump and the same keys keep working, because the
question was never about letters. An input method is kept from swallowing the keys as well, for the
window's sake.

Naming them is the one place the layout still matters, and it goes the other way: given a position,
`UCKeyTranslate` says what this keyboard prints on it, so on Colemak the HUD hint reads `WARS move`.
That is asked once, before AWT starts, because Text Services belongs to the process's first thread
and answers the game loop with a trap rather than an answer.

Asking it that early has a trap of its own. The first call checks the process in with the system,
and a process with no window yet checks in as background-only, a type AWT does not undo when the
window opens. A background-only app can never be the active one: the window came up, clicking it did
nothing, and every key went to whatever app was in front (`lsappinfo` showed `type="BackgroundOnly"`;
with the call skipped, `Foreground`). So `Keys` first declares the process an ordinary app with
`TransformProcessType`, which is what AWT would have done, and only then asks for the key caps.

Reading the machine's key state means reading it whichever of the two windows is focused - but also
whatever else is in front, so the keys are ignored unless one of our windows is the active one. Off
macOS, or on a JVM that will not let us call out, the AWT events come back as a fallback, read as a
plain US QWERTY board.

On macOS the fallback is also what runs when the app that started the game has no Input Monitoring
permission (started from a desktop app rather than a Terminal that has it): the key state then
says "not held" for every key, forever. AWT on macOS names a letter key by what the layout prints on
it and carries no position (a `KeyEvent`'s raw code is only filled in on X11), so on Colemak the S
position arrives as `VK_R`. The key caps read at startup turn that round: `VK_R` is the position that
prints R, which is S, so WASD stays WARS. A letter no tracked position prints (`VK_G` comes from the
T position on Colemak) is ignored rather than filed under its QWERTY place.

One ray is cast per rendered column, so **the rendered width is literally the ray count**:
`--size` sets the output resolution and `--ss N` renders at N times that and averages back down,
which means `rays per frame = width x N`. Both combine with `--bench` and `--shot`.

**The render resolution and the window are two different sizes.** The window is the output -
1920x1080 by default, or `--window` - and the picture is rendered at `--size` (1280x720 by default)
and scaled into it. Making the window bigger does not add a single ray.

### Dynamic resolution

What a frame costs is the pixels it shades, not the rays it casts: on Haven at 1920x1080, tilting the
view 30 degrees takes it from 1924 rays to 2826 (+47%) but the frame only from 75.5 to 86.9 ms (+15%),
and 61% of the time is texture filtering. So the thing to turn is the render resolution, and
`DynamicResolution` turns it from the measured frame time. It moves along eight steps from half the
window's width to all of it (1280x720 in a 1920x1080 window is the fourth), aiming at `--fps`
(60 by default):

- over budget on average over the last 10 frames: one step down, straight away;
- under 70% of the budget over the last 30 frames: one step up;
- after a step, 20 frames are not judged, while the buffers are rebuilt;
- one frame counts for at most 1.5 budgets, so a single hitch (a GC, the OS) cannot cost a step
  on its own but a stall that lasts does.

Down is quick and up is slow on purpose: a stutter is worse than a picture that is a little soft for
a second. The 70% margin stops it climbing a step and falling straight back, since no two
neighbouring steps differ by more than 27% in pixels. `,` and `.` take over by hand (and turn it off);
`V` hands it back. The HUD and the minimap are drawn on the window rather than into the picture, so
they stay sharp at any render size.

Measured with `--bench --flat` on an 8-core Apple M3:

| Map | Render size | ms / frame |
|---|---|---|
| school | 640x360 | 3.05 |
| school | 1280x720 | 12.20 (tilted 30 deg: 17.9) |
| school | 1920x1080 | 31.88 |
| haven | 640x360 | 5.79 |
| haven | 1280x720 | 28.47 |
| haven | 1920x1080 | 75.48 |

The tables further down that give 640x360 as 0.65 ms predate anisotropic filtering and are kept
for the record only; do not measure a change against them.

### Measuring it (`./bench.sh`)

`./run.sh` used to delete the classes and recompile on every run, which put the compiler in every
timing; `build.sh` now only compiles when a source is newer than the last build. `--bench` runs 400
untimed frames first for the JIT, then times every frame of a full turn, level and tilted, and
prints the median and the 99th percentile rather than a mean.

That still leaves the machine. On a fanless MacBook Air six identical runs spread by 39-55%, and
they got slower as it went on: the first three at 10 ms, the last three at 12-16 ms. That is the chip
throttling as it heats, and nothing in the JVM takes it out. `bench.sh` runs a fresh JVM several
times with a rest in between and reports the median of the run medians with the spread, and with
two builds (`CP=old CP_B=new ./bench.sh`) it runs them in turn, A B A B, and gives the median of the
B/A ratios of neighbouring runs, so the heat falls on both sides of the comparison.

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
engine, so it now starts closed and `R` opens it. What made it expensive was redrawing several hundred translucent antialiased shapes every
frame; the grid, regions and shape footprints never move, so they are now drawn once into an image
(rebuilt only when you zoom) and blitted with the view transform.

## Shading on the card (`--gpu`)

`--gpu` moves the *shading* of a frame to the graphics card and leaves everything else exactly
where it was. The CPU still casts one ray per screen column, still walks the acceleration grid,
still tests the shapes and still decides what is visible; what it hands over is the answer - a
handful of row intervals per column - and the card colours them in. The column constraint is not
weakened by this. It is the reason the thing works at all: a column renderer's output is a few
hundred kilobytes of intervals, which is a tiny amount to hand a card, where a triangle renderer
would have to hand it the world.

### What crosses the bus

Two per-column lists and four tables that never change:

| | what it is |
|---|---|
| spans | the wall and plane intervals a column painted, with the few numbers a strip needs |
| masks | the masked surfaces blended over the finished column: trees, and cut-outs sawn from a mesh |
| materials | one record per surface: which image, its mean, and the map from the world to it |
| blends | a second layer, its height map and the mesh's vertex colour, for the few surfaces with them |
| lightmaps | every baked map shelf-packed into one float atlas, with a record saying where each landed |
| images | every image the map uses, as array textures grouped by size |

The lists are held at their worst case - a few hundred entries a column - and a frame uses a
handful of that, so only the part each frame really fills is uploaded (`GpuWalls.pack`). Sending
the whole array was forty megabytes a frame of mostly nothing and measured as most of the pass.

### Sixteen samplers, and what that forced

A fragment shader is promised sixteen texture units and no more. Six are spoken for - spans,
blends, the light atlas, its records, the material table and the masks - which leaves ten for
images. Haven's 452 images come in fifteen distinct sizes, so one array per size does not fit.

An array layer's levels are a mip chain, and a smaller image of the same shape is exactly what
some level of a bigger one looks like, so a 512 square image can live in levels 1 and down of a
1024 square layer and be sampled by asking for a level one deeper. Wrapping still works, because
wrapping happens in normalised coordinates and those do not know which level answered. The cost
is the levels above it, allocated and never read, so a size is folded into a bigger bank only
when the waste of doing so stays under a budget: the 222 images at 512 square would waste 700 MB
in a 1024 square bank and keep their own, the 51 at 128 square waste ten megabytes and do not.
Fifteen sizes become seven banks for about thirty megabytes, and nothing is left behind.

### Proving it draws the same picture

The golden frames cannot answer this. They hash the renderer's own arrays, and a card works in
float where the renderer works in double, so the two will never hash the same. What can be asked
is how far apart the two pictures are, and `engine.GpuCheck` asks it: for each camera it renders
the frame on the CPU with the spans recorded, draws those same spans on the card, and compares
every pixel.

```
./build.sh && java --enable-native-access=ALL-UNNAMED -cp out engine.GpuCheck [map.json] [WxH]
```

| column | what it means |
|---|---|
| `masked` | pixels the card was not given, because something on them is not ported |
| `worst` | the largest single-channel difference anywhere in the frame |
| `over 2` | how much of the frame differs by more than a byte can hide |
| `merged` | the same, for the frame the game actually shows (see below) |

`masked` going to zero is what finishing looks like. It is zero on school and on all six Haven
cameras, and the worst difference is 1 to 3 of 255 with fewer than two pixels in five million
over 2.

The 3 is the mip chain, not the shading. `Materials.Level.half` keeps its area averages as floats
and says in a comment that it does not quantize them; `GpuTextures` rounds them to a byte on the
way up. Uploading the same levels without rounding (`-Dgpu.texels=half` or `=float`) settles it -
on Haven's spot7 the worst goes 3, 2, 2 and the mean 0.055, 0.029, 0.006 for RGB8, RGB16F and
RGB32F, and the pixels over 2 go to none. A worst of 2 is the floor: float against double in the
shader's own arithmetic, which no texture format reaches. With RGB16F all six Haven cameras read
2. The default stays the byte because the difference costs 570 MB of card memory and nobody can
see it, but the switch is there and the number it buys is known.

### The whole loop, not just the renderer

`GpuCheck` compares the renderer's buffer at a fixed size with the camera level. Between that
buffer and the window sit the pitch warp, the overscan growing under it and the card's own
buffers being rebuilt around that, and none of it was covered - which is where the worst bug of
this branch lived, a resize that watched the width and not the height.

`--gpu-verify views.txt` draws each view through `Main.frame` twice, once on each path, at five
tilts, and compares the finished output:

```bash
./test.sh --gpu                                    # school, at the golden size, in seconds
./run.sh maps/haven/haven.json --gpu-verify tests/views/haven.txt --flat
```

The tilts climb, because the overscan only ever grows: each one asks for a taller buffer than the
last and lands on whatever height the warp asks for rather than a round number. Frames are thrown
away at each tilt until the per-column lists stop growing, because those start small and double
when a column runs out, so the first frames at a new tilt hand rows back to the CPU as designed.

Its verdict is about the picture and not about the rounding. The two are never identical, and a
few pixels a frame land exactly on the hard edge of a procedural material - a plank line, a brick
course - where float and double fall on opposite sides and disagree by tens of levels. So the
threshold is on how many pixels disagree, not by how much: school runs at 0.0001% of the frame
over 8 levels and a deliberately broken merge runs at 4.8%, which is the margin the rule sits in.
Entries handed back to the CPU are reported and do not fail it - a drop is the fallback working,
and the run that dropped sixty thousand of them still agreed to within four levels.

### Why the CPU stops shading, and how that is checked

Moving a surface to the card saves nothing on its own: the CPU was still colouring every pixel
and the card's work was added to it, not substituted for it. The saving arrives when the CPU
stops. It cannot simply stop, because a masked surface the card was not given is blended over
whatever the CPU painted underneath and needs something to blend with. So rows the card has are
skipped and rows anything unported will be blended over are not, which a column works out as it
goes (`Renderer.cpuUnder`), and a screenshot still shades everything because its albedo and depth
come from the CPU.

That is a saving that could hide a mistake - a row the CPU skipped that the card turns out not to
have drawn would come back black - so `GpuCheck` renders every camera a second time with the
shading off, merges the two the way `Main` does, and compares that against the CPU-only frame.
That is the `merged` column, and it has to equal `worst`: a difference of two hundred and not of
two is what a mistake about which rows those are would look like.

### Where the time goes

`-Dgpu.stats=true` with `--bench` prints the card's share of a frame. Measured on an M3 at
1280x720, pitch 0, baked lighting:

| | school | Haven |
|---|---|---|
| pack the lists | 0.22 ms | 0.50 ms |
| upload | 0.26 ms | 0.87 ms |
| draw | 2.09 ms | 5.02 ms |
| read the picture back | 0.96 ms | 1.07 ms |
| everything else (rays, grid, visibility, warp, HUD) | ~1.0 ms | ~3.3 ms |

These four are measured with a `glFinish` between the draw and the read, which is what puts them
in separate columns; without `-Dgpu.stats` there is no `glFinish` at all, because `glReadPixels`
synchronises by itself. Taking that redundant sync out was worth a third of school's frame -
4.6 ms to 3.3 - and came out of a review of this branch rather than out of a profile.

The readback is a stall by construction: the CPU waits for a frame it cannot start the next one
without. Presenting the card's texture directly would remove it, and on this stack there is no
way to: Java2D cannot wrap a GL texture as an `Image`, and the escape hatch is JAWT with native
code of its own on a deprecated macOS view. A ring of pixel buffer objects could overlap the read
with the next frame's ray walk at the cost of a frame of latency, which is an optimisation rather
than a way out.

### The two things to know before trusting a number

**A horizon on a half-integer row makes the two pictures disagree more.** A pixel's height is
`eye - (row + 0.5 - hz) * pixelSize`. When `hz` is a whole number that offset is a half-integer
and the heights fall between things; when `hz` ends in .5 the offset is a whole number, and whole
numbers land exactly on the boundaries of the procedural materials - plank lines every sixth of a
metre, brick courses every quarter. Exactly on a boundary, float and double fall on opposite
sides and the two pictures pick different sides of a hard edge.

`GpuCheck` makes that happen by asking for an odd buffer height, because it puts the horizon at
`h / 2`: school's worst difference is 11 of 255 at 720 and 37 at 719 or 721, Haven's 3 and 74.
**The game does not reach it that way.** `Warp.place` sets `c.pitch = hz - srcH / 2.0` and the
renderer then computes `srcH / 2.0 + c.pitch`, so the buffer height cancels exactly and the
horizon is the warp's own `vHi + 2` whatever size the overscan grew to. Rounding the overscan to
an even number of rows would therefore change nothing, which is worth writing down because it is
the fix this section first recommended. What would actually help is making the material
boundaries themselves agree - snapping the scaled coordinate before `floor` and `frac` with the
same epsilon on both sides - and that is a change to `Materials`, not to the buffer.

**The frame-time tail on Haven is garbage collection, not the renderer.** The median frame is
8 to 12 ms and p99 is 32 to 37; `-Xlog:gc` shows G1 mixed pauses of 40 to 174 ms on a live heap
of 5 to 9 GB. That is the size of the map, not the cost of a frame.

### Platform

The GL entry points are the same C functions everywhere. Two things are not - which library holds
them, and how to get a context with no window behind it - and those are `GlPlatform`. macOS
(`GlCgl`, which is CGL) and Windows (`GlWgl`, which is WGL) are written; on anything else - Linux -
`--gpu` prints one sentence and the CPU renderer carries on, which is the whole engine.
`-Dgl.platform=none` forces that path so the sentence can be tested on a machine that does have a
backend.

The two backends are not the same shape under the interface. CGL makes a context out of nothing.
WGL cannot: the pixel format that decides what a context can do belongs to a device context, and a
device context comes from a window, so `GlWgl` registers a one-pixel window that is never shown and
never painted, purely to hang a format on. And `opengl32.dll` exports OpenGL 1.1 and stops - the
export table was frozen in 1996 - so every call younger than that, every framebuffer and shader and
vertex array, comes from `wglGetProcAddress`, which only answers a thread that already has a
context. `Gl` resolves its entry points while its class initialises, so on Windows the context has
to exist before the lookup does; `GlWgl.library()` makes it.

**Two cards is a trap.** On a machine with an integrated GPU and a discrete one, OpenGL takes
whichever card Windows prefers for `java.exe` - a per-application setting in Settings > System >
Display > Graphics, read once when the JVM starts. It is not the card the window is on: placing the
hidden window on the discrete card's monitor was written, run and measured making no difference at
all, which is why that code is not here and the measurement is in `GlWgl.note`. So `--gpu` prints
the renderer string and, when there is more than one card, the name of the one it did not use.
"Intel UHD Graphics 770" is an answer that looks exactly like success.

One GLSL note that only Windows found: `packed` is a reserved word in the language. Apple's
compiler accepts it as an identifier anyway and Intel's does not, so the wall shader's `unpack`
takes a `bits`.

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

## Texture filtering

A texture drawn by sampling it once per pixel falls apart at a distance. One pixel of a far wall
covers several centimetres of it, and asking the texture for the colour at one point inside that
patch picks one side of a mortar line at random - so the lines crawl and merge as you move, and a
tiled floor running to the horizon turns into moire. More rays do not fix it: they move the distance
at which it starts and nothing else. This is the one thing that makes a raycaster look dated, and
every 3D game since about 1997 solves it the same way: **do not draw detail smaller than a pixel,
draw its average**.

Normally that means mipmaps - the texture stored again at half size, quarter size and so on, and the
hardware picks the level where one texel is about one pixel. Here the textures are functions rather
than images, so there is no pyramid to pick from; instead each one is told how wide a pixel is on
the surface it is drawing (`w`, in metres) and fades out whatever is finer than that:

- `Materials.keep(w, feature)` - a detail needs about two pixels across it to show. Past that it
  gets faded into the mean it averages out to.
- Thin lines go first (mortar is 2.5 cm, floor grout 2.5 cm, panel joints 2.4 cm), the per-brick and
  per-plank shades next (25-50 cm), the coarse mottling last.

The renderer works out `w` from the projection: one pixel is `t / F` metres across on a surface
square to the eye. That shrinks when the ray count goes up, so `.` really does buy back distant
detail rather than only smoothing edges.

**Anisotropic filtering.** A surface seen edge-on - a corridor wall, a floor to the horizon - covers
a long thin strip per pixel, and its two sides can differ a hundredfold. Filtering both to the long
side is what smears a distant floor into paste, and it is exactly what the first attempt here did:
the ceiling grid vanished about four metres out. So sample *along* the strip instead, up to 8 times,
each sample filtered to the width of the narrow side - the same trick as the "16x AF" setting in a
graphics driver. For a floor the strip runs away from the eye and is `F / (row - horizon)` times its
own width; for a wall it runs along the surface and is as long as the wall is turned away.

It costs about 2x the shading time (640x360: 627 -> 333 fps) and it is the difference between a
corridor that crawls and one that does not.

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
| `src/engine/Materials.java` | procedural and image textures, mip levels, anisotropic filtering, masks |
| `src/engine/Lighting.java` | the baked lightmaps: sun, sky, lamps, shadows and bounced light |
| `src/engine/LightCache.java` | those lightmaps on disk, keyed by the map, the settings and the bake's own code |
| `src/engine/Occluder.java` | line of sight and nearest hit for a ray anywhere in the world |
| `src/engine/Main.java` | the window, the frame loop, and the buffers between the two |
| `src/engine/Options.java` | the command line, read once into one object |
| `src/engine/Player.java` | movement, gravity, steps and collision |
| `src/engine/Warp.java` | the pitch warp: the tilted view resampled from the upright one |
| `src/engine/Hud.java` | the overlay text and the minimap, drawn over the frame |
| `src/engine/Minimap.java` | the minimap itself, flooded from walkable space |
| `src/engine/Capture.java` | the headless modes: `--bench`, `--shot`, `--shots`, `--verify` |
| `src/engine/DynamicResolution.java` | the render scale, picked from measured frame time |
| `src/engine/RayView.java` | the top-down ray view window |
| `src/engine/Keys.java` | physical key state: the controls go by where a key sits, not by its letter |
| `src/engine/Hash.java` | digests of a frame and of a bake, for the golden test |
| `src/engine/Json.java` | minimal JSON parser (`//` comments allowed) |
| `src/engine/Gl.java` | the OpenGL entry points, one line each |
| `src/engine/GlPlatform.java` | which library holds them and how to get a context; the only per-OS part |
| `src/engine/GlCgl.java` | the macOS backend: OpenGL.framework and CGL |
| `src/engine/GlWgl.java` | the Windows backend: a hidden window, WGL, and `wglGetProcAddress` |
| `src/engine/GlMaterials.java` | `Materials`' procedural detail and masks, in GLSL |
| `src/engine/GpuWalls.java` | the card's pass: the shader, the uploads and the readback |
| `src/engine/GpuSpans.java` | the renderer's intervals, per column, as a card can read them |
| `src/engine/GpuMasks.java` | the masked surfaces blended over a finished column |
| `src/engine/GpuMaterials.java` | one record per surface, and a second table for blends and vertex colour |
| `src/engine/GpuTextures.java` | every image of a map, as array textures grouped by size |
| `src/engine/GpuLights.java` | the baked lightmaps packed into one float atlas |
| `src/engine/GpuTable.java` | how a table of records is laid out so a card will allocate it |
| `src/engine/GpuCheck.java` | how far the card's picture is from the CPU's, camera by camera |
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

A shape's top and bottom are drawn the same way, a stretch at a time along with the floors, and
not when the ray reaches the shape. A top spans the whole distance from where the ray enters the
shape to where it leaves, and anything standing on it enters somewhere in between: painted all at
once, the platform's far rows were already taken when the crate on it came along, and the crate
lost everything below the platform's far edge. When several of these surfaces want the same row
within one stretch - a platform and the top of the crate on it, or two storeys' floors - the row
goes to the one the ray through it meets first.

On a tie the shape wins, so a wall sitting exactly on a region boundary is drawn while the ray is
still in the near region and gets that region's light level and ceiling clip. For that tie-break to
be reachable the shape has to already be in the pending list, which is why shape bounds are grown by
a tiny epsilon before being registered into grid cells (`World.CELL_PAD`): a wall flush against a
cell line belongs to both cells. Without that padding the wall is only discovered one cell later
than the boundary event, and which of the two is handled first comes down to floating-point noise -
visible as vertical streaks of two different brightnesses along a wall seen at a grazing angle.

## Lighting

Lighting is baked once at load and read back per pixel, the way Valorant and most shipped games do
it. Nothing about it costs frame time: the frame rate above is with all of it on. `--flat` skips the
bake and falls back to the old flat shading, which is handy for seeing what the bake is worth.

**Lightmaps.** Every surface the renderer can draw gets a grid of RGB light levels over its own flat
coordinates: world `(x, y)` for floors, ceilings and the tops of shapes, `(distance along the face,
height)` for anything vertical. A world of vertical walls and horizontal slabs unfolds like that on
its own, so there is none of the UV unwrapping a general 3D engine needs. The demo map is 160k
texels at 20 cm across 842 surfaces. A pixel samples its surface's map bilinearly and multiplies:
`colour * texture * light`.

**Shadows are the renderer's own question, asked from somewhere else.** Can this texel see that
lamp? Draw the segment between them, project it onto the map, and walk it - region boundaries for
the storeys, grid cells for the furniture - checking the height the segment has where it crosses
each thing. That is `Occluder.clear`, and it is the same DDA walk `Renderer` uses to draw. So the
shadows are exact against the same geometry: a desk shadows the floor, a storey shadows the one
below, and a gap between two railings lets a line of light through.

**Bounced light** is where most of the "modern" look actually comes from. Each texel fires a small
hemisphere of rays (32 by default, cosine-weighted, turned by a per-texel angle so neighbours do not
sample the same directions). `Occluder.hit` is `clear`'s bigger sibling: it keeps the *nearest*
crossing instead of stopping at the first one, and reports which surface it was. A ray that gets
away sees sky; a ray that lands somewhere brings back that surface's own light times its colour.
Run the pass again and each surface now bounces what it gathered last time, so two passes give two
bounces. That single mechanism produces the corner darkening, the soft fill light in a room with no
lamp on, the warm bounce off the courtyard grass onto the corridor wall, and the light spilling
out of a doorway - none of which is special-cased anywhere.

**Soft edges.** A shadow ray answers yes or no, so one ray per texel puts every shadow edge on a
texel boundary and it comes out as a staircase of 20 cm steps. Each texel is therefore sampled at
eight spread-out spots inside itself, aiming at a different point on the sun (a disc two degrees
wide) and on each ceiling panel (a 60 cm square) each time. The average lands between lit and dark,
which is what the bilinear filter needs to draw a smooth edge - and it is also what really happens:
an edge is soft, and softer the further the shadow falls from whatever casts it. The gather rays are
fired from a different spot in each texel for the same reason.

**Tone mapping.** Bounced light pushes lit surfaces past full brightness, and clipping at 255 turns
a sunlit wall into a flat white shape. `Renderer.TONE` leaves everything below 200 exactly as it
was and rolls the rest off towards 255, as a 2048-entry table, so the extra light reads as detail.

The whole bake casts 19.3 million rays - 9.7M for the sun and the lamps, 9.6M for the sky and the
two bounces - one task per texel row spread over every core, and takes about two seconds for the
demo map at roughly 9 million rays a second. For scale, that whole budget is a handful of frames'
worth of what a real-time engine's ray-traced GI spends, except it is paid once instead of sixty
times a second.
It is controlled from the map's `lighting` block:

```json
"lighting": {
  "texel": 0.2,                     // lightmap resolution, metres
  "sun":  { "elevation": 40, "color": "#fff2de", "intensity": 1.0 },
  "sky":  { "color": "#a9c6ee", "intensity": 0.5 },
  "ambient": "#121418",             // the floor under everything, so nothing is pure black
  "panelLights": { "intensity": 0.7, "range": 7, "soft": 2, "glow": 0.35 },
  "shadowSamples": 8,               // spots sampled per texel: what makes shadow edges smooth
  "indirect": { "bounces": 2, "samples": 32, "reflectance": 0.6, "reach": 40, "smooth": 2 },
  "lights": [ { "pos": [26.5, 13.0], "z": 4.6, "intensity": 0.8, "range": 7 } ]
}
```

`panelLights` needs no list: the lit cells of a panel ceiling - the same cells `Materials` draws
bright - become the lights, so what glows is what lights the room. `reflectance` is how much of its
own colour a surface bounces back; `bounces` of 1 still gives sky and ambient occlusion, just no
bounce, and `reflectance: 0` gives direct light only.

## Minimap

`M` shows and hides it. It is not the shapes seen from above but where you can go, drawn the way
Valorant draws one: grey floor, green bomb sites, light boxes with a white edge for the crates and
pillars standing in the open, and nothing at all for walls, buildings you cannot get into and the
world outside the map.

`Minimap` works it out once, the first time it is shown, from the same shapes the player collides
with, on a 25 cm grid:

1. every square gets the surfaces a player could crouch on - a region's floor or a shape's top,
   with nothing solid in the 1.15 m above it;
2. a flood from where the player stands walks to each neighbouring square's highest surface that
   is at most 0.45 m up (a step, not a jump - so a crate stays a crate), or any distance down;
3. an island of unreachable squares under 25 m2, surrounded by floor, is an obstacle.

After that, drawing it is one image and the player marker. The old minimap filled every shape every
frame: for Haven's 38,000 shapes that was 30 ms a frame drawn into an image, against 0.6 ms now.
Working it out takes about 0.4 s for Haven's 137,400 shapes. The image is cropped to what is drawn,
and the map can turn it by quarter turns - Haven's is turned to match Riot's own minimap, with the
attackers' spawn on the right.

`-Dminimap.debug=true` also paints, in blue, ground a player could stand on that the flood never
reached - red where a jump would have got there, yellow where a ledge is in the way. That is how to
find a doorway the conversion closed.

## Map format

See the comments at the top of `maps/school.json`. In short:

- **Regions** are convex polygons with a `floor` and a `ceil`. Omitting `ceil` (or writing `null`)
  makes the region open to the sky. `top` is the top of the wall above the opening, as seen from an
  open-air region looking in.
- **Shapes** are `wall` (a segment), `circle`, `box`, `poly` or `ngon`, each with `z0` (bottom) and
  `h` (top). `maxDist` stops a shape being drawn beyond a given distance.
- **Tilted tops and bottoms**: `hx`, `hy` tilt the top and `z0x`, `z0y` the bottom, as metres per
  metre of x and y, with `h` and `z0` staying the heights at the middle of the shape's bounds. With
  both, a polygon is a slab at any slope - a diagonal brace, a plank, one face of a mesh - and a
  `wall` is a trapezoid standing up. A ray still meets a column of solid between two heights, so
  nothing about the renderer changes but where those heights are.
- **chunks**: a big map can keep its shapes in files beside it, `"chunks": ["haven/2_3.json", ...]`,
  each `{"shapes": [...]}`, paths relative to the map. A converted map of Haven is written that way, one
  file per 32 m square: the map itself is only the header - lighting, regions, textures, spawn.
- **array** repeats its `items` `count` times, translated by `step` (desks and chairs, colonnades).
- Polygons must be convex; this is checked on load. An L shape has to be split into several pieces.
- `site` on a shape (`"A"`, `"B"`, ...) marks a bomb site's floor; the minimap paints it green.
- `"walkable": false` on a region says its floor is only the bottom of the world and the real ground
  is made of shapes, as in an imported map; the minimap does not count it as somewhere to stand.
- `"minimap": {"rotate": -90}` at the top level turns the minimap by quarter turns (degrees,
  clockwise), so a map can be shown the way round its players know it.

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

### A tree in every cell

One union per group rejects a storey at once, but it cannot reject part of one. Once a 32 m square
of Haven was converted triangle for triangle (218,000 shapes in one square), a 2 m cell held
hundreds of them, a ray through the cell passed a few, and every other one still got its own
`hidden()` test: `hidden()` and `skip()` were 41% of the frame, measured with Java Flight Recorder.
The bake had the same problem three times over - `Occluder.shapesClear` walked every member of
every cell a shadow ray crossed, `Occluder.open` did the same for every lightmap sample, and
`Lighting.direct` went through all 189 lamps for every sample before finding most of them too far.

- **Each group is a bounding volume hierarchy** (`World.Group`): members split in half along the
  widest spread of their middles, down to four per leaf. The renderer walks it with the same
  `hidden()` test on each node, so a node's rows being painted rejects everything under it; the
  `Occluder` rejects a node whose height range or plan the segment misses, and, looking for the
  nearest hit, one it enters beyond the nearest hit so far.
- **Lamps are indexed on a 4 m grid** (`Lighting.indexLights`): a sample only visits the lamps whose
  range reaches its square, and each keeps its own place in the list for its random spot.

None of it changes a pixel or a texel, and that was checked rather than assumed. Visiting shapes in
a different order could change which of two surfaces at exactly the same distance is drawn, so
first those ties were made to go to the lower shape id (renderer and `Occluder` alike); that build
is the baseline. Against it: every `--shot` image, depth and albedo file from school (flat and
baked) and from ten Haven cameras on two maps is byte-identical, and a hash over every channel of
every lightmap is identical, with the same ray count. The first version was not: its point-in-node
test for `open()` was not padded, a tilted shape's `bottomAt` rounded a hair below its lowest
corner, and 480 rays went differently - invisible in any picture, found by the hash.

Measured on an 8-core Apple M3, 1280x720, the camera at spot8 (two `--bench` runs each; single runs
on this machine differ by up to 40%, so two are shown):

| | before | after |
|---|---|---|
| base conversion (220,287 shapes): bake | 132.8 s | 48.4 s |
| base conversion: frame, level / tilted 30 degrees | 20.6-26.4 / 43-48 ms | 22.2-22.5 / 41-43 ms |
| one square as triangles (426,207 shapes): bake | 760 s | 248 s |
| one square as triangles: frame, level / tilted 30 degrees | 50-51 / 116-150 ms | 33-38 / 100-103 ms |

The base conversion's frames did not need it and did not change. On the triangles, the tests that
were 41% of the frame are now 8%, and what is left is shading: filtering the textures is 61%, and
choosing between overlapping tops and bottoms (`nearest`) most of the rest.
