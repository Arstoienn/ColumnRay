package engine;

import engine.World.Region;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;

/**
 * The overlay: four lines of text saying what the engine is doing, and the minimap.
 *
 * It is a debugging instrument rather than a game's interface - what resolution is being rendered,
 * whether the lighting is baked, which pitch model is on - and it is the part of the picture that
 * is deliberately not the engine's output: it draws text, and the glyphs a machine happens to have
 * are not something a golden test can compare. That is why --shot writes the frame without it as
 * well, and why --verify does not draw it at all.
 *
 * What it cannot read from the world or the player arrives as a {@link Status}, so nothing here
 * reaches back into the window loop for a field.
 */
final class Hud {
    /** The frame's own state: what the HUD reports that is neither the map's nor the player's. */
    record Status(double fps, int w, int h, int ss, int rays, boolean fisheye, String lighting,
                  boolean shear, boolean autoRes) {}

    private final World world;
    private final Renderer renderer;
    private final Player player;
    private Minimap minimap;                                     // built on the first frame that shows it

    Hud(World world, Renderer renderer, Player player) {
        this.world = world;
        this.renderer = renderer;
        this.player = player;
    }

    /** A key hint, spelled the way this player's keyboard labels the keys in those positions. */
    private static String key(int... positions) { return Keys.labels(positions); }

    void draw(Graphics2D g, int left, int top, Status st) {
        Region r = player.here();
        String[] lines = {
            String.format("%s   %.0f fps   %dx%d%s   %,d rays   FOV %.0f deg   %s   %s", world.name, st.fps(), st.w(), st.h(),
                    st.ss() > 1 ? " x" + st.ss() + " AA" : "", st.rays(), renderer.fov(),
                    st.fisheye() ? "fisheye demo (straight-line distance, wrong)" : "perpendicular distance",
                    st.lighting()),
            String.format("%s   (%.1f, %.1f)   feet %.2f m   pitch %+.0f deg %s", r == null ? "-" : r.name,
                    player.x, player.y, player.feet,
                    Math.toDegrees(player.pitch), st.shear() ? "y-shearing (old)" : "true perspective"),
            // The controls go by where a key sits, so name each one the way this keyboard labels it.
            key(Keys.W, Keys.A, Keys.S, Keys.D) + " move   drag mouse / arrows look   Space jump   "
                    + key(Keys.C) + " crouch   Shift run   " + key(Keys.G) + " fly",
            key(Keys.LEFT_BRACKET) + " " + key(Keys.RIGHT_BRACKET) + " FOV   " + key(Keys.COMMA) + " "
                    + key(Keys.PERIOD) + " rays   " + key(Keys.V) + (st.autoRes() ? " auto res on   " : " auto res off   ")
                    + key(Keys.F) + " fisheye   " + key(Keys.P) + " pitch   "
                    + key(Keys.L) + " lighting   " + key(Keys.R) + " ray view   " + key(Keys.M)
                    + " minimap   Esc quit   hover to pick a column",
        };
        g.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        for (int i = 0; i < lines.length; i++) {
            g.setColor(new Color(0, 0, 0, 160));
            g.drawString(lines[i], left + 1, top + i * 18 + 1);
            g.setColor(new Color(235, 235, 235));
            g.drawString(lines[i], left, top + i * 18);
        }
    }

    /** The minimap (see Minimap): one prebuilt image, plus the player on top of it. */
    void drawMinimap(Graphics2D g, int right, int top, int size) {
        if (minimap == null) minimap = new Minimap(world, player.x, player.y, player.feet);
        double mw = minimap.image.getWidth() * Minimap.CELL, mh = minimap.image.getHeight() * Minimap.CELL;
        // The map may turn its minimap by quarter turns, the way Valorant shows each map the same
        // way round every time; a quarter turn swaps which side of the panel is the long one.
        int quarter = Math.floorMod((int) Math.round(world.minimapRotate / 90), 4);
        double pw = quarter % 2 == 0 ? mw : mh, ph = quarter % 2 == 0 ? mh : mw;
        double s = Math.min(size / pw, size / ph);
        double ox = right - pw * s, oy = top;
        AffineTransform saved = g.getTransform();
        g.setColor(new Color(255, 255, 255, 200));
        g.fill(new Rectangle2D.Double(ox - 6, oy - 6, pw * s + 12, ph * s + 12));
        g.translate(ox + pw * s / 2, oy + ph * s / 2);
        g.rotate(quarter * Math.PI / 2);                              // clockwise on screen
        g.scale(s, s);
        g.translate(-minimap.ix0 - mw / 2, -minimap.iy0 - mh / 2);
        g.setStroke(new BasicStroke((float) (1.5 / s)));

        Object smoothing = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform px = AffineTransform.getTranslateInstance(minimap.ix0, minimap.iy0);
        px.scale(Minimap.CELL, Minimap.CELL);
        g.drawImage(minimap.image, px, null);
        if (smoothing != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smoothing);

        double x = player.x, y = player.y;
        double dx = Math.cos(player.angle), dy = Math.sin(player.angle), pl = renderer.planeHalfWidth(), len = 4;
        g.setColor(new Color(235, 150, 20, 220));
        g.draw(new Line2D.Double(x, y, x + (dx + dy * pl) * len, y + (dy - dx * pl) * len));
        g.draw(new Line2D.Double(x, y, x + (dx - dy * pl) * len, y + (dy + dx * pl) * len));
        g.setColor(new Color(255, 80, 60));
        g.fill(new Ellipse2D.Double(x - Player.RADIUS, y - Player.RADIUS, Player.RADIUS * 2, Player.RADIUS * 2));
        g.setTransform(saved);
    }
}
