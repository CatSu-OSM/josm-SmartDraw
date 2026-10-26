package org.openstreetmap.josm.plugins.smartdraw;

import java.util.List;
import org.openstreetmap.josm.data.coor.LatLon;

/** Decides when discarding an unsaved trace needs confirmation. */
public final class DraftDiscardPolicy {
    private DraftDiscardPolicy() { }

    public static double lengthMeters(List<LatLon> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++)
            length += points.get(i - 1).greatCircleDistance(points.get(i));
        return length;
    }

    public static boolean needsConfirmation(List<LatLon> points, int nodeLimit, double meterLimit) {
        return points.size() > nodeLimit || lengthMeters(points) > meterLimit;
    }
}
