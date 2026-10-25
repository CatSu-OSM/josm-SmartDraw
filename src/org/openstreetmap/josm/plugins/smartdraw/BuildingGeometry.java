package org.openstreetmap.josm.plugins.smartdraw;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/** Straightens a traced roof polygon and estimates short hidden corners. */
public final class BuildingGeometry {
    private BuildingGeometry() { }

    public static List<Point2D> regularize(List<Point2D> input) {
        if (input.size() < 4) return new ArrayList<>(input);
        List<Point2D> result = removeCollinear(input);
        result = coarseCorners(result);
        List<Point2D> fitted = fitWallLines(result);
        if (!fitted.isEmpty()) return fitted;
        for (int pass = 0; pass < 4; pass++) {
            List<Point2D> bridged = bridgeOneCorner(result);
            if (bridged == null) break;
            result = removeCollinear(bridged);
        }
        for (int i = 0; i < result.size(); i++) {
            Point2D a = result.get((i - 1 + result.size()) % result.size());
            Point2D b = result.get(i), c = result.get((i + 1) % result.size());
            double ax = b.getX() - a.getX(), ay = b.getY() - a.getY();
            double bx = c.getX() - b.getX(), by = c.getY() - b.getY();
            double lengths = Math.hypot(ax, ay) * Math.hypot(bx, by);
            if (lengths < 100 || Math.abs((ax * bx + ay * by) / lengths) > 0.20) continue;
            double fraction = ((c.getX() - a.getX()) * ax + (c.getY() - a.getY()) * ay)
                    / (ax * ax + ay * ay);
            Point2D square = new Point2D.Double(a.getX() + fraction * ax, a.getY() + fraction * ay);
            if (square.distance(b) > 5) continue;
            List<Point2D> proposed = new ArrayList<>(result);
            proposed.set(i, square);
            if (!Geometry.selfIntersects(proposed, true)) result = proposed;
        }
        return removeCollinear(result);
    }

