# A second opinion on the card's half

Codex CLI read the `gpu` branch on 2026-09-20 and answered five questions about it. It was told
what it is: a reviewer and not a source of truth, unable to run the GPU checks, which need a
macOS OpenGL context and, for Haven, a twenty-minute bake. Its answer is a source review only and
it said so itself. What follows is what it found, what was done about it, and - the part worth
keeping - the three places where it disagreed with what this branch had written down.

## Where it disagreed, and was right

**The overscan height does not set the horizon.** This branch had measured that an odd render
buffer makes the CPU's and the card's pictures disagree far more than an even one (school 11 of
255 at 720, 37 at 719 or 721; Haven 3 and 74), explained it by the horizon landing on a
half-integer row, and concluded that the pitch warp's overscan - an arbitrary integer - made it
reachable in the real game, so that rounding the overscan to an even number of rows would fix it
at the cost of new golden frames.

The parity explanation holds. The conclusion did not. `Warp.place` sets `c.pitch = hz - srcH/2.0`
and the renderer computes `srcH/2.0 + c.pitch`, so the buffer height cancels exactly: the horizon
is the warp's own `vHi + 2` whatever size the overscan grew to. Rounding it would have changed
nothing, and would have cost a re-blessing of the golden frames to do so. `GpuCheck` reaches the
bad case only because it puts the horizon at `h / 2` itself. The section in `ENGINEERING.md` now
says this, including that it is the fix that section first recommended.

Codex's own suggestion for what would help - snapping the scaled coordinate before `floor` and
`frac` with the same epsilon in Java and in GLSL - is a change to `Materials`, which is one of the
classes that invalidates every cached bake. It is not done, and it is not obviously worth a bake.

**The two pixels are probably not either suspect we named.** The branch had put the last 3-of-255
difference down to one of two boundary crossings: the colour dodge in `Materials.blend`, or the
sample count in the anisotropic strip. Codex named a third and better candidate: the mip chain
itself. `Materials.Level.half` keeps its area averages as floats and deliberately does not
quantize them - there is a comment saying so - while `GpuTextures` rounds every level to a byte
and uploads `RGB8`, and the hardware then interpolates with a fixed number of subtexel bits. A
blend material samples a base, a second layer and a height map and runs them through a nonlinear
mix, which is exactly where a fraction of a level becomes three. An `RGB32F` chain would settle
it and would cost four times Haven's 600 MB, so the diagnosis is written into `GpuTextures` and
not paid for. It also observed that "the colour dodge is discontinuous" is too loose: the only
real discontinuity in that function is `a == 0 && hp >= 1`, and a log of `a`, `hp`, `room` and
`dodge` at the two pixels would say whether they are anywhere near it.

**There was a redundant `glFinish`.** `GpuWalls.draw` called it unconditionally before
`glReadPixels`, which synchronises by itself; it was there to separate the draw and read timing
columns. Making it conditional on `-Dgpu.stats` took school's frame from 4.6 ms to 3.3 - a third -
and nothing else changed. That is the single largest speed-up of the day and it came out of a
reading, not a profile.

## What it found that was simply wrong, and is now fixed

**The resize guard.** `Main` recreated the card's resources only when `srcW` changed. The overscan
grows with pitch and there is no rule that the width has to grow with the height; a height that
moved on its own would have left `GpuWalls` drawing at the old size and `GpuSpans`' skip mask too
short for the new one, which is an out-of-bounds write rather than a wrong pixel. Both dimensions
are compared now.

**A GL leak.** `GpuWalls.close()` deleted its textures and framebuffer but not its program or its
vertex array, and `Main` closes and rebuilds a `GpuWalls` every time the overscan grows. Both are
deleted now, and `Gl` grew the two entry points to do it.

**The buffers were over-built.** The per-column lists were held at their worst case - 512 spans
and 1024 masks a column - in a Java array, again in off-heap staging, and again in a GL texture:
about three hundred megabytes for frames that use eight span slots a column. The lists now start
small and double when a column runs out, which is safe because running out was never wrong (the
rows go back to the CPU), and the staging and the textures are sized to a high-water mark. School
holds 41 spans and 1 mask a column; Haven's worst camera holds 363 and 658, and reaches them in a
few frames.

## Where it is not followed

**Its highest-value next work** is a `GpuCheck` that drives the real `Main` pipeline - the warp,
the buffer growth, pitch, odd and even dynamic resolutions - and asserts rather than prints. That
is a good idea and it is not done. The present check renders at a fixed size with the camera
level, so nothing it says covers the warp or the growth path, and the resize bug above is exactly
the kind of thing it would have caught. It is the first thing to pick up.

> **Done, and this paragraph outlived it.** `--gpu-verify` is that check: it drives `Host.frame`
> on both paths at five tilts a camera and exits non-zero on the picture, and since 2026-09-25 it
> also steps the render size one rung of the ladder per comparison, odd widths included. `GpuCheck`
> stayed what it was and is now the diagnostic rather than the gate - it is the one that names the
> surface behind the worst pixel. This note is here because the paragraph above was read as current
> on 2026-09-25 and sent somebody off to build what already existed; the rest of this document is a
> record of one day and is left as it was written.

**Its reading of the timing columns.** It objected to `GpuCheck` publishing a minimum of twelve
runs as a performance number. That is fair, and it is worth saying that no performance claim on
this branch comes from there: every number quoted in `ENGINEERING.md` is from `--bench`, which is
a warmed loop of hundreds of frames reporting a median and a p99. The check's columns are a smoke
test and should be read as one.

**Its suggestion to close the long-lived textures at exit** is not done, on the grounds that the
process is about to end and the driver frees them; it is right in principle and trivial to add if
the engine ever keeps a world alive across a map change.
