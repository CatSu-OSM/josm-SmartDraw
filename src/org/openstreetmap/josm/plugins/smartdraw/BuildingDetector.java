package org.openstreetmap.josm.plugins.smartdraw;

import java.awt.Point;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Chooses between a roof polygon and a fitted rectangle. */
public final class BuildingDetector {
    private BuildingDetector() { }

    public static List<Point2D> detect(BufferedImage image, Point click) {
        if (image == null || click == null || click.x < 1 || click.y < 1
                || click.x >= image.getWidth() - 1 || click.y >= image.getHeight() - 1) return List.of();
        if (((image.getRGB(click.x, click.y) >>> 24) & 255) < 32) return List.of();
        List<Point2D> color = detectByColor(image, click);
        List<Point2D> edges = detectByEdges(image, click);
        List<Point2D> roof = RoofSegmenter.detect(image, click);
        if (color.isEmpty() && edges.isEmpty()) roof = RoofSegmenter.expand(image, click, roof);
        if (color.isEmpty() && edges.isEmpty()) return roof;
        List<Point2D> rectangle = color;
        if (color.isEmpty()) rectangle = edges;
        if (!edges.isEmpty()) {
            if (!color.isEmpty()) {
                double colorArea = polygonArea(color), edgeArea = polygonArea(edges);
                Point2D a = color.get(0), b = color.get(1);
                double angle = Math.atan2(b.getY() - a.getY(), b.getX() - a.getX());
                boolean nearlyAxisAligned = Math.abs(Math.sin(2 * angle)) < 0.4;
                if (nearlyAxisAligned && edgeArea > colorArea * 0.65 && edgeArea < colorArea * 1.5)
                    rectangle = edges;
            }
        }
        if (!roof.isEmpty() && (rectangle.isEmpty() || usefulRoofPolygon(roof, rectangle))) return roof;
        return rectangle;
    }