    private static List<Point2D> coarseCorners(List<Point2D> input) {
        if (input.size() <= 10) return input;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Point2D point : input) {
            minX = Math.min(minX, point.getX()); maxX = Math.max(maxX, point.getX());
            minY = Math.min(minY, point.getY()); maxY = Math.max(maxY, point.getY());
        }
        double maximum = Math.min(35, Math.max(4, Math.min(maxX - minX, maxY - minY) * 0.12));
        List<Point2D> ring = new ArrayList<>(input);
        ring.add(input.get(0));
        List<Point2D> best = input;
        for (double tolerance = 4; tolerance <= maximum; tolerance += 2) {
            List<Point2D> simplified = Geometry.simplify(ring, tolerance);
            if (simplified.size() > 1) simplified.remove(simplified.size() - 1);
            if (simplified.size() < 4 || Geometry.selfIntersects(simplified, true)) continue;
            double ratio = area(simplified) / area(input);
            if (ratio < 0.85 || ratio > 1.15) continue;
            best = simplified;
            if (best.size() <= 14) break;
        }
        return best;
    }

    /** Finds the two dominant wall directions, then intersects fitted edge runs at corners. */
    private static List<Point2D> fitWallLines(List<Point2D> points) {
        int count = points.size();
        double cos4 = 0, sin4 = 0, total = 0;
        for (int i = 0; i < count; i++) {
            Point2D a = points.get(i), b = points.get((i + 1) % count);
            double length = a.distance(b);
            if (length < 6) continue;
            double angle = Math.atan2(b.getY() - a.getY(), b.getX() - a.getX());
            cos4 += length * Math.cos(4 * angle);
            sin4 += length * Math.sin(4 * angle);
            total += length;
        }
        if (total == 0 || Math.hypot(cos4, sin4) / total < 0.55) return List.of();
        for (int i = 0; i < count; i++) {
            Point2D a = points.get((i + count - 1) % count), b = points.get(i);
            Point2D c = points.get((i + 1) % count);
            double ax = b.getX() - a.getX(), ay = b.getY() - a.getY();
            double bx = c.getX() - b.getX(), by = c.getY() - b.getY();
            double lengths = Math.hypot(ax, ay) * Math.hypot(bx, by);
            if (Math.min(Math.hypot(ax, ay), Math.hypot(bx, by)) < total * 0.10) continue;
            double cosine = Math.abs((ax * bx + ay * by) / lengths);
            if (cosine > 0.30 && cosine < 0.92) return List.of();
        }
        double angle = Math.atan2(sin4, cos4) / 4;
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double[] u = new double[count], v = new double[count];
        boolean[] horizontal = new boolean[count];
        double supported = 0, perimeter = 0;
        for (int i = 0; i < count; i++) {
            Point2D point = points.get(i);
            u[i] = point.getX() * cos + point.getY() * sin;
            v[i] = -point.getX() * sin + point.getY() * cos;
        }
        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            double du = u[next] - u[i], dv = v[next] - v[i];
            double length = Math.hypot(du, dv);
            horizontal[i] = Math.abs(du) >= Math.abs(dv);
            perimeter += length;
            if (Math.min(Math.abs(du), Math.abs(dv)) <= 0.31 * length) supported += length;
        }
        if (supported < perimeter * 0.70) return List.of();
        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            double du = u[next] - u[i], dv = v[next] - v[i];
            double length = Math.hypot(du, dv);
            if (length > perimeter * 0.15 && Math.min(Math.abs(du), Math.abs(dv)) > length * 0.26)
                return List.of();
        }
        int start = -1;
        for (int i = 0; i < count; i++) {
            if (horizontal[i] != horizontal[(i + count - 1) % count]) { start = i; break; }
        }
        if (start < 0) return List.of();
        List<WallRun> runs = new ArrayList<>();
        WallRun current = null;
        for (int step = 0; step < count; step++) {
            int i = (start + step) % count, next = (i + 1) % count;
            if (current == null || current.horizontal != horizontal[i]) {
                current = new WallRun(horizontal[i]);
                runs.add(current);
            }
            double length = Math.hypot(u[next] - u[i], v[next] - v[i]);
            current.position += length * (horizontal[i] ? (v[i] + v[next]) : (u[i] + u[next])) / 2;
            current.length += length;
        }
        if (runs.size() < 4 || runs.size() > 24 || (runs.size() & 1) != 0) return List.of();
        for (WallRun run : runs) run.position /= run.length;
        List<Point2D> fitted = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            WallRun previous = runs.get((i + runs.size() - 1) % runs.size()), next = runs.get(i);
            double cornerU = previous.horizontal ? next.position : previous.position;
            double cornerV = previous.horizontal ? previous.position : next.position;
            fitted.add(new Point2D.Double(cornerU * cos - cornerV * sin,
                    cornerU * sin + cornerV * cos));
        }
        if (Geometry.selfIntersects(fitted, true)) return List.of();
        double originalArea = area(points), fittedArea = area(fitted);
        if (fittedArea < originalArea * 0.65 || fittedArea > originalArea * 1.35) return List.of();
        return removeSmallDetours(fitted, originalArea, cos, sin);
    }

    private static List<Point2D> removeSmallDetours(List<Point2D> input, double area,
            double cos, double sin) {
        List<Point2D> result = new ArrayList<>(input);
        double depthLimit = Math.min(25, Math.max(5, Math.sqrt(area) * 0.08));
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < result.size() && result.size() > 4; i++) {
                int size = result.size();
                Point2D a = result.get((i + size - 1) % size), b = result.get(i);
                Point2D c = result.get((i + 1) % size), d = result.get((i + 2) % size);
                double dx = d.getX() - a.getX(), dy = d.getY() - a.getY();
                double du = dx * cos + dy * sin, dv = -dx * sin + dy * cos;
                if (Math.min(Math.abs(du), Math.abs(dv)) > Math.hypot(du, dv) * 0.1) continue;
                if (Math.max(Geometry.segmentDistance(b, a, d), Geometry.segmentDistance(c, a, d))
                        > depthLimit) continue;
                List<Point2D> proposed = new ArrayList<>();
                for (int j = 0; j < size; j++)
                    if (j != i && j != (i + 1) % size) proposed.add(result.get(j));
                if (Geometry.selfIntersects(proposed, true)
                        || Math.abs(area(result) - area(proposed)) > area * 0.015) continue;
                result = proposed;
                changed = true;
                break;
            }
        } while (changed);
        return result;
    }

    private static double area(List<Point2D> points) {
        double twice = 0;
        for (int i = 0; i < points.size(); i++) {
            Point2D a = points.get(i), b = points.get((i + 1) % points.size());
            twice += a.getX() * b.getY() - b.getX() * a.getY();
        }
        return Math.abs(twice) / 2;
    }

    private static final class WallRun {
        final boolean horizontal;
        double position, length;
        WallRun(boolean horizontal) { this.horizontal = horizontal; }
    }

    private static List<Point2D> bridgeOneCorner(List<Point2D> points) {
        int count = points.size();
        if (count < 5) return null;
        for (int i = 0; i < count; i++) {
            int bIndex = (i + 1) % count, cIndex = (i + 2) % count;
            Point2D a = points.get(i), b = points.get(bIndex);
            Point2D c = points.get(cIndex), d = points.get((i + 3) % count);
            double firstX = b.getX() - a.getX(), firstY = b.getY() - a.getY();
            double secondX = d.getX() - c.getX(), secondY = d.getY() - c.getY();
            double firstLength = Math.hypot(firstX, firstY), secondLength = Math.hypot(secondX, secondY);
            double gap = b.distance(c);
            if (firstLength < 12 || secondLength < 12 || gap < 3 || gap > 55) continue;
            double cross = firstX * secondY - firstY * secondX;
            double sine = Math.abs(cross) / (firstLength * secondLength);
            if (sine < 0.5) continue;
            double offsetX = c.getX() - a.getX(), offsetY = c.getY() - a.getY();
            double firstFraction = (offsetX * secondY - offsetY * secondX) / cross;
            double secondFraction = (offsetX * firstY - offsetY * firstX) / cross;
            if (firstFraction < 0.9 || secondFraction > 0.1) continue;
            Point2D corner = new Point2D.Double(a.getX() + firstFraction * firstX,
                    a.getY() + firstFraction * firstY);
            if (corner.distance(b) > Math.max(8, gap * 1.4)
                    || corner.distance(c) > Math.max(8, gap * 1.4)) continue;
            List<Point2D> proposed = new ArrayList<>();
            for (int j = 0; j < count; j++) {
                if (j == bIndex) proposed.add(corner);
                else if (j != cIndex) proposed.add(points.get(j));
            }
            if (proposed.size() >= 4 && !Geometry.selfIntersects(proposed, true)) return proposed;
        }
        return null;
    }

    private static List<Point2D> removeCollinear(List<Point2D> input) {
        List<Point2D> result = new ArrayList<>(input);
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < result.size() && result.size() > 4; i++) {
                Point2D a = result.get((i - 1 + result.size()) % result.size());
                Point2D b = result.get(i), c = result.get((i + 1) % result.size());
                if (Geometry.segmentDistance(b, a, c) > 2) continue;
                List<Point2D> proposed = new ArrayList<>(result);
                proposed.remove(i);
                if (!Geometry.selfIntersects(proposed, true)) {
                    result = proposed;
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return result;
    }
}
