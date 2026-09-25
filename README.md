# ColumnRay

A 2.5D raycasting engine written in plain Java (Swing) with no external libraries.

The renderer casts one ray per screen column and draws each column as a vertical strip whose
height follows from the perpendicular hit distance. The world is described as a 2D plan of
regions and shapes with floor and ceiling heights, which allows stacked storeys, sloped surfaces
and textured geometry while keeping the column-based renderer.

## Features

- Column renderer with a DDA walk over a uniform acceleration grid and a bounding volume
  hierarchy per grid cell
- Multiple storeys through stacked regions, including open-air regions and floor openings
- True camera pitch, implemented as an exact projective warp of the column renderer's output
- Procedural and image textures with mip mapping and anisotropic filtering
- Baked lightmaps: sun, sky, point and panel lights, soft shadows and two bounces of indirect light,
  cached on disk and reused until the map, the settings or the bake code change
- Dynamic resolution driven by measured frame time
- Supersampled anti-aliasing
- Minimap computed from walkable space, and a top-down debug view of the rays
- Keyboard input by physical key position, independent of the active layout (macOS)

## Requirements

- JDK 22 or newer. Keys reads the physical key state through the foreign function API,
  which is a preview feature before 22
- Developed and tested on macOS. On other platforms, input falls back to AWT key events read
  as a US QWERTY layout
- Shading on the card is the default, and needs an OpenGL backend: macOS (CGL) and Windows
  (WGL) have one, Linux does not yet. Without one it prints a sentence and the CPU renderer
  carries on, which is the whole engine. `--cpu` asks for that path on purpose. On a machine
  with two graphics cards, which one draws is Windows' per-application preference for
  `java.exe` (Settings > System > Display > Graphics), and the card it ended up on is named
  on the first line of the run

## The engine and the game

They are two packages, and the boundary is meant to be used rather than admired. `engine` owns the
window, the frame loop, the buffers, the renderer, the map and the bake; it has no `main`, no idea
which key walks forward, and no player. `game` owns all of that, and reaches the engine through
`Game` - four methods, of which two matter - and through the public methods on `Host`.

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

`Sandbox` is the game this repository ships with - walk about a map and look at it - and it is
worth reading as the worked example: about two hundred lines, none of which the engine knows
anything about. Collision against the map is `Body`, line of sight is `Occluder`, and both take
the size of the body from whoever asks, because the engine does not know how tall anybody is.

## Building and running

```bash
./run.sh
```

`run.sh` compiles `src/` into `out/` when a source file has changed (see `build.sh`) and starts
the game with `maps/school.json`. Arguments are passed through to `game.Main`:

```bash
./run.sh maps/school.json --size 1920x1080
./run.sh --shot a.png 16 21.6 -100 5
./run.sh --bench
```

## Command-line options

| Option | Description |
|---|---|
| `[map.json]` | Map to load (default `maps/school.json`) |
| `--size WxH` | Render resolution; the width is the number of rays per frame (default 1280x720) |
| `--window WxH` | Window size; the render is scaled to fit (default 1920x1080) |
| `--fps N` | Frame-time target for dynamic resolution; `0` disables it (default 60) |
| `--ss N` | Supersampling factor, 1-8 (default 1) |
| `--feet M` | Starting floor height in metres, e.g. to start on an upper storey |
| `--shade card\|cpu` | Where the frame is shaded. The card by default and three to four times faster; the CPU by default for `--verify`, `--shot` and `--shots`. `--gpu` and `--cpu` are the old spellings |
| `--hdr` | Shade in light rather than in sRGB numbers, and roll the highlights off filmically |
| `--unlit` | Skip the lightmap bake and use flat shading (`--flat` was its old name) |
| `--shear` | Use y-shearing for pitch instead of the perspective warp |
| `--bench` | Time the renderer over a full turn, level and pitched all the way up |
| `--shot out.png [x y heading pitch [column]]` | Render one frame headlessly |
| `--shots views.txt` | Render several frames in one run, one `out.png x y feet heading [pitch]` per line |
| `--verify views.txt` | Render each view and print a digest of it instead of writing files; see Tests |

