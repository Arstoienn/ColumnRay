package engine;

import engine.World.Shape;

/** 光線交點與碰撞用的 2D 幾何。光線是 p + r·t，r 不必是單位向量。 */
final class Geometry {
    private Geometry() {}

    static final double EPS = 1e-9;

    /** t：光線上的距離；u：線段上的位置 0..1；(ex, ey)：邊向量。 */
    record SegHit(double t, double u, double ex, double ey) {}

    /** 進入與離開的 t。 */
    record Span(double t1, double t2) {}

    /** 凸多邊形：進入 / 離開的 t，以及對應的邊和邊上位置。 */
    record PolyHit(double t1, double t2, int enterEdge, double enterU, int exitEdge, double exitU) {}

    static SegHit raySeg(double px, double py, double rx, double ry,
                         double ax, double ay, double bx, double by) {
        double ex = bx - ax, ey = by - ay;
        double den = rx * ey - ry * ex;
        if (Math.abs(den) < EPS) return null;                 // 平行
        double apx = ax - px, apy = ay - py;
        double t = (apx * ey - apy * ex) / den;               // 光線上的距離
        double u = (apx * ry - apy * rx) / den;               // 線段上的位置 0..1
        return (u >= 0 && u <= 1) ? new SegHit(t, u, ex, ey) : null;
    }

    static Span rayCircle(double px, double py, double rx, double ry, double cx, double cy, double R) {
        double pcx = px - cx, pcy = py - cy;
        double A = rx * rx + ry * ry;
        double B = 2 * (rx * pcx + ry * pcy);
        double C = pcx * pcx + pcy * pcy - R * R;
        double D = B * B - 4 * A * C;
        if (D <= 0) return null;
        double q = Math.sqrt(D);
        return new Span((-B - q) / (2 * A), (-B + q) / (2 * A));   // 進入、離開
    }

    /** 對每條邊跑 raySeg：最小的 t 是進入、最大的是離開。進入為負代表站在範圍內。 */
    static PolyHit rayPoly(double px, double py, double rx, double ry, double[] xs, double[] ys) {
        double t1 = Double.POSITIVE_INFINITY, t2 = Double.NEGATIVE_INFINITY, u1 = 0, u2 = 0;
        int e1 = -1, e2 = -1;
        for (int i = 0, n = xs.length; i < n; i++) {
            int j = (i + 1) % n;
            SegHit h = raySeg(px, py, rx, ry, xs[i], ys[i], xs[j], ys[j]);
            if (h == null) continue;
            if (h.t() < t1) { t1 = h.t(); e1 = i; u1 = h.u(); }
            if (h.t() > t2) { t2 = h.t(); e2 = i; u2 = h.u(); }
        }
        return t2 > t1 ? new PolyHit(t1, t2, e1, u1, e2, u2) : null;
    }

    static double distPointSeg(double x, double y, double ax, double ay, double bx, double by) {
        double ex = bx - ax, ey = by - ay;
        double len2 = ex * ex + ey * ey;
        double u = len2 == 0 ? 0 : ((x - ax) * ex + (y - ay) * ey) / len2;
        u = Math.max(0, Math.min(1, u));
        return Math.hypot(x - ax - ex * u, y - ay - ey * u);
    }

    /** 凸多邊形，順逆時針都可以。邊上的點算在裡面。 */
    static boolean pointInPoly(double x, double y, double[] xs, double[] ys) {
        int sign = 0;
        for (int i = 0, n = xs.length; i < n; i++) {
            int j = (i + 1) % n;
            double c = (xs[j] - xs[i]) * (y - ys[i]) - (ys[j] - ys[i]) * (x - xs[i]);
            if (c == 0) continue;
            int s = c > 0 ? 1 : -1;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return true;
    }

    static boolean isConvex(double[] xs, double[] ys) {
        int n = xs.length, sign = 0;
        if (n < 3) return false;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n, k = (i + 2) % n;
            double c = (xs[j] - xs[i]) * (ys[k] - ys[j]) - (ys[j] - ys[i]) * (xs[k] - xs[j]);
            if (Math.abs(c) < EPS) continue;
            int s = c > 0 ? 1 : -1;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return sign != 0;
    }

    static boolean discTouchesPoly(double x, double y, double rad, double[] xs, double[] ys) {
        if (pointInPoly(x, y, xs, ys)) return true;
        for (int i = 0, n = xs.length; i < n; i++) {
            int j = (i + 1) % n;
            if (distPointSeg(x, y, xs[i], ys[i], xs[j], ys[j]) < rad) return true;
        }
        return false;
    }

    /** 圓盤（玩家）和形狀的俯視投影是否重疊。 */
    static boolean overlaps(double x, double y, double rad, Shape s) {
        return switch (s.kind) {
            case SEG -> distPointSeg(x, y, s.ax, s.ay, s.bx, s.by) < rad;
            case CIRCLE -> Math.hypot(x - s.cx, y - s.cy) < s.r + rad;
            case POLY -> discTouchesPoly(x, y, rad, s.xs, s.ys);
        };
    }
}
