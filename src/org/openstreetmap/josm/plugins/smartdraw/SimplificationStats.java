package org.openstreetmap.josm.plugins.smartdraw;

import java.util.List;
import org.openstreetmap.josm.data.coor.LatLon;

/** Adapted from FastDraw's GPL sliding-window node density calculation. */
public final class SimplificationStats {
    private SimplificationStats() { }

    public static double maxPointsPerKm(List<LatLon> points, int blockSize) {
        int count = points.size();
        if (count < 2) return 0;
        int window = Math.max(2, Math.min(blockSize, count));
        double distance = 0, maximum = 0;
        for (int i = 1; i < count; i++) {
            distance += points.get(i - 1).greatCircleDistance(points.get(i));
            if (i > window) distance -= points.get(i - window - 1).greatCircleDistance(points.get(i - window));
            if (i >= window || i == count - 1) {
                int segments = Math.min(i, window);
                if (distance > 0) maximum = Math.max(maximum, 1000.0 * segments / distance);
            }
        }
        return Math.round(maximum);
    }
}
