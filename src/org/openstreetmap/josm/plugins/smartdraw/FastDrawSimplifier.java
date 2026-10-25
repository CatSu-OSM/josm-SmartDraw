package org.openstreetmap.josm.plugins.smartdraw;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * FastDraw-compatible screen-pixel Douglas-Peucker simplification. Fixed points
 * split the line into independent fragments and always survive simplification.
 * Adapted from JOSM FastDraw's GPL DrawnPolyLine implementation; see DESIGN.md
 * for source and attribution.
 */
public final class FastDrawSimplifier {
    private FastDrawSimplifier() { }

    public static List<Integer> keptIndices(List<Point> points, List<Boolean> fixed, double epsilon) {
        if (points.size() != fixed.size()) throw new IllegalArgumentException("Point and fixed counts differ");
        int count = points.size();
        List<Integer> kept = new ArrayList<>();
        if (count < 3) {
            for (int i = 0; i < count; i++) kept.add(i);
            return kept;
        }
        boolean[] used = new boolean[count];
        int start = 0;
        for (int i = 0; i < count; i++) {
            if (fixed.get(i) || i == count - 1) {
                simplifyPart(points, start, i, epsilon, used);
                start = i;
            }
        }
        for (int i = 0; i < count; i++) if (used[i]) kept.add(i);
        return kept;
    }

    private static void simplifyPart(List<Point> points, int first, int last,
            double epsilon, boolean[] used) {
        used[first] = true;
        used[last] = true;
        if (last - first < 2) return;
        double largest = 0;
        int candidate = -1;
        for (int i = first + 1; i < last; i++) {
            double distance = pointLineDistance(points.get(i), points.get(first), points.get(last));
            if (distance > largest) { largest = distance; candidate = i; }
        }
        if (largest > epsilon) {
            simplifyPart(points, first, candidate, epsilon, used);
            simplifyPart(points, candidate, last, epsilon, used);
        }
    }

    private static double pointLineDistance(Point p, Point a, Point b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double length = Math.hypot(dx, dy);
        if (length == 0) return p.distance(a);
        return Math.abs(dx * (a.y - p.y) - (a.x - p.x) * dy) / length;
    }
}