`--shot` also writes `-plain.png` (no HUD), `-albedo.png` (unshaded surface colour),
`-depth.pfm` (distance per pixel) and `-rays.png` (the ray view).

JVM system properties can be set through `JAVA_OPTS`, for example
`JAVA_OPTS=-Dlight.texel=0.5 ./run.sh`:

| Property | Description |
|---|---|
| `light.texel` | Lightmap texel size in metres |
| `light.stats` | Print lightmap bake statistics |
| `light.cache`, `light.cache.dir` | `false` bakes without the lightmap cache; the folder it is kept in (`.lightcache`) |
| `bench.warmup`, `bench.frames` | Untimed and timed frames for `--bench` (400, 720) |
| `minimap.debug` | Highlight standable ground the minimap flood did not reach |
| `grade.sat`, `grade.lift` | Colour grading parameters |

## Tests

```bash
./test.sh
```

Unit tests for the parts that can be checked on their own - the JSON parser, the ray/segment and
ray/polygon intersections, the frame-time controller, map loading and the lightmap cache's key -
and then two checks on whole frames:

- **Determinism.** The same build renders the seven cameras in `tests/views/school.txt` on one
  thread and on every core, and the two must agree exactly. This is the property the renderer's
  distance tie-break and the grid's cell padding exist to give, and it holds on any machine.
- **Golden frames.** The same cameras, baked and `--flat`, against digests in `tests/golden/`.
  What is hashed is the engine's own pixel, depth, albedo and lightmap arrays rather than the PNGs
  written from them, so a change to an image encoder cannot turn the test red on its own.
  `./test.sh --haven` does the same against the `maps/haven` submodule, where the map is large
  enough to exercise the acceleration structures properly.

The rule the engine is built on is that an optimisation is proved not to change the output rather
than assumed not to; the golden files are where that proof lives. A deliberate change to the
picture is blessed with `./test.sh --bless` in the same commit that causes it.
`tests/golden/README.md` explains the one thing they cannot promise - the same digests on a
different CPU - and why CI checks them on macOS only.

## Controls

| Input | Action |
|---|---|
| `W` `A` `S` `D` | Move |
| Mouse drag, arrow keys | Look |
| `Q` / `E` | Turn |
| `Space` / `C` / `Shift` | Jump / crouch / run |
| `G` | Fly: gravity off, Space and crouch go up and down, nothing is solid |
| `M` | Toggle minimap |
| `R` | Toggle ray view |
| `N` | Ray view: keep the player pointing up, or let the world stay put instead |
| `L` | Toggle baked lighting |
| `F` | Toggle fisheye projection (for comparison) |
| `P` | Toggle pitch model (for comparison) |
| `[` / `]` | Field of view |
| `,` / `.` | Render resolution (disables dynamic resolution) |
| `V` | Toggle dynamic resolution |
| `Esc` | Quit |

Movement keys refer to physical key positions, so they stay in place on non-QWERTY layouts.

## Maps

Maps are JSON files; `maps/school.json` documents the format in comments at the top. In summary:

- **Regions** are convex polygons with a floor height and an optional ceiling height. Regions may
  overlap to form storeys; a region without a ceiling is open to the sky.
- **Shapes** are walls (segments), circles, boxes and convex polygons with bottom and top heights.
  Tops and bottoms can be sloped.
- **Arrays** repeat a group of shapes at a fixed step.
- **Chunks** let large maps store shapes in separate files.
- **Lighting**, **textures**, **images** and the spawn point are configured per map.

## Haven

