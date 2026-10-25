package org.openstreetmap.josm.plugins.smartdraw;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/** Local image-edge path search. Input is a rendered, bounded imagery crop. */
public final class MagneticTracer {
    private MagneticTracer() { }

    /** Finds a visible image edge near the pointer without moving farther than attraction pixels. */
    public static Point snap(BufferedImage image, Point cursor, int attraction) {
        if (image == null || cursor == null || attraction < 1) return null;
        int width = image.getWidth(), height = image.getHeight();
        int left = Math.max(1, cursor.x - attraction), right = Math.min(width - 2, cursor.x + attraction);
        int top = Math.max(1, cursor.y - attraction), bottom = Math.min(height - 2, cursor.y + attraction);
        Point best = null;
        double bestScore = 0;
        for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
            double distance = cursor.distance(x, y);
            if (distance > attraction || !opaque(image.getRGB(x, y))
                    || !opaque(image.getRGB(x - 1, y)) || !opaque(image.getRGB(x + 1, y))
                    || !opaque(image.getRGB(x, y - 1)) || !opaque(image.getRGB(x, y + 1))) continue;
            double strength = colorEdge(image, x, y);
            if (strength < 0.035) continue;
            double score = strength / (1 + distance / 8);
            if (score > bestScore) { bestScore = score; best = new Point(x, y); }
        }
        return best;
    }

    private static boolean opaque(int rgb) { return ((rgb >>> 24) & 255) >= 32; }

    private static double softChannel(BufferedImage image, int x, int y, int shift) {
        double sum = 0, weightSum = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int nx = x + dx, ny = y + dy;
            if (nx < 0 || ny < 0 || nx >= image.getWidth() || ny >= image.getHeight()) continue;
            int rgb = image.getRGB(nx, ny);
            if (!opaque(rgb)) continue;
            double weight = (dx == 0 ? 2 : 1) * (dy == 0 ? 2 : 1);
            sum += ((rgb >> shift) & 255) * weight;
            weightSum += weight;
        }
        return weightSum == 0 ? 0 : sum / weightSum;
    }

    private static double colorEdge(BufferedImage image, int x, int y) {
        double energy = 0;
        for (int shift : new int[] {16, 8, 0}) {
            double gx = softChannel(image, x + 1, y, shift) - softChannel(image, x - 1, y, shift);
            double gy = softChannel(image, x, y + 1, shift) - softChannel(image, x, y - 1, shift);
            energy += gx * gx + gy * gy;
        }
        return Math.sqrt(energy / 3) / 255;
    }

    public static List<Point> trace(BufferedImage image, Point start, Point goal,
            double sensitivity, int attraction, BooleanSupplier cancelled) {
        int width = image.getWidth(), height = image.getHeight();
        if (width < 3 || height < 3 || start.x < 0 || start.y < 0 || goal.x < 0 || goal.y < 0
                || start.x >= width || goal.x >= width || start.y >= height || goal.y >= height) return List.of();
        int count = width * height;
        double[] red = new double[count], green = new double[count], blue = new double[count];
        double[] edge = new double[count];
        boolean[] valid = new boolean[count];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgb = image.getRGB(x, y), index = y * width + x;
            valid[index] = opaque(rgb);
            red[index] = (rgb >> 16) & 255;
            green[index] = (rgb >> 8) & 255;
            blue[index] = rgb & 255;
        }
        double[] softRed = new double[count], softGreen = new double[count], softBlue = new double[count];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = y * width + x;
            if (!valid[i]) continue;
            double sumRed = 0, sumGreen = 0, sumBlue = 0, weights = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                int nearby = ny * width + nx;
                if (!valid[nearby]) continue;
                double weight = (dx == 0 ? 2 : 1) * (dy == 0 ? 2 : 1);
                sumRed += red[nearby] * weight;
                sumGreen += green[nearby] * weight;
                sumBlue += blue[nearby] * weight;
                weights += weight;
            }
            softRed[i] = sumRed / weights;
            softGreen[i] = sumGreen / weights;
            softBlue[i] = sumBlue / weights;
        }
        double maximum = 0;
        for (int y = 1; y < height - 1; y++) for (int x = 1; x < width - 1; x++) {
            int i = y * width + x;
            if (!valid[i] || !valid[i - 1] || !valid[i + 1] || !valid[i - width] || !valid[i + width]) continue;
            double rx = softRed[i + 1] - softRed[i - 1], ry = softRed[i + width] - softRed[i - width];
            double gx = softGreen[i + 1] - softGreen[i - 1], gy = softGreen[i + width] - softGreen[i - width];
            double bx = softBlue[i + 1] - softBlue[i - 1], by = softBlue[i + width] - softBlue[i - width];
            edge[i] = Math.sqrt((rx * rx + ry * ry + gx * gx + gy * gy + bx * bx + by * by) / 3) / 255;
            maximum = Math.max(maximum, edge[i]);
        }
        if (maximum < 0.035) return List.of();
        int source = start.y * width + start.x, target = goal.y * width + goal.x;
        if (!valid[source] || !valid[target]) return List.of();
        double[] distance = new double[count];
        int[] previous = new int[count];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);
        PriorityQueue<Step> queue = new PriorityQueue<>((a, b) -> Double.compare(a.cost, b.cost));
        double chordX = goal.x - start.x, chordY = goal.y - start.y;
        double chordLengthSquared = chordX * chordX + chordY * chordY;
        double attractionSquared = (double) attraction * attraction;
        distance[source] = 0;
        queue.add(new Step(source, 0));
        while (!queue.isEmpty()) {
            if (cancelled.getAsBoolean()) return List.of();
            Step current = queue.poll();
            if (current.cost != distance[current.index]) continue;
            if (current.index == target) break;
            int x = current.index % width, y = current.index / width;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (nx < 1 || nx >= width - 1 || ny < 1 || ny >= height - 1) continue;
                int next = ny * width + nx;
                if (!valid[next]) continue;
                double fraction = chordLengthSquared == 0 ? 0 : Math.max(0, Math.min(1,
                        ((nx - start.x) * chordX + (ny - start.y) * chordY) / chordLengthSquared));
                double offsetX = nx - start.x - fraction * chordX;
                double offsetY = ny - start.y - fraction * chordY;
                double corridorSquared = offsetX * offsetX + offsetY * offsetY;
                if (corridorSquared > attractionSquared) continue;
                double corridorDistance = Math.sqrt(corridorSquared);
                double strength = Math.min(1, edge[next] / 0.15);
                double cost = (dx == 0 || dy == 0 ? 1 : 1.4142)
                        * (1 + Math.max(0, sensitivity) * (1 - strength) + 0.015 * corridorDistance);
                double proposal = current.cost + cost;
                if (proposal < distance[next]) {
                    distance[next] = proposal; previous[next] = current.index;
                    queue.add(new Step(next, proposal));
                }
            }
        }
        if (!Double.isFinite(distance[target])) return List.of();
        List<Point> path = new ArrayList<>();
        for (int i = target; i != -1; i = previous[i]) path.add(new Point(i % width, i / width));
        Collections.reverse(path);
        int weakRun = 0;
        int strongPixels = 0;
        double minimumEdge = 0.035;
        for (Point point : path) {
            if (edge[point.y * width + point.x] >= minimumEdge) {
                weakRun = 0;
                strongPixels++;
            } else if (++weakRun > 12) {
                return List.of();
            }
        }
        if (strongPixels * 2 < path.size()) return List.of();
        return path;
    }

    private static final class Step {
        final int index; final double cost;
        Step(int index, double cost) { this.index = index; this.cost = cost; }
    }
}
