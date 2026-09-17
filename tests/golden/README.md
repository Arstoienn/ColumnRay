# Golden frames

`../views/school.txt` names seven cameras on `maps/school.json`. `./test.sh` renders each of them
and compares a digest of the result against the files here; `./test.sh --bless` rewrites them.

What is hashed is the engine's own output, not the files it writes: the `W * H` pixels of the
warped view (the same array `--shot` saves as `-plain.png`), the depth and albedo arrays that go
with it, and every texel of every lightmap in bake order. An image encoder may pick a different
PNG filter between JDK releases without a single pixel changing, and a golden test that goes red
for that reason is one people learn to ignore. The HUD is left out for the same reason: it draws
text, and the glyphs a machine happens to have are not the engine's output.

`school.txt` is the baked bake; `school-flat.txt` is `--flat`, which skips the bake entirely, so a
change to `Lighting` cannot hide behind a change to the renderer or the other way round.

## When these change

They are not supposed to. The rule this project runs on is that an optimisation is *proved* not to
change the output rather than assumed not to - that is how an unpadded point-in-node test and a
`bottomAt` rounding error were caught, both of which moved 480 rays and not one pixel. So a diff
here is either a change you meant, in which case bless it in the same commit that causes it and say
so in the message, or a bug.

## Platform

Taken on macOS 15 / Apple silicon, JDK 26. `Math.sin` and the other transcendental methods are
specified to within one unit in the last place rather than exactly, and HotSpot's intrinsics for
them differ between CPU architectures, so the digests here are not promised to reproduce on a
different machine. What *is* promised on every machine is `./test.sh --determinism`: one thread and
all of them must agree, which is the property the grid's tie-break and cell padding exist to give.
That is why CI runs the determinism check everywhere and the golden check only on macOS.
