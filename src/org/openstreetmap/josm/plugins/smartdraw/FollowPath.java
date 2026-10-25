package org.openstreetmap.josm.plugins.smartdraw;

import java.util.ArrayList;
import java.util.List;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/** Traverses the original node sequence without changing or copying its nodes. */
public final class FollowPath {
    private FollowPath() { }

    public static List<Node> between(Way way, int from, int to, boolean forward) {
        if (way == null || way.hasIncompleteNodes()) throw new IllegalArgumentException("Incomplete way");
        return between(way.getNodes(), way.isClosed(), from, to, forward);
    }

    public static List<Node> between(List<Node> nodes, boolean closed, int from, int to, boolean forward) {
        int count = nodes.size() - (closed ? 1 : 0);
        if (count < 2 || from < 0 || to < 0 || from >= count || to >= count) {
            throw new IllegalArgumentException("Invalid endpoint");
        }
        List<Node> result = new ArrayList<>();
        int index = from;
        result.add(nodes.get(index));
        while (index != to) {
            index += forward ? 1 : -1;
            if (index < 0 || index >= count) {
                if (!closed) throw new IllegalArgumentException("Direction leaves open way");
                index = Math.floorMod(index, count);
            }
            result.add(nodes.get(index));
            if (result.size() > count + 1) throw new IllegalStateException("Path did not terminate");
        }
        return result;
    }
}