    private static boolean usefulRoofPolygon(List<Point2D> roof, List<Point2D> rectangle) {
        if (roof.size() < 6) return false;
        double ratio = polygonArea(roof) / polygonArea(rectangle);
        if (ratio < 0.40 || ratio > 1.8) return false;
        if (ratio < 0.82) return true;
        Path2D fitted = new Path2D.Double();
        fitted.moveTo(rectangle.get(0).getX(), rectangle.get(0).getY());
        for (int i = 1; i < rectangle.size(); i++)
            fitted.lineTo(rectangle.get(i).getX(), rectangle.get(i).getY());
        fitted.closePath();
        int outsideCorners = 0;
        for (Point2D corner : roof) {
            if (fitted.contains(corner)) continue;
            double distance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < rectangle.size(); i++)
                distance = Math.min(distance, Geometry.segmentDistance(corner, rectangle.get(i),
                        rectangle.get((i + 1) % rectangle.size())));
            if (distance > 5 && ++outsideCorners >= 2) return true;
        }
        return false;
    }

    private static List<Point2D> detectByColor(BufferedImage image, Point click) {
        int width = image.getWidth(), height = image.getHeight();
        int center = image.getRGB(click.x, click.y);
        if (((center >>> 24) & 255) < 32) return List.of();
        int count = width * height;
        boolean[] seen = new boolean[count];
        int[] queue = new int[count];
        List<Point> region = new ArrayList<>();
        int seed = click.y * width + click.x;
        queue[0] = seed;
        seen[seed] = true;
        int head = 0, tail = 1;
        boolean reachesBorder = false;
        while (head < tail) {
            int index = queue[head++], x = index % width, y = index / width;
            int rgb = image.getRGB(x, y);
            if (((rgb >>> 24) & 255) < 32 || colorDistanceSquared(center, rgb) > 60 * 60) continue;
            region.add(new Point(x, y));
            if (x <= 2 || y <= 2 || x >= width - 3 || y >= height - 3) reachesBorder = true;
            if (region.size() > count * 0.6) return List.of();
            if (x > 0 && !seen[index - 1]) { seen[index - 1] = true; queue[tail++] = index - 1; }
            if (x + 1 < width && !seen[index + 1]) { seen[index + 1] = true; queue[tail++] = index + 1; }
            if (y > 0 && !seen[index - width]) { seen[index - width] = true; queue[tail++] = index - width; }
            if (y + 1 < height && !seen[index + width]) { seen[index + width] = true; queue[tail++] = index + width; }
        }
        if (reachesBorder || region.size() < 64) return List.of();
        double bestArea = Double.POSITIVE_INFINITY, bestAngle = 0;
        double bestMinU = 0, bestMaxU = 0, bestMinV = 0, bestMaxV = 0;
        for (int degrees = 0; degrees < 90; degrees += 2) {
            double angle = Math.toRadians(degrees), cos = Math.cos(angle), sin = Math.sin(angle);
            double minU = Double.POSITIVE_INFINITY, maxU = Double.NEGATIVE_INFINITY;
            double minV = Double.POSITIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
            for (Point point : region) {
                double u = point.x * cos + point.y * sin;
                double v = -point.x * sin + point.y * cos;
                minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
            double area = (maxU - minU + 1) * (maxV - minV + 1);
            if (area < bestArea) {
                bestArea = area; bestAngle = angle;
                bestMinU = minU; bestMaxU = maxU; bestMinV = minV; bestMaxV = maxV;
            }
        }
        double length = bestMaxU - bestMinU + 1, breadth = bestMaxV - bestMinV + 1;
        if (Math.min(length, breadth) < 7 || Math.max(length, breadth) / Math.min(length, breadth) > 12
                || region.size() / bestArea < 0.55) return List.of();
        double cos = Math.cos(bestAngle), sin = Math.sin(bestAngle);
        double lowU = bestMinU - 1, highU = bestMaxU + 1;
        double lowV = bestMinV - 1, highV = bestMaxV + 1;
        return List.of(corner(lowU, lowV, cos, sin), corner(highU, lowV, cos, sin),
                corner(highU, highV, cos, sin), corner(lowU, highV, cos, sin));
    }

    private static List<Point2D> detectByEdges(BufferedImage image, Point click) {
        List<EdgePair> horizontal = pairs(candidates(image, click, true, -1),
                candidates(image, click, true, 1), click.x);
        List<EdgePair> vertical = pairs(candidates(image, click, false, -1),
                candidates(image, click, false, 1), click.y);
        double bestScore = 0;
        List<Point2D> best = List.of();
        for (EdgePair x : horizontal) for (EdgePair y : vertical) {
            int left = x.first.position, right = x.second.position;
            int top = y.first.position, bottom = y.second.position;
            if (right - left < 16 || bottom - top < 16) continue;
            double aspect = Math.max((double) (right - left) / (bottom - top),
                    (double) (bottom - top) / (right - left));
            if (aspect > 8) continue;
            int middleX = (left + right) / 2, middleY = (top + bottom) / 2;
            int halfWidth = (right - left) / 2, halfHeight = (bottom - top) / 2;
            double leftEdge = edgeScore(image, left, middleY, true, halfHeight);
            double rightEdge = edgeScore(image, right, middleY, true, halfHeight);
            double topEdge = edgeScore(image, middleX, top, false, halfWidth);
            double bottomEdge = edgeScore(image, middleX, bottom, false, halfWidth);
            if (Math.min(Math.min(leftEdge, rightEdge), Math.min(topEdge, bottomEdge)) < 0.045) continue;
            double score = (leftEdge + rightEdge + topEdge + bottomEdge) / 4
                    + (x.first.strength + x.second.strength + y.first.strength + y.second.strength) / 16
                    - 0.0001 * (right - left + bottom - top);
            if (score > bestScore) {
                bestScore = score;
                best = List.of(new Point2D.Double(left - 2, top - 2), new Point2D.Double(right + 2, top - 2),
                        new Point2D.Double(right + 2, bottom + 2), new Point2D.Double(left - 2, bottom + 2));
            }
        }
        return bestScore >= 0.09 ? best : List.of();
    }

    private static List<EdgeCandidate> candidates(BufferedImage image, Point click, boolean vertical, int direction) {
        int center = vertical ? click.x : click.y;
        int extent = vertical ? image.getWidth() : image.getHeight();
        List<EdgeCandidate> scored = new ArrayList<>();
        for (int distance = 8; distance <= 145; distance++) {
            int position = center + direction * distance;
            if (position < 4 || position >= extent - 4) break;
            double strength = edgeScore(image, vertical ? position : click.x,
                    vertical ? click.y : position, vertical, 24);
            if (strength >= 0.065) scored.add(new EdgeCandidate(position, strength));
        }
        scored.sort(Comparator.comparingDouble((EdgeCandidate c) -> c.strength).reversed());
        List<EdgeCandidate> result = new ArrayList<>();
        for (EdgeCandidate candidate : scored) {
            if (result.stream().anyMatch(existing -> Math.abs(existing.position - candidate.position) < 6)) continue;
            result.add(candidate);
            if (result.size() == 12) break;
        }
        return result;
    }

    private static List<EdgePair> pairs(List<EdgeCandidate> before, List<EdgeCandidate> after, int center) {
        List<EdgePair> scored = new ArrayList<>();
        for (EdgeCandidate first : before) for (EdgeCandidate second : after) {
            double near = center - first.position, far = second.position - center;
            if (near <= 0 || far <= 0 || Math.min(near, far) / Math.max(near, far) < 0.5) continue;
            double score = first.strength + second.strength - 0.04 * Math.abs(Math.log(near / far));
            scored.add(new EdgePair(first, second, score));
        }
        scored.sort(Comparator.comparingDouble((EdgePair pair) -> pair.score).reversed());
        return scored.subList(0, Math.min(8, scored.size()));
    }

    private static double edgeScore(BufferedImage image, int x, int y, boolean vertical, int halfSpan) {
        double total = 0;
        int samples = 0;
        for (int offset = -halfSpan; offset <= halfSpan; offset += 4) {
            int px = vertical ? x : x + offset, py = vertical ? y + offset : y;
            int ax = vertical ? px - 3 : px, ay = vertical ? py : py - 3;
            int bx = vertical ? px + 3 : px, by = vertical ? py : py + 3;
            if (ax < 0 || ay < 0 || bx >= image.getWidth() || by >= image.getHeight()) continue;
            int a = image.getRGB(ax, ay), b = image.getRGB(bx, by);
            if (((a >>> 24) & 255) < 32 || ((b >>> 24) & 255) < 32) continue;
            total += Math.sqrt(colorDistanceSquared(a, b)) / 441.7;
            samples++;
        }
        return samples == 0 ? 0 : total / samples;
    }

    private static double polygonArea(List<Point2D> points) {
        double twiceArea = 0;
        for (int i = 0; i < points.size(); i++) {
            Point2D a = points.get(i), b = points.get((i + 1) % points.size());
            twiceArea += a.getX() * b.getY() - b.getX() * a.getY();
        }
        return Math.abs(twiceArea) / 2;
    }

    private static final class EdgeCandidate {
        final int position;
        final double strength;
        EdgeCandidate(int position, double strength) { this.position = position; this.strength = strength; }
    }

    private static final class EdgePair {
        final EdgeCandidate first, second;
        final double score;
        EdgePair(EdgeCandidate first, EdgeCandidate second, double score) {
            this.first = first; this.second = second; this.score = score;
        }
    }

    private static int colorDistanceSquared(int a, int b) {
        int red = ((a >> 16) & 255) - ((b >> 16) & 255);
        int green = ((a >> 8) & 255) - ((b >> 8) & 255);
        int blue = (a & 255) - (b & 255);
        return red * red + green * green + blue * blue;
    }

    private static Point2D corner(double u, double v, double cos, double sin) {
        return new Point2D.Double(u * cos - v * sin, u * sin + v * cos);
    }
}
