# ColumnRay

A raycasting engine in plain Java, the Wolfenstein kind: one ray for each column of the screen, and
each column drawn as a vertical strip whose height comes from how far the ray went. The point of the
project is to see how close that can get to a modern game without ever giving it up. The pictures
under Maps are what it gets to so far.

The world is a 2D plan of regions and shapes, each with a floor and a ceiling height. That is enough
for stacked storeys, sloped roofs and textured meshes converted from 3D models, and the renderer
underneath is still the same column renderer. The one dependency is LWJGL, for the window and
OpenGL. Everything that decides what a frame looks like is in this repository.

## What it does

You can look up and down. Pitch goes to 50 degrees, and it is a true tilt: the engine renders the
level view and then applies an exact projective warp to it, so the columns stay columns. It only
renders the part of the level view the warp actually reads, which is what makes a steep tilt
affordable.

Light is baked. Sun, sky, point lights and ceiling panels, with soft shadows and two bounces, cached
on disk until the map or the bake changes. Lamps can flicker or die. Each group of them is baked on
its own and added back every frame at whatever level it is at, so a lamp that drops out takes its
share of the bounced light with it, and all that costs per frame is one number.

Water and glass reflect without breaking the one-ray-per-column rule. A puddle reflects by walking
back up its own column, because the reflected ray stays in that column's vertical plane. Glass
reflects by running the column renderer a second time from the eye mirrored in the pane. School's
courtyard has both.

The rest:

- a DDA walk over a uniform grid, with a bounding volume hierarchy in each cell
- procedural and image textures, mipmapped and anisotropically filtered
- dynamic resolution driven by frame time, and supersampling
- a minimap, and a top-down view of every ray, for debugging
- keys read by where they sit, so WASD stays put on Dvorak or Colemak

## Requirements

A JDK, 22 or newer. Nothing else: the build fetches LWJGL and checks it. Shading runs on the graphics
card through OpenGL 3.3, and where there is no card the CPU renderer takes over. More in
[Using ColumnRay](docs/USAGE.md#requirements).

## Quick start

```bash
git clone --recursive https://github.com/Arstoienn/ColumnRay.git
cd ColumnRay
./run.sh                                               # the school, in the sandbox
./run.sh maps/backrooms/backrooms.json --hdr --play    # the Backrooms, nothing on screen but the world
```

The first run fetches LWJGL and compiles. Options and controls are in [Using ColumnRay](docs/USAGE.md).

## Maps

<p>
  <img src="docs/images/street-corner.jpg" width="49%" alt="A night street after rain">
  <img src="docs/images/backrooms-room.jpg" width="49%" alt="An empty room in the Backrooms">
</p>

A night street after rain, and the Backrooms. `maps/school.json` is in this repository and the
Backrooms is the submodule `maps/backrooms`. [Maps](docs/MAPS.md) has more pictures, where each map
came from, and the map format.

## Documentation

| | |
|---|---|
| [Using ColumnRay](docs/USAGE.md) | Building, running, options and controls |
| [Maps](docs/MAPS.md) | The converted maps, pictures of each, and the map format |
| [Engineering](docs/ENGINEERING.md) | Writing a game on the engine, the tests, the code, and why it works the way it does |
| [License and copyright](docs/COPYRIGHT.md) | What the MIT licence covers, and where each map comes from |

## License

The engine and `maps/school.json` are MIT. The Backrooms map is CC BY 4.0, and the pictures of the
other maps belong to their owners. See [LICENSE](LICENSE) and
[License and copyright](docs/COPYRIGHT.md).
