package org.openstreetmap.josm.plugins.smartdraw;

import java.awt.Point;
import java.awt.geom.Point2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Proposes a polygon from a connected roof-colored region. */
public final class RoofSegmenter {
    private static final int CLOSE_RADIUS = 4;
    private RoofSegmenter() { }

    /** Checks nearby roof facets so a click on one wing can recover the joined outline. */
    public static List<Point2D> expand(BufferedImage image, Point click, List<Point2D> initial) {
        if (initial.isEmpty()) return initial;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Point2D point : initial) {
            minX = Math.min(minX, point.getX()); maxX = Math.max(maxX, point.getX());
            minY = Math.min(minY, point.getY()); maxY = Math.max(maxY, point.getY());
        }
        double middleX = (minX + maxX) / 2, middleY = (minY + maxY) / 2;
        Point[] probes = {
            new Point((int) middleX, (int) (minY + (maxY - minY) * 0.75)),
            new Point((int) middleX, (int) (minY + (maxY - minY) * 0.25)),
            new Point((int) (minX + (maxX - minX) * 0.25), (int) middleY),
            new Point((int) (minX + (maxX - minX) * 0.75), (int) middleY)
        };
        List<Point2D> best = initial;
        double bestArea = polygonArea(initial);
        for (Point probe : probes) {
            if (probe.distance(click) < 24 || !contains(initial, probe)) continue;
            List<Point2D> proposed = detect(image, probe);
            double area = polygonArea(proposed);
            if (area > bestArea * 1.08 && area < bestArea * 1.8 && contains(proposed, click)) {
                best = proposed;
                bestArea = area;
            }
        }
        return best;
    }

    private static boolean contains(List<Point2D> polygon, Point point) {
        if (polygon.size() < 3) return false;
        Path2D path = new Path2D.Double();
        path.moveTo(polygon.get(0).getX(), polygon.get(0).getY());
        for (int i = 1; i < polygon.size(); i++) path.lineTo(polygon.get(i).getX(), polygon.get(i).getY());
        path.closePath();
        return path.contains(point);
    }

    public static List<Point2D> detect(BufferedImage image, Point click) {
        if (image == null || click == null || click.x < 4 || click.y < 4
                || click.x >= image.getWidth() - 4 || click.y >= image.getHeight() - 4) return List.of();
        int width = image.getWidth(), height = image.getHeight(), count = width * height;
        double[] seed = seedColor(image, click);
        if (seed == null) return List.of();
        long[][] texture = textureIntegrals(image);
        boolean[] mask = new boolean[count];
        double seedBrightness = (seed[0] + seed[1] + seed[2]) / 3;
        double seedRedGreen = seed[0] - seed[1], seedBlueGreen = seed[2] - seed[1];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgb = image.getRGB(x, y);
            if (((rgb >>> 24) & 255) < 32) continue;
            int red = (rgb >> 16) & 255, green = (rgb >> 8) & 255, blue = rgb & 255;
            double brightness = (red + green + blue) / 3.0;
            double colorTolerance = 10;
            mask[y * width + x] = Math.abs(red - green - seedRedGreen) <= colorTolerance
                    && Math.abs(blue - green - seedBlueGreen) <= colorTolerance
                    && brightness >= (seedBrightness > 150 ? Math.max(120, seedBrightness * 0.72)
                            : Math.max(32, seedBrightness * 0.35))
                    && brightness <= Math.min(255, seedBrightness * 1.55)
                    && (seedBrightness > 150 || blue >= green - 2)
                    && smoothSurface(texture, width, height, x, y, seedBrightness > 150 ? 6 : 4.5);
        }
        mask = close(mask, width, height);
        int start = nearestMask(mask, width, height, click);
        if (start < 0) return List.of();
        boolean[] component = new boolean[count];
        int[] queue = new int[count];
        queue[0] = start;
        component[start] = true;
        int head = 0, tail = 1;
        boolean reachesBorder = false;
        while (head < tail) {
            int index = queue[head++], x = index % width, y = index / width;
            if (x <= 2 || y <= 2 || x >= width - 3 || y >= height - 3) reachesBorder = true;
            if (tail > count * 0.55) return List.of();
            if (x > 0) tail = add(mask, component, queue, tail, index - 1);
            if (x + 1 < width) tail = add(mask, component, queue, tail, index + 1);
            if (y > 0) tail = add(mask, component, queue, tail, index - width);
            if (y + 1 < height) tail = add(mask, component, queue, tail, index + width);
        }
        if (reachesBorder || tail < 90) return List.of();
        List<Point2D> boundary = outerBoundary(component, width, height);
        if (boundary.size() < 8) return List.of();
        List<Point2D> smoothed = Geometry.smooth(boundary, 2);
        List<Point2D> simplified = List.of();
        for (double tolerance = 3; tolerance <= 15; tolerance += 1) {
            simplified = Geometry.simplify(smoothed, tolerance);
            if (simplified.size() <= 24) break;
        }
        if (simplified.size() > 1 && simplified.get(0).distance(simplified.get(simplified.size() - 1)) < 1)
            simplified = new ArrayList<>(simplified.subList(0, simplified.size() - 1));
        if (simplified.size() < 4 || simplified.size() > 28
                || Geometry.selfIntersects(simplified, true)) return List.of();
        double area = polygonArea(simplified);
        if (area < 80 || area > count * 0.5 || tail / area < 0.45) return List.of();
        return BuildingGeometry.regularize(simplified);
    }

    private static double[] seedColor(BufferedImage image, Point click) {
        double red = 0, green = 0, blue = 0;
        int count = 0;
        for (int y = click.y - 2; y <= click.y + 2; y++) for (int x = click.x - 2; x <= click.x + 2; x++) {
            int rgb = image.getRGB(x, y);
            if (((rgb >>> 24) & 255) < 32) continue;
            red += (rgb >> 16) & 255;
            green += (rgb >> 8) & 255;
            blue += rgb & 255;
            count++;
        }
        return count < 12 ? null : new double[] {red / count, green / count, blue / count};
    }

    private static long[][] textureIntegrals(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight(), stride = width + 1;
        long[] sum = new long[stride * (height + 1)], squares = new long[sum.length];
        for (int y = 1; y <= height; y++) {
            long row = 0, rowSquares = 0;
            for (int x = 1; x <= width; x++) {
                int rgb = image.getRGB(x - 1, y - 1);
                int brightness = (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255)) / 3;
                row += brightness;
                rowSquares += (long) brightness * brightness;
                int index = y * stride + x;
                sum[index] = sum[index - stride] + row;
                squares[index] = squares[index - stride] + rowSquares;
            }
        }
        return new long[][] {sum, squares};
    }

    private static boolean smoothSurface(long[][] texture, int width, int height, int x, int y, double maximumSd) {
        if (x < 2 || y < 2 || x >= width - 2 || y >= height - 2) return false;
        int stride = width + 1, left = x - 2, top = y - 2, right = x + 3, bottom = y + 3;
        long sum = regionSum(texture[0], stride, left, top, right, bottom);
        long squares = regionSum(texture[1], stride, left, top, right, bottom);
        double mean = sum / 25.0;
        double variance = squares / 25.0 - mean * mean;
        return variance <= maximumSd * maximumSd;
    }

    private static long regionSum(long[] integral, int stride, int left, int top, int right, int bottom) {
        return integral[bottom * stride + right] - integral[top * stride + right]
                - integral[bottom * stride + left] + integral[top * stride + left];
    }

    private static boolean[] close(boolean[] input, int width, int height) {
        int radius = CLOSE_RADIUS;
        boolean[] dilated = new boolean[input.length];
        for (int y = radius; y < height - radius; y++) for (int x = radius; x < width - radius; x++) {
            int index = y * width + x;
            for (int dy = -radius; dy <= radius && !dilated[index]; dy++) for (int dx = -radius; dx <= radius; dx++) {
                if (input[index + dy * width + dx]) { dilated[index] = true; break; }
            }
        }
        boolean[] result = new boolean[input.length];
        for (int y = radius * 2; y < height - radius * 2; y++)
            for (int x = radius * 2; x < width - radius * 2; x++) {
            int index = y * width + x;
            boolean full = true;
            for (int dy = -radius; dy <= radius && full; dy++) for (int dx = -radius; dx <= radius; dx++) {
                if (!dilated[index + dy * width + dx]) { full = false; break; }
            }
            result[index] = full;
        }
        return result;
    }

    private static int nearestMask(boolean[] mask, int width, int height, Point click) {
        int best = -1, bestDistance = Integer.MAX_VALUE;
        for (int dy = -6; dy <= 6; dy++) for (int dx = -6; dx <= 6; dx++) {
            int x = click.x + dx, y = click.y + dy;
            if (x < 0 || y < 0 || x >= width || y >= height || !mask[y * width + x]) continue;
            int distance = dx * dx + dy * dy;
            if (distance < bestDistance) { bestDistance = distance; best = y * width + x; }
        }
        return best;
    }

    private static int add(boolean[] mask, boolean[] component, int[] queue, int tail, int index) {
        if (mask[index] && !component[index]) { component[index] = true; queue[tail++] = index; }
        return tail;
    }

    private static List<Point2D> outerBoundary(boolean[] region, int width, int height) {
        int stride = width + 1;
        Map<Integer, Integer> edges = new HashMap<>();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int index = y * width + x;
            if (!region[index]) continue;
            int topLeft = y * stride + x, topRight = topLeft + 1;
            int bottomLeft = topLeft + stride, bottomRight = bottomLeft + 1;
            if (y == 0 || !region[index - width]) edges.put(topLeft, topRight);
            if (x == width - 1 || !region[index + 1]) edges.put(topRight, bottomRight);
            if (y == height - 1 || !region[index + width]) edges.put(bottomRight, bottomLeft);
            if (x == 0 || !region[index - 1]) edges.put(bottomLeft, topLeft);
        }
        List<Point2D> largest = List.of();
        double largestArea = 0;
        while (!edges.isEmpty()) {
            int start = edges.keySet().iterator().next(), current = start;
            List<Point2D> loop = new ArrayList<>();
            for (int step = 0; step <= width * height * 4; step++) {
                loop.add(new Point2D.Double(current % stride, current / stride));
                Integer next = edges.remove(current);
                if (next == null) break;
                current = next;
                if (current == start) {
                    loop.add(loop.get(0));
                    double area = polygonArea(loop);
                    if (area > largestArea) { largestArea = area; largest = loop; }
                    break;
                }
            }
        }
        return largest;
    }

    private static double polygonArea(List<Point2D> points) {
        double twiceArea = 0;
        for (int i = 0; i < points.size(); i++) {
            Point2D a = points.get(i), b = points.get((i + 1) % points.size());
            twiceArea += a.getX() * b.getY() - b.getX() * a.getY();
        }
        return Math.abs(twiceArea) / 2;
    }
}