A section of VALORANT's Haven converted for this engine. The map is a submodule at `maps/haven`,
kept in its own repository, [ColumnRay-Haven](https://github.com/Arstoienn/ColumnRay-Haven),
because it is about 290 MB:

```bash
git clone --recursive https://github.com/Arstoienn/ColumnRay.git   # or, in an existing clone:
git submodule update --init maps/haven
JAVA_OPTS=-Xmx12g ./run.sh maps/haven/haven.json --feet 3
```

**Mid Doors**

![Mid Doors](docs/images/mid-doors.jpg)

**Garage**

![Garage](docs/images/garage.jpg)

**C Long**

![C Long](docs/images/c-long.jpg)

**Flowerpot**

![Flowerpot](docs/images/flowerpot.jpg)

**Heaven and Hell**

![Heaven, left, and Hell, right](docs/images/heaven-hell.jpg)

![Ray views of Heaven, left, and Hell, right](docs/images/heaven-hell-rays.jpg)

Heaven, on the left, is the upper floor of A Tower; Hell, on the right, is the room directly below
it. Beneath each view is its ray view, the engine's top-down debug window (`R`), which draws every
column's ray over the plan of the map and lists, for the centre column, each shape the ray met and
how many rows it filled.

## Benchmarking and verification

- `./bench.sh [map] [size] [runs]` runs `--bench` in several fresh JVMs and reports the median.
  Setting `CP_B` or `JAVA_OPTS_B` runs two builds or configurations alternately and reports their
  ratio.
- Changes to the renderer or lighting are expected not to alter output unless intended: `--shot`
  images, albedo and depth files, and lightmap hashes should be byte-identical before and after.

## Project layout

| Path | Contents |
|---|---|
| `src/engine/Renderer.java` | Camera, projection, grid traversal, column filling, shading |
| `src/engine/Geometry.java` | Ray intersection with segments, circles and polygons |
| `src/engine/World.java` | Regions, shapes, JSON loading, acceleration grid |
| `src/engine/Materials.java` | Procedural and image textures, filtering |
| `src/engine/Lighting.java` | Lightmap bake |
| `src/engine/LightCache.java` | Baked lightmaps on disk, keyed by the map, settings and bake code |
| `src/engine/Occluder.java` | Line of sight and nearest-hit queries |
| `src/engine/Body.java` | What the map lets a body of a given size stand on, and what blocks it |
| `src/engine/Host.java` | Window, frame loop and buffers: the engine a game runs on |
| `src/engine/Game.java` | What a game implements: update, view, overlay |
| `src/engine/View.java` | Where to look from, in metres and radians |
| `src/engine/Input.java` | Keys and mouse, with no bindings in them |
| `src/engine/Options.java` | Command line |
| `src/engine/Warp.java` | The pitch warp |
| `src/engine/Capture.java` | Headless modes: `--bench`, `--shot`, `--shots`, `--verify` |
| `src/engine/DynamicResolution.java` | Frame-time-driven render scaling |
| `src/engine/Minimap.java` | Minimap |
| `src/engine/RayView.java` | Top-down ray debug view |
| `src/engine/Keys.java` | Physical key state |
| `src/engine/Hash.java` | Digests for the golden test |
| `src/engine/Json.java` | JSON parser (supports `//` comments) |
| `src/game/Main.java` | Where a run starts: command line, map, engine, then play or capture |
| `src/game/Sandbox.java` | The game: bindings, toggles, the camera it hands the engine |
| `src/game/Player.java` | Movement, gravity, crouch and jump |
| `src/game/Hud.java` | Overlay text and minimap drawing |
| `maps/school.json` | Demo map |
| `maps/haven/` | Submodule: the Haven map, in [ColumnRay-Haven](https://github.com/Arstoienn/ColumnRay-Haven) |
| `docs/images/` | README screenshots |
| `docs/ENGINEERING.md` | Design notes, measurements and the reasoning behind each subsystem |

## License

The source code is released under the MIT License; see [LICENSE](LICENSE). The license does not
cover the Haven screenshots; see the disclaimer.

## Disclaimer

The screenshots in `docs/images/` are rendered from a map derived from
["Valorant - Heaven Map"](https://open3dlab.com/project/4a0d5de0-05ac-4db3-a53b-6555879bc29d/) by
AC_NONE on Open3DLab (CC BY-NC-ND 4.0), which contains assets from VALORANT. The map itself is not
part of this repository; it is published separately in
[ColumnRay-Haven](https://github.com/Arstoienn/ColumnRay-Haven). VALORANT and Haven are trademarks
and copyrighted works of Riot Games, Inc. This project is not affiliated with or endorsed by Riot
Games. The screenshots are included for non-commercial demonstration only, remain the property of
their respective owners, and are not covered by this repository's MIT License.
