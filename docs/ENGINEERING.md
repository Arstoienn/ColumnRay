# Engineering

How the engine is built and why. It is for anybody writing a game on it or changing it: the API a
game sees, the tests every change has to pass, where the code is, and then each part of the engine in
turn, with the measurements that decided it. Running it, its options and its controls are in
[Using ColumnRay](USAGE.md). Back to the [README](../README.md).

ColumnRay is a 2.5D raycasting engine in plain Java with one dependency, LWJGL, for the window and
OpenGL. The map is a 2D plan seen from above, one ray is cast per screen column, and every height on
screen comes out of the same projection formula. The demo map is two storeys of a school: a
classroom, corridors, a courtyard with a pool, and a staircase up to a walkway you can look back
down from.

## Writing a game on the engine

The engine and the game are two packages, and the line between them is meant to be used. `engine`
owns the window, the frame loop, the buffers, the renderer, the map and the bake. It has no `main`,
no idea which key walks forward, and no player. `game` owns all of that, and reaches the engine
through `Game`, which has four methods of which two matter, and through the public methods on
`Host`.

```java
public final class MyGame implements Game {
    private final View view = new View();

    @Override public void update(double dt, Input in) { /* move whatever moves */ }
    @Override public View view() { return view; }          // x, y, eye, heading, pitch
}
```

```java
Host host = new Host(World.load(Path.of("maps/school.json")), Options.parse(args));
host.run(new MyGame(host));
```

`Sandbox` is the game this repository ships: walk about a map and look at it. Read it as the worked
example. It is about two hundred lines, and the engine knows nothing about any of them. Collision
against the map is `Body` and line of sight is `Occluder`, and both take the size of the body from
whoever asks, because the engine doesn't know how tall anybody is.

### Where the line falls

The engine is a library and `game` is the program that uses it, which is why `engine` has no `main`.
Start-up has an order (ask for a graphics context before AWT does anything, load the map, open a
window), and getting it right is the game's job, because only the game knows whether a window is
going to open at all. `game.Main` is those ten lines.

`Host` owns the frame and `Game` owns what is in it. The buffers, the overscan, the pitch warp, the
render scale and the window are one tangle that has to change together, and none of it is a
decision a game should be making. What the game hands back is a `View`: a position, an eye height, a
heading and a pitch, in metres and radians. The horizon in pixels that the renderer actually wants
is the warp's arithmetic, and a game that had to fill it in would be doing the engine's work.

The pitch limit is pushed to the game, not pulled by it. How far the camera may tilt depends on the
map and on how much overscan the render buffer can hold at its current size, which dynamic
resolution changes every few frames. So `Host` works it out and calls `Game.pitchLimit` before it
takes the view, instead of clamping the view behind the game's back. A game whose idea of where it
is looking had quietly drifted from the picture would be miserable to debug.

`Body` is the engine's and `Player` is the game's. What can be stood on at a point, what is over your
head and what is in the way are questions about the map, and the map is the engine's. How fast a
walk is, how hard a jump is, how low a crouch goes and how quickly the camera eases over a step are
not. So `Body` takes the size of the body from whoever asks (a player, a crate, a guard) and knows
nothing else about them.

`Input` reads the keyboard, and nothing in the engine knows what a key means. `Keys` reports
positions instead of letters, and `Input` adds the two things every game needs and nobody wants to
write twice: ignore the keyboard while another application is in front, and fire a tap once
instead of on every frame the key is held.

There are two games on one engine, and the walking belongs to neither. `Sandbox` is the instrument:
every switch the renderer has on a key, four lines of text over the picture, a minimap, and a fly
mode for looking at a map from outside it. `Play` (`--play`) is the same world with none of that. No
overlay, no toggles, no flying, the pointer held and hidden. What they share is a body and a set of
bindings, which is `Walker`. Two of them can exist at all because neither is the engine: the loop
calls `update`, `view` and `overlay` and has no idea which game it is talking to.

A headless capture asks the game where to stand. `--shot`, `--shots` and `--verify` read a views file
whose `feet` column may say "stand on whatever ground is here". That needs a collision query and a
body height, and `Capture` has no business knowing either. So it calls `Game.place` and renders
whatever `Game.view` says, which also keeps a golden frame a picture of the game and not of some
second, simpler path.

The test of the split was that it changed no pixel. The golden digests, the Haven digests and the
`--shot` images, the HUD and minimap drawn over them included, are byte for byte what they were
before.

## Tests

```bash
./test.sh
```

First, unit tests for the parts that can be checked on their own: the JSON parser, the ray/segment
and ray/polygon intersections, the frame-time controller, map loading, and the lightmap cache's key.
Then two checks on whole frames.

Determinism. The same build renders the seven cameras in `tests/views/school.txt` on one thread and
on every core, and the two have to agree exactly. The renderer's distance tie-break and the grid's
cell padding exist to give this property, and it holds on any machine.

