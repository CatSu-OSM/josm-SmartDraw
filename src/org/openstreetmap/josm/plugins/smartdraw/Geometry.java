package org.openstreetmap.josm.plugins.smartdraw;

import java.awt.geom.Point2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;

/** Small screen-space geometry helpers used only for newly traced points. */
public final class Geometry {
    private Geometry() { }

    public static double segmentDistance(Point2D p, Point2D a, Point2D b) {
        double dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1,
                ((p.getX() - a.getX()) * dx + (p.getY() - a.getY()) * dy) / len2));
        return p.distance(a.getX() + t * dx, a.getY() + t * dy);
    }

    public static List<Point2D> simplify(List<Point2D> input, double epsilon) {
        if (input.size() <= 2 || epsilon <= 0) return new ArrayList<>(input);
        boolean[] keep = new boolean[input.size()];
        keep[0] = keep[input.size() - 1] = true;
        simplifyPart(input, 0, input.size() - 1, epsilon, keep);
        List<Point2D> output = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) if (keep[i]) output.add(input.get(i));
        return output;
    }

    /** Smooths pixel noise while retaining the exact endpoints of a traced span. */
    public static List<Point2D> smooth(List<Point2D> input, int radius) {
        if (input.size() < 3 || radius < 1) return new ArrayList<>(input);
        List<Point2D> output = new ArrayList<>(input.size());
        output.add(input.get(0));
        for (int i = 1; i < input.size() - 1; i++) {
            double x = 0, y = 0, weightSum = 0;
            for (int j = Math.max(0, i - radius); j <= Math.min(input.size() - 1, i + radius); j++) {
                double weight = radius + 1 - Math.abs(i - j);
                x += input.get(j).getX() * weight;
                y += input.get(j).getY() * weight;
                weightSum += weight;
            }
            output.add(new Point2D.Double(x / weightSum, y / weightSum));
        }
        output.add(input.get(input.size() - 1));
        return output;
    }

    /** Rejects a new segment that turns back or meets an earlier part of the draft. */
    public static boolean canExtendWithoutCrossing(List<Point2D> existing, Point2D candidate, double clearance) {
        if (existing.isEmpty()) return true;
        Point2D last = existing.get(existing.size() - 1);
        if (last.distance(candidate) < 0.01) return false;
        if (existing.size() < 2) return true;
        Point2D previous = existing.get(existing.size() - 2);
        double ax = last.getX() - previous.getX(), ay = last.getY() - previous.getY();
        double bx = candidate.getX() - last.getX(), by = candidate.getY() - last.getY();
        double lengths = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (lengths > 0 && (ax * bx + ay * by) / lengths < -0.65) return false;
        for (int i = 0; i < existing.size() - 2; i++) {
            Point2D a = existing.get(i), b = existing.get(i + 1);
            if (Line2D.linesIntersect(a.getX(), a.getY(), b.getX(), b.getY(),
                    last.getX(), last.getY(), candidate.getX(), candidate.getY())) return false;
            if (i < existing.size() - 3 && clearance > 0
                    && (segmentDistance(candidate, a, b) < clearance
                    || segmentDistance(a, last, candidate) < clearance
                    || segmentDistance(b, last, candidate) < clearance)) return false;
        }
        return true;
    }

    private static void simplifyPart(List<Point2D> points, int first, int last, double epsilon, boolean[] keep) {
        if (last - first < 2) return;
        double max = -1;
        int index = -1;
        for (int i = first + 1; i < last; i++) {
            double distance = segmentDistance(points.get(i), points.get(first), points.get(last));
            if (distance > max) { max = distance; index = i; }
        }
        if (max > epsilon) {
            keep[index] = true;
            simplifyPart(points, first, index, epsilon, keep);
            simplifyPart(points, index, last, epsilon, keep);
        }
    }

    public static boolean selfIntersects(List<Point2D> points, boolean closed) {
        int segments = closed ? points.size() : points.size() - 1;
        for (int i = 0; i < segments; i++) {
            Point2D a = points.get(i), b = points.get((i + 1) % points.size());
            for (int j = i + 2; j < segments; j++) {
                if (closed && i == 0 && j == segments - 1) continue;
                Point2D c = points.get(j), d = points.get((j + 1) % points.size());
                if (Line2D.linesIntersect(a.getX(), a.getY(), b.getX(), b.getY(),
                        c.getX(), c.getY(), d.getX(), d.getY())) return true;
            }
        }
        return false;
    }
}
