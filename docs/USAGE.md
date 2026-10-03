# Using ColumnRay

How to build and run the engine, every command-line option, and the controls. Back to the [README](../README.md).

## Requirements

- JDK 22 or newer. The buffers handed to the card are allocated through the foreign function and
  memory API, which is a preview feature before 22
- LWJGL 3.4.3, fetched by `build.sh` through `lib/fetch.sh` against checksums tracked in that
  script. The jars are not committed, and a jar that does not hash as the script says it should
  is deleted rather than used, so a clone needs nothing but a JDK
- An OpenGL 3.3 core context, which GLFW asks for on every platform. The window is drawn through
  it, so the window does not open without one. Shading on the card is the default; the rays are
  walked on the CPU either way. The runs that open no window - `--shot`, `--shots`, `--verify`,
  `--bench` - need no card: where the machine cannot give a context (a software renderer, a
  virtual machine, a CI runner) they print a sentence and the CPU renderer does the whole frame.
  With a window open, a map the card cannot hold is shaded on the CPU the same way.
  `--shade cpu` asks for the CPU path on purpose. On a machine with two graphics cards, which one draws is
  Windows' per-application preference for `java.exe` (Settings > System > Display > Graphics),
  and the card it ended up on is named on the first line of the run

## Building and running

```bash
./run.sh
```

`run.sh` fetches LWJGL if it is missing, compiles `src/` into `out/` when a source file has
changed (see `build.sh` and `lib/fetch.sh`), and starts the game with `maps/school.json`. Arguments are passed through to `game.Main`:

```bash
./run.sh maps/school.json --size 1920x1080
./run.sh --play                            # no overlay, no toggles: just the world
./run.sh --shot a.png 16 21.6 -100 5
./run.sh --bench
```

`./run.sh` opens the sandbox: every switch the renderer has on a key, four lines of text saying
what it is doing, a minimap, and a fly mode for looking at a map from outside it. `--play` is the
same walking with none of that - no overlay, no minimap, no toggles, no flying, and the mouse
looks about without a button held. It is what to open when the point is the world rather than the
engine.

## Command-line options

| Option | Description |
|---|---|
| `[map.json]` | Map to load (default `maps/school.json`) |
| `--size WxH` | Render resolution; the width is the number of rays per frame (default 1280x720) |
| `--window WxH` | Window size; the render is scaled to fit (default 1920x1080) |
| `--fps N` | Frame-time target for dynamic resolution; `0` disables it (default 60) |
| `--ss N` | Supersampling factor, 1-8 (default 1) |
| `--feet M` | Starting floor height in metres, e.g. to start on an upper storey |
| `--fov N` | Field of view in degrees, 30-120 (default 90). Applies to the window, `--shot`, `--shots` and `--bench` alike |
| `--play` | The game with nothing on the screen but the game: no overlay, no minimap, no toggles, no flying |
| `--shade card\|cpu` | Where the frame is shaded. The card by default and several times faster, more so tilted; the CPU by default for `--verify`, `--shot` and `--shots`. `--gpu` and `--cpu` are the old spellings |
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
| `light.cache`, `light.cache.dir`, `light.cache.max` | `false` bakes without the lightmap cache; the folder it is kept in (`.lightcache`); how many megabytes of bakes that folder may hold before the least recently used are dropped (2048, `0` for no limit) |
| `bench.warmup`, `bench.frames` | Untimed and timed frames for `--bench` (400, 720) |
| `minimap.debug` | Highlight standable ground the minimap flood did not reach |
| `grade.sat`, `grade.lift` | Colour grading parameters |

## Controls

Both the sandbox and `--play`:

| Input | Action |
|---|---|
| `W` `A` `S` `D` | Move |
| Mouse | Look. The pointer is hidden and captured, the way a first-person game has worked for thirty years. `Tab` hands it back in the sandbox, which needs one for hovering a column |
| Arrow keys, `Q` / `E` | Look, turn |
| `Space` / `C` / `Shift` | Jump / crouch / run |
| `Esc` | Quit |

The sandbox only, because they are switches on the engine rather than on a person:

| Input | Action |
|---|---|
| `Tab` | Hand the pointer back, so it can hover a column for the ray view |
| `G` | Fly: gravity off, Space and crouch go up and down, nothing is solid |
| `M` | Toggle minimap |
| `R` | Toggle ray view |
| `N` | Ray view: keep the player pointing up, or let the world stay put instead |
| `L` | Toggle baked lighting |
| `H` | Toggle shading in light and the filmic curve |
| `F` | Toggle fisheye projection (for comparison) |
| `P` | Toggle pitch model (for comparison) |
| `[` / `]` | Field of view |
| `,` / `.` | Render resolution (disables dynamic resolution) |
| `V` | Toggle dynamic resolution |

Movement keys refer to physical key positions, so they stay in place on non-QWERTY layouts.

The walk has a velocity in it rather than a speed: it accelerates to pace in about 70 ms and stops
about as quickly, a jump keeps the momentum it left the ground with, and a jump asked for just
after walking off an edge or just before landing is still a jump. The camera bobs a couple of
centimetres with the stride and dips when a fall lands, which is how a first-person view reports
speed at all. None of it moves the player differently from where the physics says they are, and
none of it reaches a screenshot: `Player.cameraMotion` is the switch, and a placed camera is level.