Golden frames. The same cameras, baked and `--flat`, against digests in `tests/golden/`. What gets
hashed is the engine's own pixel, depth, albedo and lightmap arrays, not the PNGs written from them,
so a change to an image encoder can't turn the test red by itself. `./test.sh --haven` does the same
against the `maps/haven` submodule, where the map is big enough to work the acceleration structures
properly. That map is private (see [Haven](MAPS.md#haven)).

The engine is built on one rule: an optimisation is proved not to change the output, never assumed
not to. The golden files are where that proof lives. A deliberate change to the picture is blessed
with `./test.sh --bless`, in the same commit that causes it. Beyond the goldens, a change to the
renderer or the bake should leave `--shot` images, albedo and depth files, and lightmap hashes
byte-identical unless the change was meant to move them. `tests/golden/README.md` explains the one
thing the digests can't promise, the same values on a different CPU, and why CI checks them on
macOS only.

## Benchmarking

`./bench.sh [map] [size] [runs]` runs `--bench` in several fresh JVMs and reports the median. Set
`CP_B` or `JAVA_OPTS_B` and it runs two builds or configurations alternately and reports their ratio.

### Why `bench.sh` and not one run

`./run.sh` used to delete the classes and recompile every time, which put the compiler in every
timing. `build.sh` now compiles only when a source is newer than the last build. `--bench` runs 400
untimed frames first for the JIT, then times every frame of a full turn, level and tilted, and
prints the median and the 99th percentile instead of a mean.

That still leaves the machine. On a fanless MacBook Air six identical runs spread by 39-55%, and
they got slower as they went: the first three at 10 ms, the last three at 12-16 ms. That is the chip
throttling as it heats, and nothing in the JVM takes it out. `bench.sh` runs a fresh JVM several
times with a rest in between and reports the median of the run medians, with the spread. Given two
builds (`CP=old CP_B=new ./bench.sh`) it runs them in turn, A B A B, and reports the median of the
B/A ratios of neighbouring runs, so the heat lands on both sides of the comparison.

## Project layout

| File | Contents |
|---|---|
| `src/engine/Body.java` | what the map lets a body of a given size stand on, and what blocks it |
| `src/engine/Capture.java` | the headless modes: `--bench`, `--shot`, `--shots`, `--verify` |
| `src/engine/DynamicResolution.java` | the render scale, picked from measured frame time |
| `src/engine/Game.java` | what a game implements: update, view, overlay, place |
| `src/engine/Geometry.java` | ray vs segment / circle / convex polygon; distance helpers for collision |
| `src/engine/Gl.java` | OpenGL through LWJGL, and the context the card path draws in |
| `src/engine/GlMaterials.java` | `Materials`' procedural detail and masks, in GLSL |
| `src/engine/Glfw.java` | the library's lifetime, and the hidden window that owns the engine's context |
| `src/engine/GpuCheck.java` | how far the card's picture is from the CPU's, camera by camera |
| `src/engine/GpuLights.java` | the baked lightmaps packed into one float atlas |
| `src/engine/GpuMasks.java` | the masked surfaces blended over a finished column |
| `src/engine/GpuMatCheck.java` | how far `GlMaterials` is from `Materials`, material by material |
| `src/engine/GpuMaterials.java` | one record per surface, and a second table for blends and vertex colour |
| `src/engine/GpuNote.java` | says when a machine has a second card the frame did not go to |
| `src/engine/GpuSpans.java` | the renderer's intervals, per column, as a card can read them |
| `src/engine/GpuTable.java` | how a table of records is laid out so a card will allocate it |
| `src/engine/GpuTextures.java` | every image of a map, as array textures grouped by size |
| `src/engine/GpuWalls.java` | the card's pass: the shader, the uploads and the readback |
| `src/engine/Hash.java` | digests of a frame and of a bake, for the golden test |
| `src/engine/Host.java` | the window, the frame loop, and the buffers between the two |
| `src/engine/Input.java` | the keys and the mouse, with no bindings in them |
| `src/engine/Json.java` | minimal JSON parser (`//` comments allowed) |
| `src/engine/Keys.java` | physical key state: the controls go by where a key sits, not by its letter |
| `src/engine/LightCache.java` | those lightmaps on disk, keyed by the map, the settings and the bake's own code |
| `src/engine/Lighting.java` | the baked lightmaps: sun, sky, lamps, shadows and bounced light |
| `src/engine/Materials.java` | procedural and image textures, mip levels, anisotropic filtering, masks |
| `src/engine/Minimap.java` | the minimap itself, flooded from walkable space |
| `src/engine/Occluder.java` | line of sight and nearest hit for a ray anywhere in the world |
| `src/engine/Options.java` | the command line, read once into one object |
| `src/engine/RayView.java` | the top-down ray view window |
| `src/engine/Renderer.java` | camera, projection, DDA grid walk, interval filling, region boundaries, shading |
| `src/engine/Srgb.java` | an sRGB number as light; its own class because `LightCache` has to key a bake on it |
| `src/engine/Surface.java` | the window seam: where the picture goes and where the controls come from |
| `src/engine/SurfaceGlfw.java` | the GLFW window: the picture, the pointer, the keys, the ray view panel |
| `src/engine/View.java` | where to look from, in metres and radians |
| `src/engine/Warp.java` | the pitch warp: the tilted view resampled from the upright one |
| `src/engine/World.java` | region and shape data, JSON loading, acceleration grid, point queries |
| `src/game/Hud.java` | the overlay text and the minimap, drawn over the frame |
| `src/game/Main.java` | where a run starts: the command line, the map, the engine, then play or capture |
| `src/game/Play.java` | `--play`: the same walking with nothing drawn over it |
| `src/game/Player.java` | movement, gravity, steps, crouch, jump, and what the camera does about them |
| `src/game/Sandbox.java` | the sandbox: the bindings, the toggles, and the camera it hands the engine |
| `src/game/Walker.java` | the walking both games share: the body, the bindings, the view it hands over |
| `maps/school.json` | Demo map |
| `maps/backrooms/` | Submodule: the Backrooms map, in [ColumnRay-Backrooms](https://github.com/Arstoienn/ColumnRay-Backrooms) (CC BY 4.0) |
| `maps/haven/` | Submodule: the Haven map, in a private repository (see [Haven](MAPS.md#haven)) |
| `docs/images/` | Pictures for the README and the maps page |
| `docs/ENGINEERING.md` | This document |

## How it works

1. The camera. `r = dir + plane * camX`, with `PL = 1.0` at the default 90-degree field of view
   (`PL = tan(FOV/2)`; it was 0.66, which is 67 degrees and what every raycasting tutorial uses),
   and `F = (W/2)/PL`. `r` is deliberately left unnormalised, so the intersection parameter t *is*
   the perpendicular distance and there is no fisheye. See `Renderer.Column.render`.
2. Projection. `rowZ(z, t) = hz - (z - eye) * F / t`, with `hz = H/2 + pitch`, which is y-shearing.
   The renderer only ever y-shears, and `Warp` turns that into a true tilt (see "Looking up and
   down"). See `rowZ`.
3. Intersection. Segments, circles (enter at t1, exit at t2), and convex polygons, where every edge
   is tested and the smallest and largest t are kept. A negative t1 means the eye is inside the
   shape's footprint. See `Geometry`.
4. Interval filling. Each column tracks which rows are still empty. Things are drawn near to far and
   only paint empty rows, and the column stops as soon as it is full. See `paint`.
5. A shape is three parts: its side, its top (when the eye is above it) and its bottom (when the eye
   is below it). See `drawHit`.
6. Floors and ceilings invert the projection to get a distance from a row,
   `t = (eye - z) * F / (y - hz)`, and then `pos + r * t` is the world position to texture with.
   A ceiling clips anything taller than it (`min(h, ceil)`). See `surfaces` and `flat`.
7. Region boundaries work like portals. When the ray crosses into the next region it compares the
   heights on both sides: a lower ceiling on the far side becomes a lintel, a higher floor a step
   riser. Open-air regions like the courtyard have no ceiling, so you see sky. See `cross`.
8. Shading. A segment's normal is its edge vector turned 90 degrees, a cylinder's is
   (hit point - centre) / radius, and both are multiplied by distance fog. See `lambert` and `fog`.

### How shapes and regions get ordered

The DDA steps one cell at a time and adds that cell's shapes to a pending list. By the time the ray
reaches the cell's exit distance `tout`, the order of everything with `t1 <= tout` is final, because
a shape found only in a later cell has to enter beyond that point. Those shapes and the "cross into
the next region" events are then interleaved by distance, and the current region's floor and
ceiling are drawn for the stretch between each pair of events. That is why the floor in front of an
object is drawn first and the floor behind it is hidden by the object.

A shape's top and bottom are drawn the same way, a stretch at a time along with the floors, and not
when the ray reaches the shape. A top spans the whole distance from where the ray enters the shape
to where it leaves, and anything standing on it enters somewhere in between. Painted all at once,
the platform's far rows were already taken when the crate on it came along, and the crate lost
everything below the platform's far edge. When several of these surfaces want the same row within
one stretch (a platform and the top of the crate on it, say, or two storeys' floors), the row goes
to the one the ray through it meets first.

On a tie the shape wins, so a wall sitting exactly on a region boundary is drawn while the ray is
still in the near region, and gets that region's light level and ceiling clip. For that tie-break to
be reachable the shape has to be in the pending list already. That is why shape bounds are grown by
a tiny epsilon before they are registered into grid cells (`World.CELL_PAD`): a wall flush against a
cell line belongs to both cells. Without the padding the wall is found one cell after the boundary
event, and which of the two is handled first comes down to floating-point noise. It shows as
vertical streaks of two different brightnesses along a wall seen at a grazing angle.

## Input

The mouse looks without a button held. A window is told where the pointer is, never how far the hand
moved. GLFW answers that directly: `GLFW_CURSOR_DISABLED` hides the pointer and stops clamping it,
and the difference between two reports is the movement (`Host.looked`). It is one call and it works
on every platform, so `Host.canMouseLook()` is simply true.

It used to be a warp, and the warp is worth remembering because the same trap is waiting in any
toolkit that doesn't offer this. The pointer was put back at the middle of the window after every
event, so the next event's distance from the middle was the movement. That was
`CGWarpMouseCursorPosition`, through the FFM API, in an `engine.Pointer` that has been gone since
2026-09-28. Two things kept it from drifting. An event exactly at the middle was the warp's own
arrival coming back round, and counted for nothing. And the warp went to the same *integer* middle
the deltas were measured against, because half a pixel out made every event read as a flick of one.
The warp was a macOS call, so everywhere else the mouse only looked while a button was held.

### The walk has a velocity in it

Moving by `speed * dt` in whatever direction the keys point makes the top speed instant both ways,
and makes the air as steerable as the floor. `Player` instead accelerates a velocity towards what the
keys ask for. `GROUND_ACCEL` reaches walking pace in about 70 ms and `STOP_ACCEL` loses it about as
fast. `AIR_ACCEL` is a fifth of that, so a jump keeps its momentum, and with nothing asked for in the
air nothing happens at all. `COYOTE` and `JUMP_BUFFER` are the two allowances that still make a jump
out of one asked for slightly too late or too early. Both are under a sixth of a second, which is
less than a person can time.

The camera bobs with the stride and dips on a landing, because in a first-person view speed is read
off the picture and nowhere else. Neither moves the body. Both are an offset on `Player.eye()`, and
both are zero the moment a camera is placed, so no screenshot, digest or golden frame can see them.

### Keys are places on the keyboard

WASD is a shape on the keyboard, not four letters. A toolkit that names a key by the character it
produces moves that shape on every layout that isn't US QWERTY. On Colemak the key in the S position
produces R and the one in the D position produces S, so a game listening for letters would go the
wrong way, and on Bopomofo it would hear nothing it knew.

So a key in `Keys` is a position. The numbers are macOS virtual key codes, because that is what the
engine read when it asked macOS directly, and GLFW's key tokens turn out to be the same positions
said differently. `glfwGetKeyScancode(GLFW_KEY_W)` is 13, which is what `Keys.W` has always been, and
all thirty-three the game uses agree. `SurfaceGlfw.check()` says so out loud if they ever stop. The
window answers for held keys through `Keys.Source`, so `Keys` never learns which toolkit it is.

Naming a key is the one place the layout still matters, and there it goes the other way.
`glfwGetKeyName` says what this keyboard prints on a position, read from the layout in force when it
is asked, so on Colemak the HUD hint reads `WARS move`. AWT's `getKeyText` names the keys no layout
prints anything on.

This used to be three hundred lines of FFM calls, because AWT could answer neither question:
`CGEventSourceKeyState` for whether a position was down, and `UCKeyTranslate` behind Text Services
for its cap. The cap had to be asked once before any window existed, because Text Services belongs
to the process's first thread, and only after `TransformProcessType`, because that first call
checked the process in as background-only. The game once came up taking no keys at all, and
`lsappinfo` said `type="BackgroundOnly"`. All of it went on 2026-09-28, with the AWT window.

## Resolution

One ray is cast per rendered column, so the rendered width is literally the ray count. `--size` sets
the output resolution, and `--ss N` renders at N times that and averages back down, which means
`rays per frame = width x N`. Both work with `--bench` and `--shot`.

The render resolution and the window are two different sizes. The window is the output, 1920x1080 by
default or whatever `--window` says, and the picture is rendered at `--size` (1280x720 by default)
and scaled into it. A bigger window adds not a single ray.

There is no hard limit on the ray count beyond memory (the pixel buffer is `W * H * 4` bytes) and the
16384x16384 sanity clamp. The useful limit for raw detail is one ray per horizontal pixel of the
window. Past that, extra rays stop being new detail and become anti-aliasing, which is what `--ss`
is for.

### Dynamic resolution

What a frame costs is the pixels it shades, not the rays it casts. On Haven at 1920x1080, tilting the
view 30 degrees takes it from 1924 rays to 2826 (+47%), but the frame only from 75.5 to 86.9 ms
(+15%), and 61% of the time is texture filtering. So the thing to turn is the render resolution, and
`DynamicResolution` turns it from the measured frame time. It moves along eight steps from half the
window's width to all of it (1280x720 in a 1920x1080 window is the fourth), aiming at `--fps`, which
is 60 by default:

- over budget on average over the last 10 frames: one step down, straight away;
- under 70% of the budget over the last 30 frames: one step up;
- after a step, 20 frames go unjudged while the buffers are rebuilt;
- one frame counts for at most 1.5 budgets, so a single hitch (a GC, the OS) can't cost a step by
  itself, but a stall that lasts does.

Down is quick and up is slow on purpose. A stutter is worse than a picture that is a little soft for
a second. The 70% margin stops it climbing a step and falling straight back, since no two
neighbouring steps differ by more than 27% in pixels. `,` and `.` take over by hand and turn it off,
and `V` hands it back. The HUD and the minimap are drawn on the window, not into the picture, so
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

Tables further down that give 640x360 as 0.65 ms are from before anisotropic filtering. They are
kept for the record. Don't measure a change against them.

### How large a screenshot can be

Nobody is waiting on a screenshot, so the only real limit is what fits in the heap. `--size` caps at
16384 a side, and `--size` times `--ss` has to stay inside that too. On an M3 with 16 GB, school,
baked, one camera, the whole run timed including JVM start, the cached bake and PNG encoding:

| | level | tilted 45 degrees |
|---|---|---|
| 3840x2160 | 2.1 s | - |
| 7680x4320 | 4.0 s | 4.6 s |
| 7680x4320 `--ss 2` | 6.3 s | 11.3 s, and needs `JAVA_OPTS=-Xmx12g` |

Only the last one needs explaining. Tilting costs memory more than time. At 45 degrees the upright
image the warp resamples is about 2.5 times the frame's width and 2.6 times its height, so 8K at
`--ss 2` is an overscan buffer of 38240 x 22290. Three arrays that size (pixels, depth and albedo)
don't fit in the 4 GB the JVM takes by default. Level, the same shot needs nothing special.

## Where the frame time actually goes

Every timing in this document before 2026-09-26 was taken at a 67-degree field of view, the default
until then. It is 90 now, and the change is not even. On school a level frame is within a tenth of
what it was (1280x720, card: 3.30 ms against 3.56, and 3.42 measured again a day later). A frame
tilted 45 degrees nearly doubles: 8.60 against 16.65 ms, 116 fps against 60. A wider view adds
overscan for the pitch warp, 2,884 rays against 4,142, and adds nothing at all when level.

On Haven the same change costs four times as much. That is the number to know before widening
anything again. Re-measured cold on 2026-09-27, with the shipped map and its bake, `--hdr`, the
card, 1920x1080, rested runs, medians of run medians:

| | level | pitch 45 |
|---|---|---|
| 67 degrees | 37.1 ms (27 fps) | 59.6 ms (17 fps) |
| 90 degrees | 46.4 ms (22 fps), runs 42.5-52.4 | 237 ms (4 fps), runs 197-333 |

The 67-degree row reproduces the numbers from 2026-09-21 (36.9 and 57.0). That is what says the
90-degree row is the renderer and not the afternoon. `WORK` says where it goes. Tilted, 67 degrees
walks 4,350 rays over 6.5 cells, each testing 55.8 shapes; 90 degrees walks 6,212 over 14.2, testing
128.3. That is 243,000 shape tests a frame against 797,000. School's frame is mostly shading, which
a wider view barely touches. Haven's is mostly the rays, and overscan lands squarely on them. These
are costs at a fixed render size. With the frame-time controller on, a tilted view on Haven comes
back as a coarser picture, not as 4 fps.

Measuring that taught two things worth more than the numbers.

Give Haven `-Xmx8g`, not `-Xmx12g`. Its live heap after a collection is 5.3 GB, and with 12 on a
16 GB machine the JVM sits at 6-10 GB resident while the rest of the system swaps. The first attempt
at this table had 2 GB of swap in use, one frame of 55 seconds, and a pair of runs that took 47
minutes.

And the CPU rows further down have still not been re-measured. A sustained CPU benchmark throttles
this machine so far that a 67-degree control taken after an hour of runs read 29.2 ms where the
table says 20.8, and one pair came out *faster* at 90 than at 67. Ratios measured back to back in
one sitting hold up. Absolute numbers are a record of a particular day.

`--bench` times the raycaster and nothing else. With both windows open the loop also has to blit the
main view and redraw the ray view, and those used to dominate. In the real windowed loop, 640x360,
both windows open:

| | before | after |
|---|---|---|
| raycaster | 1.0 ms | 0.9 ms |
| main window blit + HUD + minimap | 6.6 ms | 2.3 ms |
| ray view redraw | 19.4 ms | 5.3 ms |
| **whole frame** | **27.6 ms (36 fps)** | **8.5 ms (117 fps)** |

The ray view cost was redrawing several hundred translucent antialiased shapes every frame. The
grid, the regions and the shape outlines never move, so they are now drawn once into an image,
rebuilt only on zoom, and blitted with the view transform. It is a debugging window anyway, so it
starts closed and `R` opens it; closing it roughly triples the frame rate again.

## Shading on the card

The card shades the frame by default, and `--shade cpu` asks for the other path (`--cpu` and `--gpu`
are the old spellings). It used to be opt-in, for as long as there were surfaces the card had not
been given. Once `--gpu-verify` reported zero such pixels on both school and Haven, leaving it off
meant leaving three quarters of the machine's speed on the floor.

Two modes still take the CPU path. `--verify` does, because its whole output is a digest, and a
digest of the card's float arithmetic is not one the double-precision renderer could ever match.
`--shot` and `--shots` do for a duller reason. They write depth and albedo beside the picture, and a
frame with a depth buffer shades every row on the CPU whether or not the card was given the surface.
Handing the colours to the card afterwards would buy a different rounding and nothing else, and it
would cost the byte-for-byte comparison every renderer change is checked with. A screenshot that
wanted only the picture could have the card. Nothing asks for that yet.

The card takes the shading and nothing else. The CPU still casts one ray per screen column, walks
the acceleration grid, tests the shapes and decides what is visible. What it hands over is the
answer, a handful of row intervals per column, and the card colours them in. That doesn't weaken the
column constraint. It is why the whole thing works: a column renderer's output is a few hundred
kilobytes of intervals, which is very little to give a card. A triangle renderer would have to give
it the world.

### What crosses the bus

Two per-column lists, and four tables that never change:

| | what it is |
|---|---|
| spans | the wall and plane intervals a column painted, with the few numbers a strip needs |
| masks | the masked surfaces blended over the finished column: trees, and cut-outs sawn from a mesh |
| materials | one record per surface: which image, its mean, and the map from the world to it |
| blends | a second layer, its height map and the mesh's vertex colour, for the few surfaces with them |
| lightmaps | every baked map shelf-packed into one float atlas, with a record saying where each landed |
| images | every image the map uses, as array textures grouped by size |

The lists are allocated at their worst case, a few hundred entries a column, and a frame uses a
handful of that. So only the part a frame really fills is uploaded (`GpuWalls.pack`). Sending the
whole array was forty megabytes a frame of mostly nothing, and it measured as most of the pass.

### Sixteen samplers, and what that forced

A fragment shader is promised sixteen texture units and no more. Six are taken by the spans, the
blends, the light atlas, its records, the material table and the masks, which leaves ten for
images. Haven's 452 images come in fifteen sizes, so one array per size does not fit.

An array layer's levels are a mip chain, and a smaller image of the same shape is exactly what some
level of a bigger one looks like. So a 512 square image can live in levels 1 and down of a 1024
square layer and be sampled by asking for a level one deeper. Wrapping still works, because it
happens in normalised coordinates, which don't know which level answered. The cost is the levels
above it, allocated and never read. A size is folded into a bigger bank only while that waste stays
under a budget: the 222 images at 512 square would waste 700 MB in a 1024 bank and keep their own,
while the 51 at 128 square waste ten megabytes and get folded. Fifteen sizes become seven banks for
about thirty megabytes, and nothing is left out.

### Proving it draws the same picture

The golden frames can't answer this. They hash the renderer's own arrays, and a card works in float
where the renderer works in double, so the two never hash the same. What can be asked is how far
apart the pictures are, and `engine.GpuCheck` asks it. For each camera it renders the frame on the
CPU with the spans recorded, draws those same spans on the card, and compares every pixel.

```
./build.sh && java --enable-native-access=ALL-UNNAMED -cp out engine.GpuCheck [map.json] [WxH]
```

| column | what it means |
|---|---|
| `masked` | pixels the card was not given, because something on them is not ported |
| `worst` | the largest single-channel difference anywhere in the frame |
| `over 2` | how much of the frame differs by more than a byte can hide |
| `merged` | the same, for the frame the game actually shows (see below) |

`masked` reaching zero is what finished looks like. It is zero on school and on all six Haven
cameras, and the worst difference is 2 of 255, with no pixel anywhere over 2.

It used to be 3, and the 3 was the mip chain, not the shading. `Materials.Level.half` keeps its area
averages as floats on purpose, and `GpuTextures` was rounding them to a byte on the way up. Uploading
the same levels unrounded (`-Dgpu.texels=half` or `=float`) settles it. On Haven's spot7 the worst
goes 3, 2, 2 and the mean 0.055, 0.029, 0.006 for RGB8, RGB16F and RGB32F, and the pixels over 2 go
to none. A worst of 2 is the floor: float against double in the shader's own arithmetic, which no
texture format reaches. With RGB16F all six Haven cameras read 2, so RGB16F is the default.
`-Dgpu.texels=byte` goes back, and `=float` asks the question again.

### The whole loop, not just the renderer

`GpuCheck` compares the renderer's buffer at a fixed size, with the camera level. Between that
buffer and the window sit the pitch warp, the overscan growing under it, and the card's buffers
being rebuilt around both, and none of that was covered. That is where the worst bug of this work
lived: a resize that watched the width and not the height.

`--gpu-verify views.txt` draws each view through `Host.frame` twice, once on each path, at five
tilts, and compares the finished output:

```bash
./test.sh --gpu                                    # school, at the golden size, in seconds
./run.sh maps/haven/haven.json --gpu-verify tests/views/haven.txt --flat
```

The tilts climb, because the overscan only ever grows. Each one asks for a taller buffer than the
last, landing on whatever height the warp asks for and not on a round number. At each tilt frames
are thrown away until the per-column lists stop growing: the lists start small and double when a
column runs out, so the first frames at a new tilt hand rows back to the CPU, as designed.

The render size moves too, one rung of `DynamicResolution.LADDER` per comparison (2026-09-25). That
is the other half of the same rebuild. A tilt grows the overscan under a fixed render size; a rung
change throws the render size away and rebuilds every buffer from the renderer's pixels outward. It
costs no extra frames, since they are being rendered anyway. The rungs wrap instead of climbing, so
the card's resources get rebuilt smaller as well as larger, which a ladder that only went up would
never ask for. `./test.sh --gpu` passes `--window` at the render size so the ladder lands on 160 to
320 columns instead of 960 to 1920: eight sizes, three of them an odd number of columns wide, and
180x101 odd both ways. That last one is the case nothing else reaches, and the one that rounding the
render buffer's width up to even exists to survive. The trade is fewer pixels compared at each size
for many more configurations, which is the right way round for a check whose job is structural.

The verdict is about the picture, not the rounding. The two are never identical. A few pixels a frame
land exactly on the hard edge of a procedural material, a plank line or a brick course, where float
and double fall on opposite sides and disagree by tens of levels. So the threshold is on how many
pixels disagree, not by how much. School runs at 0.0001% of the frame over 8 levels and a
deliberately broken merge at 4.8%, and the rule sits in that margin. Entries handed back to the CPU
are reported and don't fail it. A drop is the fallback working, and the run that dropped sixty
thousand of them still agreed to within four levels.

### Why the CPU stops shading, and how that is checked

Moving a surface to the card saves nothing on its own. The CPU was still colouring every pixel, and
the card's work was added to it, not substituted. The saving comes when the CPU stops. It can't just
stop, because a masked surface the card was not given is blended over whatever the CPU painted
under it and needs something to blend with. So rows the card has are skipped, rows anything unported
will be blended over are not, and a column works out which is which as it goes
(`Renderer.cpuUnder`). A screenshot still shades everything, because its albedo and depth come from
the CPU.

A saving like that could hide a mistake. A row the CPU skipped that the card turns out not to have
drawn would come back black. So `GpuCheck` renders every camera a second time with the shading off,
merges the two the way `Host` does, and compares the result with the CPU-only frame. That is the
`merged` column, and it has to equal `worst`. A difference of two hundred instead of two is what a
mistake about those rows would look like.

### What a ray actually does (`WORK`)

`--bench` prints a `WORK` line beside each `BENCH` one: how many grid cells a ray walks, how many
shapes it tests, and how many of those tests hit. They are gathered over the warmup frames, which are
the same turn and which nobody is timing, and they don't move with the weather the way milliseconds
do on a fanless machine. When the question is whether a change made the rays do less, these are the
figures to compare.

What they say at 1920x1080, level:

| | cells / ray | tested / ray | hit |
|---|---|---|---|
| school | 9.0 | 2.1 | 95% |
| Haven | 25.6 | 138.9 | 86% |

Haven walks under three times school's cells and tests sixty-six times its shapes, and six tests in
seven find something. So the CPU half of a Haven frame isn't wasted on bounding boxes the ray misses,
or on walking the grid. The ray really does cross 119 surfaces. That is what the map is made of: the
conversion turns each mesh triangle into its own slab, the median one is 13 cm across and 8 cm tall,
and 87 per cent of them are under half a metre.

Three things were tried against that, and none worked. They are written down so nobody tries them
twice.

A `maxDist` on every shape (`tools/add_maxdist.py`, which groups coplanar fragments so that a floor
is judged by the floor's size and not a sliver's). At one pixel it gave 17 per cent of shapes a
cut-off and moved the tests from 138.9 to 138.7. At four pixels, 39 per cent of shapes, 136.5. The
pictures were within 67 pixels in 7.4 million, so the cut was safe. It just wasn't where the work
is. The shapes a ray crosses are not the ones small enough to cull.

A finer acceleration grid. Haven's cell is 2 m. At 1 m the tests fell to 128.8 and at 0.5 m to
117.4, while the cells walked went from 25.6 to 49.5 to 96.8. Fifteen per cent saved for four times
the walking is not a trade.

Merging coplanar neighbours (`tools/merge_coplanar.py`: two polygons on the same plane in world
coordinates, same material, sharing an edge, welded when the result is still convex; and walls
stacked in height or meeting end to end). It removed 2.7 per cent of the shapes and took the tests
from 138.9 to 134.2, with the pictures within 37 pixels in 7.4 million. So it works, and there is
almost nothing to merge. Haven is not flat surfaces cut into fragments. It is a tessellated mesh: 71
per cent of its 653,000 polygons are alone on their plane in world coordinates, and 652,613 of them
carry their own texture mapping, which two neighbours would have to share to become one. Counting
every pair that shares an edge inside a plane-and-material group gives 26,000 possible welds out of
653,000 polygons. Three per cent is the ceiling. It isn't a timid rule.

All three fail for the same reason: there is no redundancy in this map to take out. Every triangle
really is its own surface, with its own plane and its own texture mapping, and a ray down an open
sightline really does cross 119 of them. Anything that lowers that number has to lose something. A
decimated mesh with fewer, larger triangles, made in Blender before the export, and, if it is to
depend on distance, several of them used as a geometry LOD. That is what a mip chain is for a
texture. Geometry here has no equivalent: one level, 929,000 fragments, at every distance.

### What a big map actually costs

Not its size. Measured 2026-09-21 on Haven, 1920x1080, baked, on the card, standing on one spot and
turning all the way round with `-Dbench.dump`, one frame per degree, medians of each 30-degree arc:

| heading | ms | | heading | ms |
|---|---|---|---|---|
| 0-30 | 35.5 | | 180-210 | 55.8 |
| 30-60 | 46.2 | | 210-240 | 74.4 |
| 60-90 | 47.8 | | 240-270 | 32.2 |
| 90-120 | 55.5 | | 270-300 | 15.2 |
| 120-150 | 46.2 | | 300-330 | 12.4 |
| 150-180 | 47.9 | | 330-360 | 23.1 |

The cheapest frame of the turn is 8.6 ms and the dearest that isn't a collection is 74. Six times,
from the same point in the same map with the same 1,924 rays. Nothing changed between those frames
but which way the camera faced, so the 3.8 million surfaces are not what the frame pays for. It pays
for how far the rays get before their columns fill. Face a wall and a column is full after a few
cells. Face down an open sightline and every ray walks to `MAX_DIST` through every cell on the way,
testing what is in them and painting what it finds.

That is the same fact as chunk loading doing nothing for frame time, seen from the other side. For a
map that has to be dense, the levers are a shape's `maxDist`, coarser geometry with distance, and
anything that fills a column sooner. A smaller map isn't one of them.

### Where the time goes

`-Dgpu.stats=true` with `--bench` prints the card's share of a frame. On an M3 at 1280x720, pitch 0,
baked:

| | school | Haven |
|---|---|---|
| pack the lists | 0.22 ms | 0.50 ms |
| upload | 0.26 ms | 0.87 ms |
| draw | 2.09 ms | 5.02 ms |
| read the picture back | 0.96 ms | 1.07 ms |
| everything else (rays, grid, visibility, warp, HUD) | ~1.0 ms | ~3.3 ms |

These are measured with a `glFinish` between the draw and the read, which is what splits them into
columns. Without `-Dgpu.stats` there is no `glFinish` at all, because `glReadPixels` synchronises by
itself. Taking that redundant sync out was worth a third of school's frame, 4.6 ms to 3.3, and it was
a code review that found it, not a profile.

The readback is a stall by construction: the CPU waits for a frame before it can start the next. It
is not there because of the window. The window is GLFW and shares the engine's context, so the shaded
texture is already where the window could draw it. It is there because the pitch warp (`Warp`) ran on
the CPU between the shading and the window. Moving the warp onto the card is what removes it, and
that has been done since (`GpuWarp`). A ring of pixel buffer objects could have overlapped the read
with the next frame's ray walk, for a frame of latency. That would have been an optimisation, not a
way out.

### What it is worth on a desktop

The numbers above are an M3, where the card and the CPU share memory and a power budget. A desktop
with a discrete card is the other shape of the same trade. On an i5-14500 and an RTX 4070, `--flat`,
1280x720, `--bench`, CPU and card runs alternated:

| | school, level | school, 30 deg | Haven, level | Haven, 30 deg |
|---|---|---|---|---|
| CPU | 6.5 ms (154 fps) | 11.1 ms (90 fps) | 49.5 ms (20 fps) | 94.0 ms (11 fps) |
| `--gpu` | 3.0 ms (336 fps) | 5.6 ms (179 fps) | 23.7 ms (42 fps) | 25.5 ms (39 fps) |

Look at the last column. Tilting the view costs the CPU renderer 90% of its frame on Haven, and the
card 7%. What pitch adds is overscan, which means more rows to shade and not more rays to walk, and
the rows are the part that moved to the card. The p99s move the same way: 136 ms to 39.

One caution with these. The CPU's first run of the afternoon was 5.4 ms and its third 6.6, which is
the chip warming up, while the card's three runs sat within 0.2 ms of each other. That is why the
runs are alternated and why `bench.sh` rests between them.

### The two things to know before trusting a number

First: a horizon on a half-integer row makes the two pictures disagree more. A pixel's height is
`eye - (row + 0.5 - hz) * pixelSize`. With `hz` a whole number that offset is a half-integer, and
the heights fall between things. With `hz` ending in .5 the offset is a whole number, and whole
numbers land exactly on the boundaries of the procedural materials: plank lines every sixth of a
metre, brick courses every quarter. Exactly on a boundary, float and double fall on opposite sides,
and the two pictures pick different sides of a hard edge.

`GpuCheck` makes that happen by asking for an odd buffer height, because it puts the horizon at
`h / 2`. School's worst difference is 11 of 255 at 720 and 37 at 719 or 721; Haven's is 3 and 74.
The game does not get there that way. `Warp.place` sets `c.pitch = hz - srcH / 2.0` and the renderer
then computes `srcH / 2.0 + c.pitch`, so the buffer height cancels exactly and the horizon is the
warp's own `vHi + 2`, whatever size the overscan grew to. Rounding the overscan to an even number of
rows would change nothing. That is worth writing down, because it is the fix this section first
recommended. What would actually help is making the material boundaries agree, by snapping the
scaled coordinate before `floor` and `frac` with the same epsilon on both sides, and that is a change
to `Materials`, not to the buffer.

Second: the width does not cancel, and that one was real (2026-09-25). Everything above is about
rows. The same question about columns has the opposite answer. The warp reads the window
`[cx - needW/2, cx + needW/2)` out of the render buffer, where `cx` is `Renderer.centerX()`, which is
`srcW / 2.0`, and `needW` is even by construction. So an odd `srcW` put `x0 = floor(cx - needW/2)`
half a column off the centre the rays are measured from, and every ray in the frame moved with it.

The overscan only ever grows, so this was a picture that depended on what had been rendered before
it. Look up until the buffer grows to an odd width, look level again, and the frame was not the one
you had been looking at a moment before. `--shots` hid it by forcing every camera level. The golden
frames held it instead of catching it: `landing` is level and comes after two tilted cameras, and
its digest was the shifted one. `Host.preparePitch` now rounds `srcW` up to even, which costs at
most one column and makes the window exact at every size. The golden frames were re-blessed onto
values that no longer depend on the order they are rendered in, and `tests/views/school.txt` says
so.

And a third, smaller one: the frame-time tail on Haven is mostly where the camera is pointing, not
the garbage collector. It was first written down the other way round, from a p99 and a `-Xlog:gc`
log read side by side. `-Dbench.dump` exists to tell the two apart. It writes every frame of the turn
in the order it was rendered, and a stall and an expensive heading look nothing alike in that.

A spin on the card at `--feet 3` with the bake, 1280x720: median 5.5 ms, p95 18.4, p99 21.4, worst
23.8. As a distribution that is a four-fold tail. In order it is no tail at all, just one broad hill.
The median by fifteen degrees runs 3, 3, 3, 4, 4, 7, 9, 10, 14, 17, 16, 16, 18, 15, 11, 11, 7, 4, 4,
3, 3, 3, 3, 3 ms: a wall two metres away at one end of the turn, the length of the map at the other.
Tilted, it is the same single hill, between 6 and 105 ms.

The collector is still big and still there: 32 pauses in that run, 0.99 s of them together, the
worst a 673 ms humongous allocation. But none of the 1,440 timed frames is anywhere near 673 ms, so
it fell in the warmup or the load, where a bench's percentiles can't see it. On a map this size,
read a percentile with the series beside it.

### How far the camera looks up, and what stops it

`World.MAX_PITCH` is 50 degrees. It was 45 until 2026-09-30, then 55 for a day, and came back to 50
when looking down at water started growing the buffer upwards as well. It is the engine's number, not
a budget for one map. What a tilted frame costs varies enormously (school renders 45 degrees in 9.2
ms on the card, Haven takes 139), but that is the renderer's problem to solve and dynamic
resolution's to absorb. Shortening the camera on whichever map happens to be slow would hide the
problem in the controls, and a look control whose range depends on which level is loaded is not a
control. A map can still narrow it with `"maxPitch"` when its author wants the view held down. That
is a design decision, and the loader clamps it at 60 degrees.

Under both sits a hard ceiling that is about the machine, not taste. The overscan grows as a tangent
and runs away at 90 degrees less the vertical half-FOV: 60.6 degrees at the default 90-degree field
of view, and 69.6 back when the default was 67. `Warp.fits` returns the furthest pitch whose upright
image still fits a budget, and `Host` clamps to it every frame. The budget is a multiple of a level
frame (80 now; it was 16), not a count of pixels. The overscan a pitch needs is a fixed ratio of the
frame at any render size, so a budget in pixels would let the camera tilt further on a small window
than on a large one. As a multiple it stops at the same angle at 640x360, 1280x720 and 3840x2160.
It is worked out by asking `Warp.plan` itself, not by a second copy of its formulas.

Widening the field of view eats into that ceiling. At 90 degrees across, the old budget of 16
allowed 48.5 degrees of pitch where it had allowed 55.1 at 67, against a `MAX_PITCH` of 45 at the
time: three and a half degrees of margin instead of ten. A tilt got dearer with it too. At 720p, 45
degrees asks for a 4142x2111 buffer, 9.5 level frames, against 2902x1680 and 5.3 before. Raising
either number means measuring the other.

The budget went to 80 on 2026-09-30, because the overscan stopped being paid for in full. The card
now shades only the upright pixels the warp reads (`GpuWalls.shadeWarped`), and each column fills
only the rows it can be read in (`Warp.columnRows`), so a bigger upright image costs its rays and
its memory and not itself. 55 degrees asks for 11352x6175 at 720p, 76 level frames, inside the
budget. Past that the curve is one over the distance to 60.6 degrees (58 is 355 frames, 60 is
6,126), so that is about as far as it goes.

On the card there is a second ceiling, and it is not a multiple of anything. No texture may pass
`GL_MAX_TEXTURE_SIZE` (16384 on an M3), and the upright frame and the span texture (a row per column,
with the warp's table of output rows under them) are textures. A request past it is not refused. It
reads zero. So `Host.pitchLimit` also stops where they fit (`Warp.fitsWithin`), which at 1920x1080 is
a little under 55. The upright frame's own texture only gets storage if an upright frame is ever
drawn, and a window never draws one.

### What looking up costs, and one thing that did not help

Tilting is expensive on Haven, and the overscan buffer is not why. With the same buffer (2884x1675)
and the same 2,888 rays, a level frame is 18.0 ms and a 45-degree frame is 130.4 ms. What changes is
what a column crosses. Per column on Haven at `--feet 3`:

| per column | level | 30 deg | 45 deg |
|---|---|---|---|
| shapes drawn | 36.0 | 154.3 | 223.4 |
| candidate planes made | 109.7 | 901.9 | 1,474.7 |
| distinct planes among them | 11.2 | 73.7 | 101.0 |

A level ray dies on the first wall: the column fills, `open` reaches 0, the walk stops. An upward
ray clears the rooftops and runs to the far distance, so it meets six times as much.

Fifteen times as many candidates as distinct planes looks like the obvious thing to attack, and
`nearest` is 19.4% of a 45-degree frame. It was attacked. A winner-per-row buffer, filled once per
stretch instead of a search per candidate per row, bit-for-bit identical. It measured 9% slower level
and 4% slower at 45 degrees, five alternating runs with rest, and was reverted.

Two reasons, both worth remembering. `nearest` short-circuits, returning the moment anything nearer
turns up, so its scan is nothing like the length of the candidate list. An instrumentation counter
that adds `nc` per call, as ours did, overstates it several times over. And a buffer pays one
distance for every row of every competing candidate, where the search often pays one or two. The
arithmetic it removed was not there to remove.

So the 19.4% in `nearest` is mostly distances that really have to be computed. Lowering it means
fewer surfaces to compare, and that is the map, not the loop.

### Platform

OpenGL reaches the engine through LWJGL, and the context is a hidden GLFW window (`Glfw.root()`)
that every visible window is created sharing. That is one path on every platform. Until 2026-09-28
it was a `GlPlatform` with a backend per operating system: CGL on macOS, WGL on Windows, and nothing
on Linux, because getting a context with no window behind it is the one part of OpenGL that was
never standardised. GLFW does it everywhere, so Linux has a card path for the first time. CI runs
`--gpu` there under `xvfb-run` on Mesa's llvmpipe, and the whole gate passes with the numbers an M3
gives.

A machine with no card (no display, no OpenGL driver, a virtual machine, a Windows CI runner that
offers no core profile) shows up only when the context is asked for, as an
`ExceptionInInitializerError` out of `Gl`'s field initialisers. `Host.graphicsCard` catches it,
prints one sentence, and the CPU renderer, which is the whole engine, carries on. `-Dglfw=none`
forces that path on a machine that has a card, which is how it gets tested here instead of on CI.
Naming `Gl` at all loads it, so a line that only mentions it on a CPU path takes every frame down.

Two cards is a trap. On a machine with an integrated GPU and a discrete one, OpenGL takes whichever
card Windows prefers for `java.exe`, a per-application setting under Settings > System > Display >
Graphics, read once when the JVM starts. It is not the card the window is on. Putting the hidden
window on the discrete card's monitor was written, run and measured, and made no difference at all,
which is why that code isn't here. So the run prints the renderer string and, when there is more
than one card, the name of the one it did not use (`GpuNote`, the one piece of the old WGL backend
worth keeping). "Intel UHD Graphics 770" looks exactly like success.

One GLSL note that only Windows found: `packed` is a reserved word. Apple's compiler accepts it as an
identifier anyway and Intel's does not, so the wall shader's `unpack` takes a `bits`.

## Shading in light (`--hdr`)

For as long as there had been a bake, its last step was wrong. A colour arrives as three sRGB bytes
off a PNG, the light on it arrives as a float, and the renderer multiplied one by the other. sRGB is
a transfer curve. It is not a quantity of light, and multiplying by it over-darkens everything: a
surface at a third of full light came out at about a ninth of its colour. The bake made the same
mistake one step earlier. A surface bounced `colour / 255` of the light that hit it, so a mid-grey
wall returned 0.50 where it really returns 0.22, and two bounces of that filled every room with a
flat grey glow.

`--hdr` puts the shading right. The colour is read back into light (`Renderer.linOf`, the sRGB
transfer), the light is applied there, the bake bounces the same way, and the result comes back out
through a filmic curve and the sRGB encode. The old per-channel knee at 200 is still there for the
old path. It compresses each channel separately, so a bright red saturates in red first and shifts
hue on its way to white. The ACES fit rolls the three together, the way a film stock does.

### What the maps had to catch up on

The two halves were measured apart, and for a while the bounce had a switch of its own, because
Haven lost badly by it while school gained. School's courtyard, 1280x720:

| school | mean level | contrast (p95 - p5) | chroma |
|---|---|---|---|
| sRGB shading, sRGB bounce | 116.3 | 130.7 | 29.0 |
| linear shading, sRGB bounce | 140.2 | 149.0 | 28.9 |
| linear shading, linear bounce | 120.3 | **144.1** | **37.3** |

The middle row is the warning. Fix the shading and leave the bake alone, and the picture gets worse
in the way that matters: the shading stops over-darkening while the bake goes on over-brightening,
and the frame washes out. Both halves or neither.

What Haven was losing turned out not to be the bounce at all. `Lighting.rgb` converts every colour
the bake is given, and a light's colour is a colour too: the sun's, the sky's, the ambient term's
and each of the 189 lamps'. Read properly, every source gets dimmer, and the transfer is steepest at
the bottom, so the darkest lose the most. Haven's sun, nearly white, lost 8 per cent. A lamp at
`#ffc4ad` lost 20. The ambient term, `#3a3f46`, kept a fifth of what it had, and in a corner the sun
never reaches, the ambient term is most of the light there is.

The bounce had very little to do with it. Pushing `reflectance` to 0.95 and taking a third bounce,
which is most of what that end of the map has to give, closed a tenth of the gap on the enclosed
cameras. Writing the map's light colours as sRGB closed all of it. That is the exact inverse, with
nothing chosen by eye. Against the same bake reading the old colours as light, spot7, spot8 and
spot9 go from 93.8 / 81.0 / 106.0 mean to 93.2 / 80.9 / 105.0, and from 23.7 / 13.7 / 26.0 chroma to
23.5 / 13.7 / 25.8. None of this touches a map's surfaces. A surface's colour is what it reflects,
and reading that as light is the very arithmetic being corrected.

So `--hdr` is the whole change, bake included. `-Dhdr.bake=false` is what is left of the switch. It
is there to reproduce the middle row of that table, and no map should be run that way.

The grade comes last, after the tone curve and the sRGB encode, because that is what a grade is.
It went in the wrong place first. A map's `lift` is a fraction of the picture's range (Haven asks
for 0.08, which is 20 levels out of 255), and applied to light it became 0.08 of full daylight, or
76 levels, which laid a grey sheet over the whole map.

`EXPOSURE` (0.696, `-Dhdr.exposure`) is the one number that was chosen and not derived. It puts a
mid-grey surface under full light back where the old pipeline had it, so the two can be compared
without one of them simply being darker.

### What it cost to get the two paths to agree

`--hdr --gpu-verify` failed the first time, over 22% of the frame, and it had caught something real.
A procedural material's texture factor was folded into the shading scalar on the CPU
(`c * (tex * k)`) and applied to the colour on the card (`(c * tex) * k`). In the old arithmetic
those are the same number. In linear they differ, because the factor darkens albedo and belongs on
the sRGB side of the conversion. Before `--hdr` there had been nothing to see. `Renderer.shadeS` is
where that lives now, for both.

`H` switches the shading while the game runs, which compares two ways of finishing one bake. The two
bakes are different files, since the light cache keys on the mode.

## Anti-aliasing (`--ss`)

`--ss N` renders at N times the output size in both axes and box-filters each N x N block down to
one output pixel. `--ss 1`, the default, is a no-op: the pitch warp writes straight into the window
image and no downsample runs.

| Output | `--ss` | Rays | ms / frame | fps |
|---|---|---|---|---|
| 640x360 | 1 | 640 | 0.65 | 1550 |
| 640x360 | 2 | 1280 | 2.64 | 378 |
| 640x360 | 3 | 1920 | 6.39 | 157 |
| 1280x720 | 1 | 1280 | 2.88 | 347 |
| 1280x720 | 2 | 2560 | 11.7 | 86 |
| 1920x1080 | 2 | 3840 | 29.0 | 34 |

Cost goes with N squared, as it has to: `--ss 2` is four times the rays. Where it helps most is
rooflines against the sky and the speckle in the ceiling-panel and stone textures, which alias badly
without it.

### What a frame throws away

A renderer in its steady state should allocate almost nothing. This one was allocating gigabytes a
second. A flight recording of a Haven benchmark, filtered to what the frame loop reaches, put it in
three piles:

| | sampled over 380 frames | what it is |
|---|---|---|
| 11.0 GB | lambda captures | one `IntUnaryOperator` a surface a column, from `flat` and `drawHit` |
| 7.1 GB | `Column.<init>` | 3.5 MB of `stamp` a time, and it was being built 2,601 times |
| 6.4 GB | `Geometry` hits | a `PolyHit` or a `SegHit` a ray-versus-shape test |

The second one looked impossible. `Column` is per-thread scratch and there are a dozen threads, so
it should be built a dozen times. It was built 2,601 times in 380 frames against three buffer
resizes, so the resizes were not it. The threads were. The common ForkJoinPool grows and retires
workers as a frame loop stalls and resumes, and a `ThreadLocal` gives every new worker a fresh 3.5
MB `stamp`, an int per shape so that a ray tests a shape once however many cells it meets it in.

Lending the Columns from a queue, instead of tying them to a thread, took that from 2,601 to 16.
The picture cannot move. A chunk already renders many columns through one Column, so reuse was the
existing behaviour and only the bookkeeping changed. The determinism test is the one that would
notice if it did, and it didn't.

The lambdas were the bigger of the two left. `paint` was handed a closure: `flat` returned one over
the plane it had just described, `drawHit` another over the side of a shape, and each was built
afresh for every surface of every column. A flight recording of a whole benchmark (1,120 frames of
Haven at 854x480, loading included) sampled 50.0 GB of allocation, and 38.3 GB of it was those
closures.

What replaced them was already in the file. Building the GPU path had put a description of every
surface on the column, its material, colour, light, lightmap and texture, so that a shader could
draw what the CPU drew. That description is the closure's captured state, written out. So `Surf`
carries it now. The `Plane`, the `Cand` and the masked entry hold one each and are pooled as they
already were, and `paint` colours a row by switching on what kind of surface it has. The same
recording of this build samples 11.7 GB, and none of it comes from describing a surface.

The proof that the picture did not move is the usual one: school and Haven golden digests unchanged,
and `GpuCheck` agreeing with the card to the same 2 of 255 on Haven and 10 of 255 on school as
before, pixel for pixel.

That leaves the third pile, now 2.3 GB of `Geometry.SegHit`, one per ray-versus-segment test.
`PolyHit` is lent to the renderer. Its sibling still isn't.

### What a texel costs to keep

A mip chain is level 0 and everything below it, and the two are made of different stuff. Level 0
holds what the PNG held, whole numbers from 0 to 255. Every level below it is an area average, and
`Materials.Level.half` deliberately does not round those, because rounding them shows: it is the
difference between agreeing with the card to 2 of 255 and to 3.

So level 0 is kept as bytes and the rest as floats. Level 0 is three quarters of a chain, which on
Haven is the difference between 2,340 MB of heap and 1,023 MB:

| | texels | kept as |
|---|---|---|
| level 0 | 153,365,200 | 438 MB of `byte[]` |
| levels 1 and below | 51,121,610 | 585 MB of `float[]` |

No pixel moves. A byte widened to double is the same double that a float holding the same whole
number widens to, and the golden frames are the test. It is a little faster as well, which was not
the point and is not a surprise either: a byte has four times the cache density of a float, and
level 0 is what a near surface samples most. A/B over five alternating runs on Haven at 854x480:
2% level, 5% tilted, and the run-to-run spread fell from 19% to 7%.

## Texture filtering

Sample a texture once per pixel and it falls apart at a distance. One pixel of a far wall covers
several centimetres of it, and asking the texture for the colour at one point in that patch lands on
one side of a mortar line or the other more or less at random. The lines crawl and merge as you move,
and a tiled floor running to the horizon turns into moire. More rays do not fix it. They move the
distance where it starts, and that is all. This is the thing that makes a raycaster look dated, and
3D games have handled it the same way since the late nineties: don't draw detail smaller than a
pixel, draw its average.

Usually that means mipmaps: the texture stored again at half size, quarter size and so on, with the
hardware picking the level where one texel is about one pixel. Here the procedural textures are
functions, so there is no pyramid to pick from. Each one is told instead how wide a pixel is on the
surface it is drawing (`w`, in metres), and fades out whatever is finer than that.
`Materials.keep(w, feature)` says a detail needs about two pixels across it to show; past that it is
faded into the mean it averages out to. Thin lines go first (mortar is 2.5 cm, floor grout 2.5 cm,
panel joints 2.4 cm), then the per-brick and per-plank shades (25-50 cm), and the coarse mottling
last.

The renderer gets `w` from the projection. One pixel is `t / F` metres across on a surface square to
the eye, and that shrinks as the ray count goes up, so `.` really does buy back distant detail. It
does more than smooth edges.

### Anisotropic filtering

A surface seen edge-on, like a corridor wall or a floor running to the horizon, covers a long thin
strip per pixel, and the strip's two sides can differ a hundredfold. Filtering both to the long side
smears a distant floor into paste. That is exactly what the first attempt here did, and the ceiling
grid vanished about four metres out.

So it samples along the strip instead, up to 8 times, each sample filtered to the width of the
narrow side. It is the same trick as the "16x AF" setting in a graphics driver. For a floor the strip
runs away from the eye and is `F / (row - horizon)` times its own width. For a wall it runs along the
surface and is as long as the wall is turned away.

It roughly doubles the shading time (640x360: 627 to 333 fps). It is also the difference between a
corridor that crawls and one that doesn't.

## The ray view

The second window is the map from above. The player always points up, so left and right there match
left and right in the main view.

- The yellow fan is one line per column, drawn out to where that column became full. You can see each
  ray stop.
- Pale yellow cells are the grid cells the red ray's DDA walked. Only shapes registered in those
  cells are ever tested.
- Cyan is the camera direction and the camera plane, and the perpendicular distance t to the first
  thing the red ray actually draws.
- The red line is the column the mouse is over in the main view, which marks the same column with a
  thin red line of its own. Everything the ray runs into is numbered: an orange dot is a shape that
  got drawn, a grey dot one that was already hidden, a blue square a crossing into another region.
  The list at the bottom has every event in order of distance and how many rows it filled.

The mouse wheel zooms, and `N` switches between following the player's turn and north up.

## Fisheye

Projection always uses the perpendicular distance t, the hit point's distance along the view
direction, so there is no fisheye. Press `F` for straight-line distance to compare. Things near the
edge of the screen do look wider. That is ordinary perspective, and it gets more obvious as the field
of view widens, so turn it down with `[` if it bothers you.

## Looking up and down

A column renderer can only draw columns that stay vertical, so by itself it can only look up and down
by y-shearing: slide the horizon and keep every vertical edge vertical. A real camera that tilts back
sees verticals converge towards the top of the picture. Without that the top and bottom of the screen
stretch (looking down from the first floor, the pool comes out far too wide), and it feels like a
vertical fisheye.

So the renderer still y-shears, and `Warp` turns the result into a real tilt. Both are pinhole
cameras at the same eye point, one with an upright image plane and one with a tilted one, which makes
the tilted picture an exact projective warp of the upright one. For pitch alone that warp works row
by row. Output row `v`, measured up from the centre, reads a single upright row `v'`, stretched
horizontally about the centre by `s`, where `p` is the pitch:

```
z = F cos p - v sin p      s = F / z      v' = F (F sin p + v cos p) / z
```

Looking up, the top rows have `s > 1`. They need rays outside the normal field of view, so the
renderer draws a somewhat wider and taller upright image (overscan), and only the columns and rows
the warp will read. At pitch 0 the warp is an exact 1:1 copy, and the frame is pixel-identical to the
old y-sheared one.

| 640x360 | rays | ms / frame |
|---|---|---|
| pitch 0 | 644 | 0.82 |
| pitch 30, true perspective | 946 | 1.10 |
| pitch 30, y-shearing (`--shear`) | 644 | 0.69 |

`P` switches to the old y-shearing to compare, and `--shear` does the same for `--shot` and
`--bench`. The red line marking the traced column leans with the tilt. One ray is a vertical line in
the world, so it converges like every other vertical.

## Lighting

Light is baked once at load and read back per pixel. None of it costs frame time; every frame rate
in this document has all of it on. `--unlit` (it used to be `--flat`) skips the bake and falls back
to the old flat shading, which is the quickest way to see what the bake is worth, and the quickest
way to answer a question about geometry.

### Lightmaps

Every surface the renderer can draw gets a grid of RGB light levels over its own flat coordinates:
world `(x, y)` for floors, ceilings and the tops of shapes, and `(distance along the face, height)`
for anything vertical. A world of vertical walls and horizontal slabs unfolds like that by itself,
so there is none of the UV unwrapping a general 3D engine needs. The demo map is 160k texels at
20 cm across 842 surfaces. A pixel samples its surface's map bilinearly and multiplies:
`colour * texture * light`.

### Shadows

Shadows are the renderer's own question, asked from somewhere else. Can this texel see that lamp?
Draw the segment between them, project it onto the map and walk it: region boundaries for the
storeys, grid cells for the furniture, checking the height the segment has where it crosses each
thing. That is `Occluder.clear`, and it is the same DDA walk `Renderer` draws with. So the shadows
are exact against the same geometry. A desk shadows the floor, a storey shadows the one below, and a
gap between two railings lets a line of light through.

### Bounced light

This is where most of the modern look comes from. Each texel fires a small hemisphere of rays (32 by
default, cosine-weighted, turned by a per-texel angle so neighbours do not sample the same
directions). `Occluder.hit` is `clear`'s bigger sibling: it keeps the nearest crossing instead of
stopping at the first one, and reports which surface it was. A ray that gets away sees sky. A ray
that lands somewhere brings back that surface's own light times its colour.

Run the pass again and each surface bounces what it gathered the first time, so two passes give two
bounces. That one mechanism is the darkening in corners, the soft fill in a room with no lamp on,
the warm light off the courtyard grass onto the corridor wall, and the light spilling out of a
doorway. None of them is special-cased anywhere.

### Soft edges

A shadow ray answers yes or no. One ray per texel puts every shadow edge on a texel boundary, and the
edge comes out as a staircase of 20 cm steps. So each texel is sampled at eight spread-out spots
inside itself, each aiming at a different point on the sun (a disc two degrees wide) and on each
ceiling panel (a 60 cm square). The average lands between lit and dark, which is what the bilinear
filter needs to draw a smooth edge. It is also what really happens: an edge is soft, and softer the
further the shadow falls from whatever casts it. The gather rays start from a different spot in
each texel for the same reason.

### Tone mapping

Bounced light pushes lit surfaces past full brightness, and clipping at 255 turns a sunlit wall
into a flat white shape. In sRGB, `Renderer.TONE` leaves everything below 200 exactly as it was and
rolls the rest off towards 255, from a 2048-entry table, so the extra light reads as detail. With
`--hdr` a filmic curve does that job; see "Shading in light".

### What it costs, and how to set it

School's bake casts about 21 million rays, one task per texel row spread over every core, and takes
a few seconds. That is a handful of frames of what a real-time engine's ray-traced lighting spends,
paid once at load.

It is set in the map's `lighting` block:

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

`panelLights` needs no list. The lit cells of a panel ceiling, the same cells `Materials` draws
bright, become the lights, so what glows is what lights the room. `reflectance` is how much of its
own colour a surface bounces back. `bounces: 1` still gives sky and ambient occlusion, just no
bounce, and `reflectance: 0` gives direct light only.

### Lamps that flicker

A light that says `"flicker": "group"` is baked apart from the rest, in a pass of its own with its
group and nothing else: no sun, no sky, no ambient, no other lamps. The bake is linear in the light
(the same rays, the same smoothing and dilate, each a sum of what arrives), so that pass is exactly
what the group adds to the base, bounced light included. Full on, base plus groups lands within one
level of a plain bake.

A map a group reaches keeps the base in `rgb` and up to two groups beside it, as `over0` and
`over1`. `LightMap.sample` adds each at its level. On the card the overlays are packed into the atlas
and found through the fourth float of the map's record, which is 0 for a map without flicker, and
the levels are the `flickerLevel[]` uniform. A lamp dropping out is one number changing.

The first version recomputed the maps and sent them to the card every frame. Three lamps cost 37 to
82 ms a frame. The overlays cost 0.06 ms. Do not go back.

Two details made it come out right. Each pass clears `ok`, `direct` and `bounced` before it starts,
because `dilate` marks the texels it filled as good and a second pass that inherited that lit texels
inside walls; that was what put the first comparison 58 levels out. And a group that adds less than
0.003 anywhere in a map is folded into that map's base (`-Dflicker.faint`), since bounced light
reaches almost everything a little, and three lamps otherwise touched 60,571 maps.
`-Dflicker.levels=a,b,...` holds the groups at fixed levels for a capture, which otherwise takes
them full on.

## Minimap

`M` shows and hides it. It shows where you can go, not the shapes seen from above, and it is drawn
the way Valorant draws one: grey floor, green bomb sites, light boxes with a white edge for the
crates and pillars standing in the open, and nothing at all for walls, buildings you cannot get into
and the world outside the map.

`Minimap` works it out once, the first time it is shown, from the same shapes the player collides
with, on a 25 cm grid:

1. Every square gets the surfaces a player could crouch on: a region's floor or a shape's top, with
   nothing solid in the 1.15 m above it.
2. A flood from where the player stands walks to each neighbouring square's highest surface that is
   at most 0.45 m up, or any distance down. That is a step, not a jump, so a crate stays a crate.
3. An island of unreachable squares under 25 m2, surrounded by floor, is an obstacle.

After that, drawing it is one image and the player marker. The old minimap filled every shape every
frame. For Haven's 38,000 shapes that was 30 ms a frame; it is 0.6 ms now. Working it out takes about
0.4 s for Haven's 137,400 shapes. The image is cropped to what is drawn, and the map can turn it by
quarter turns. Haven's is turned to match Riot's own minimap, with the attackers' spawn on the right.

`-Dminimap.debug=true` also paints, in blue, ground a player could stand on that the flood never
reached: red where a jump would have got there, yellow where a ledge is in the way. That is how to
find a doorway the conversion closed.

## Map format

The comments at the top of `maps/school.json` are the full reference. In short:

- Regions are convex polygons with a `floor` and a `ceil`. Leave out `ceil` (or write `null`) and
  the region is open to the sky. `top` is the top of the wall above the opening, as seen from an
  open-air region looking in.
- Shapes are `wall` (a segment), `circle`, `box`, `poly` or `ngon`, each with `z0` (bottom) and `h`
  (top). `maxDist` stops a shape being drawn beyond a given distance.
- `hx` and `hy` tilt a shape's top, and `z0x` and `z0y` its bottom, in metres per metre of x and y.
  `h` and `z0` stay the heights at the middle of the shape's bounds. With both, a polygon is a slab
  at any slope (a diagonal brace, a plank, one face of a mesh) and a `wall` is a trapezoid standing
  up. A ray still meets a column of solid between two heights, so the renderer only has to know
  where those heights are.
- `chunks` lets a big map keep its shapes in files beside it: `"chunks": ["haven/2_3.json", ...]`,
  each `{"shapes": [...]}`, with paths relative to the map. Haven is written that way, one file per
  32 m square, and the map file itself is only the header: lighting, regions, textures, spawn.
- `array` repeats its `items` `count` times, moved by `step` each time. Desks and chairs,
  colonnades.
- Polygons must be convex, and this is checked on load. An L shape has to be split.
- `site` on a shape (`"A"`, `"B"`, ...) marks a bomb site's floor, and the minimap paints it green.
- `"walkable": false` on a region says its floor is only the bottom of the world and the real ground
  is made of shapes, as in an imported map. The minimap does not count it as somewhere to stand.
- `"minimap": {"rotate": -90}` at the top level turns the minimap by quarter turns (degrees,
  clockwise), so a map can be shown the way round its players know it.

`"maxDist": 290` at the top level is how far a ray goes before it is the sky's, in metres. The
engine's own is 80, more than school or Haven's crop is across. A street 130 m long lost the towers
behind its far end to it, and nothing said so: it took a render of the same camera in Blender to
see they were gone. A ray down an open sightline walks every cell that far, so it is the map's to
ask for, not a number to raise for everyone.

The sky as drawn is `"horizon"`, `"zenith"` and `"below"` in the lighting's `"sky"`, three colours
beside the `color` and `intensity` the bake lights with. A pixel no surface covers gets the gradient
from the first to the second over nine tenths of the view's height, and the third under the
horizon. Glass and water mirror the same. A map that says nothing keeps the afternoon the engine
always drew (`#cddeee`, `#5087d2`, `#3a3c40`) to the bit. A night map says otherwise.

### Glass

`"mask": "glass"` on a wall or a box makes its sides panes. Glass is not a lit surface. What is
behind it shows through, darkened towards a shadow of the shape's `color` (`Materials.GLASS_ALPHA`,
`Renderer.GLASS_TINT`), and Fresnel (Schlick, four per cent straight on) turns it into a mirror at a
grazing angle.

What it mirrors is what is really there. A pane is a vertical mirror, so the ray it sends back is
level, and what it shows is what the eye mirrored in the pane sees along the mirrored ray. So it is
the same column renderer, run a second time for the pane's rows from that eye, with nothing nearer
than the pane (`Renderer.mirrors`, `mirror`). A point met t away projects to the row it would in the
view t away, so the projection carries over whole. On the card the second run's spans go in tagged
with the pane and are shaded from the mirrored eye (`GpuWalls.reflectAt`).

A reflection leaves out masked surfaces: leaves, and other panes. The bake lets light through glass
the way it does through the gaps in a tree, so a room behind a window is sunlit. Give a window its
frame as thin boxes; a frameless pane reads as nothing.

### Puddles

A floor of `water` is standing water everywhere and reflects on its own. `"puddles": 0.3` on any
other region covers about that share of its floor with standing water (`Materials.puddle`: two
octaves of noise in world coordinates, so a puddle stays put). Wet ground is darkened, and the water
reflects what the column itself drew.

That works because a level mirror sends a ray back up in the same vertical plane, which is the
column's own. The reflection is a walk up the column's rows to the first surface nearer than the
reflected ray (`Renderer.reflect`, `mirrored`), and the same walk on the card
(`GpuWalls.mirroredAt`). No second ray, and the column constraint is untouched. What the column did
not draw, like a wall hidden behind a nearer post, the reflection does not have either. On a map
with puddles each column also renders the rows its puddles reflect (`Warp.mirrorRows`).

A shape takes `"puddles"` too, for its top. An imported map's ground is the tops of its shapes and
its one region's floor is only the bottom of the world, so that is where the rain has to lie. Level
tops only: `hx` or `hy` with `puddles` is refused on load, because the reflection is worked out for
a level mirror at one height. Nothing else had to change. A plane's span already carried the share
of water and its height to the card, and a region's floor had simply been the only thing that ever
filled them in. The noise is in world coordinates, so neighbouring slabs with the same share meet
without a seam.

### Detail

`"detail": 1` on a shape with an `img` lays its material's own pattern over the image (`mat` on its
sides, `topMat` on its top). Before this, a mapped surface ignored its material for everything but
the bake. The image was the whole of its colour, and a photograph of a road is a few centimetres a
texel at best, so the ground a player stood on was a blur.

The procedural materials are functions. They are as sharp underfoot as anywhere and already fade
out at the width of a pixel, so there is no moire to pay for. The image is multiplied by the pattern
*about its own mean*, `1 + detail (pattern / mean - 1)`. That leaves the image's colour where it was,
and it is exactly one past the distance the pattern fades at (`Renderer.detail`; `wallColour` and
`planeColour` on the card). The amount is a whole number of 255ths because it reaches the card in
the parity slot of the image's record. A record had no float to spare, and the flag that shares the
slot is its parity.

`asphalt` is the material written for it: grit a centimetre across. It is finer than any of the
others, which were all made to be a surface's whole texture and not its grain.

### Lamps that flicker

A light with `"flicker": "name"` joins a flicker group, and the lighting's `"flicker"` says how each
group behaves: `{"name": {"pattern": "buzz", "every": 15}}`. `buzz` is a tube that every so often
stutters for up to a second, `every` seconds apart on average; `dead` is off; `steady` is on. A shape
with `"glow": 5` gives off light of its own into its lightmap, in `"glowColor"`, and with `"flicker"`
it dims with its group. That is how a ceiling panel goes grey when its lamp drops out. How the bake
does it is under Lighting.

## Storeys

Regions stack. `World.regionsAt(x, y, out)` returns every region containing a point, lowest floor
first, and a ray carries that whole stack instead of a single region. Two rules follow:

- Anything outside all of the stack's `[floor, ceil)` ranges is solid. The gap between the ground
  floor's 3.2 m ceiling and the first floor's 3.6 m floor *is* the slab. Nobody models it.
- A hole is an absence. The courtyard and the stairwell have no upper-storey region, so the stack
  there is one deep, and you see straight down. You also fall.

Drawing every storey in the stack needs no depth sorting. For any given row, a higher floor is
always seen nearer than a lower one, and a lower ceiling nearer than a higher one, because `t`
scales with `|z - eye|`. Segments are handled near to far and `paint()` only fills rows that are
still empty, so the surface you ought to see always gets the row first.

Crossing a boundary is one rule: there is a wall wherever open space on the near side meets solid on
the far side. That gives a lintel where the far ceiling is lower, a step riser where the far floor
is higher, and the edge of a floor slab seen from either storey. The sky above a region's `top` only
opens for a viewer who is under open sky too. Indoors, your own ceiling is in the way.

### Not paying for the other storeys

The grid is 2D, so the cells a ray walks upstairs are full of the furniture downstairs, and the other
way round. Nothing is decided per storey up front (the courtyard proves you *can* see the other
floor). It is left to the row test instead: a shape whose every possible row is already painted
cannot show, and `skip()` rejects it before any intersection. Two things make that test bite.

Floors are painted a cell at a time (`paintAhead`). They used to be painted only when the next shape
or boundary came along. Crossing an empty room upstairs left open the rows your own floor was about
to cover, and the desks below were intersection-tested only to be hidden.

Each cell also bundles its shapes by the region they stand in (`World.Group`), so one room's
furniture in that cell gets a single test on the union of their bounds. Full-height walls belong to
no single storey and stay on their own.

Neither changes a pixel: the frame hashes are identical before and after, at pitch 0 and +/-30.
Measured per 640-column frame, standing in the first-floor classroom above the ground-floor desks:

| | before | after |
|---|---|---|
| per-shape `skip()` tests | 11,634 | 349 |
| group tests (shapes rejected by them) | - | 3,463 (15,508) |
| ground-floor shapes intersected, all hidden | 7 | 0 |

Looking up from the ground floor gives the same. On a two-storey map the saving is lost in the noise
of the frame time. It is there for the maps with more storeys, where the cost grows with each one.

### A tree in every cell

One union per group rejects a whole storey at once, but it cannot reject part of one. Then a 32 m
square of Haven was converted triangle for triangle, 218,000 shapes in one square. A 2 m cell held
hundreds of them and a ray through it passed a few, yet every other one still got its own `hidden()`
test. Java Flight Recorder put `hidden()` and `skip()` at 41% of the frame. The bake had the same
problem three times over. `Occluder.shapesClear` walked every member of every cell a shadow ray
crossed, `Occluder.open` did the same for every lightmap sample, and `Lighting.direct` went through
all 189 lamps for every sample before finding most of them too far away.

So each group is now a bounding volume hierarchy (`World.Group`). Members are split in half along
the widest spread of their middles, down to four per leaf. The renderer walks it with the same
`hidden()` test on each node, so a node whose rows are painted rejects everything under it. The
`Occluder` rejects a node whose height range or plan the segment misses, and, when looking for the
nearest hit, one it enters beyond the nearest hit so far. Lamps are indexed on a 4 m grid
(`Lighting.indexLights`): a sample visits only the lamps whose range reaches its square, and each
lamp keeps its own place in the list for its random spot.

None of it changes a pixel or a texel. That was checked. Visiting shapes in a different order could
change which of two surfaces at exactly the same distance gets drawn, so those ties were first made
to go to the lower shape id, in the renderer and the `Occluder` alike, and that build is the
baseline. Against it, every `--shot` image, depth and albedo file from school (flat and baked) and
from ten Haven cameras on two maps is byte-identical, and a hash over every channel of every
lightmap is identical, with the same ray count.

The first version was not. Its point-in-node test for `open()` was not padded, and a tilted shape's
`bottomAt` rounded a hair below its lowest corner. 480 rays went differently. No picture showed it.
The hash did.

Measured on an 8-core Apple M3, 1280x720, the camera at spot8, two `--bench` runs each (single runs
on this machine differ by up to 40%):

| | before | after |
|---|---|---|
| base conversion (220,287 shapes): bake | 132.8 s | 48.4 s |
| base conversion: frame, level / tilted 30 degrees | 20.6-26.4 / 43-48 ms | 22.2-22.5 / 41-43 ms |
| one square as triangles (426,207 shapes): bake | 760 s | 248 s |
| one square as triangles: frame, level / tilted 30 degrees | 50-51 / 116-150 ms | 33-38 / 100-103 ms |

The base conversion's frames never needed it and did not change. On the triangles, the tests that
were 41% of the frame are now 8%. What is left is shading: texture filtering is 61%, and choosing
between overlapping tops and bottoms (`nearest`) is most of the rest.
