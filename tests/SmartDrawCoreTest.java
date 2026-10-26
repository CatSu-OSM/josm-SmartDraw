import java.awt.geom.Point2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.smartdraw.FollowPath;
import org.openstreetmap.josm.plugins.smartdraw.BuildingDetector;
import org.openstreetmap.josm.plugins.smartdraw.BuildingGeometry;
import org.openstreetmap.josm.plugins.smartdraw.DraftDiscardPolicy;
import org.openstreetmap.josm.plugins.smartdraw.FastDrawSimplifier;
import org.openstreetmap.josm.plugins.smartdraw.Geometry;
import org.openstreetmap.josm.plugins.smartdraw.MagneticTracer;
import org.openstreetmap.josm.plugins.smartdraw.SimplificationStats;
import org.openstreetmap.josm.plugins.smartdraw.TagRules;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.spi.preferences.Config;

public final class SmartDrawCoreTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Config.setPreferencesInstance(new Preferences());
        Node a = new Node(new LatLon(0, 0));
        Node b = new Node(new LatLon(0, 1));
        Node c = new Node(new LatLon(1, 1));
        Node d = new Node(new LatLon(1, 0));
        Way forest = new Way();
        forest.setNodes(List.of(a, b, c, d, a));
        forest.put("landuse", "forest");
        TagRules rules = new TagRules();
        check(rules.eligible(forest), "forest eligible");
        Way road = new Way();
        road.setNodes(List.of(a, b));
        road.put("highway", "residential");
        check(!rules.eligible(road), "road excluded");
        rules.configure("highway", "railway", true);
        check(rules.eligible(road), "exclusion override");
        check(FollowPath.between(forest, 0, 2, true).equals(List.of(a, b, c)), "forward polygon");
        check(FollowPath.between(forest, 0, 2, false).equals(List.of(a, d, c)), "reverse polygon");
        Way open = new Way();
        open.setNodes(List.of(a, b, c));
        check(FollowPath.between(open, 2, 0, false).equals(List.of(c, b, a)), "reverse open way");
        boolean refused = false;
        try { FollowPath.between(open, 2, 0, true); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "open way cannot wrap");
        List<Point2D> points = List.of(new Point2D.Double(0, 0), new Point2D.Double(1, 0.1),
                new Point2D.Double(2, 0), new Point2D.Double(3, 1));
        check(Geometry.simplify(points, 0.2).size() == 3, "simplification keeps bend");
        List<Point> fastDrawPoints = List.of(new Point(0, 0), new Point(10, 3),
                new Point(20, 0), new Point(30, 0));
        check(FastDrawSimplifier.keptIndices(fastDrawPoints,
                List.of(false, false, false, false), 2).equals(List.of(0, 1, 3)),
                "FastDraw epsilon keeps a meaningful screen-pixel bend");
        check(FastDrawSimplifier.keptIndices(fastDrawPoints,
                List.of(false, false, true, false), 4).equals(List.of(0, 2, 3)),
                "fixed shared nodes split simplification fragments");
        check(FastDrawSimplifier.keptIndices(List.of(new Point(0, 0), new Point(15, 1), new Point(10, 0)),
                List.of(false, false, false), 2).equals(List.of(0, 2)),
                "FastDraw simplification measures distance to the infinite line");
        check(SimplificationStats.maxPointsPerKm(List.of(new LatLon(0, 0),
                new LatLon(0, 0.001), new LatLon(0, 0.002)), 10) >= 8,
                "points per kilometre uses geographic distance");
        check(SimplificationStats.maxPointsPerKm(List.of(new LatLon(0, 0),
                new LatLon(0, 0.001), new LatLon(0, 0.00101), new LatLon(0, 0.00102)), 2) > 800,
                "sliding density window detects a locally dense segment");
        List<LatLon> shortDraft = List.of(new LatLon(0, 0), new LatLon(0, 0.0005));
        List<LatLon> longDraft = List.of(new LatLon(0, 0), new LatLon(0, 0.0011));
        check(!DraftDiscardPolicy.needsConfirmation(shortDraft, 10, 100),
                "short drafts cancel without confirmation");
        check(DraftDiscardPolicy.needsConfirmation(longDraft, 10, 100),
                "drafts longer than 100 metres require confirmation");
        check(!DraftDiscardPolicy.needsConfirmation(java.util.Collections.nCopies(10, new LatLon(0, 0)), 10, 100),
                "exactly ten nodes do not require confirmation by count");
        check(DraftDiscardPolicy.needsConfirmation(java.util.Collections.nCopies(11, new LatLon(0, 0)), 10, 100),
                "drafts with more than ten nodes require confirmation");
        List<Point2D> noisy = List.of(new Point2D.Double(0, 0), new Point2D.Double(1, 3),
                new Point2D.Double(2, -3), new Point2D.Double(3, 3),
                new Point2D.Double(4, -3), new Point2D.Double(5, 0));
        List<Point2D> smoothed = Geometry.smooth(noisy, 3);
        check(smoothed.get(0).equals(noisy.get(0)) && smoothed.get(5).equals(noisy.get(5))
                && Math.abs(smoothed.get(2).getY()) < 1, "smoothing keeps endpoints and removes pixel zigzags");
        List<Point2D> outline = List.of(new Point2D.Double(0, 0), new Point2D.Double(10, 0),
                new Point2D.Double(10, 10), new Point2D.Double(0, 10));
        check(!Geometry.canExtendWithoutCrossing(outline, new Point2D.Double(5, -1), 3),
                "drag crossing an earlier segment is rejected");
        check(!Geometry.canExtendWithoutCrossing(outline, new Point2D.Double(0, 2), 3),
                "drag approaching an earlier segment is rejected");
        check(Geometry.canExtendWithoutCrossing(outline, new Point2D.Double(-5, 10), 3),
                "valid continuation remains possible");
        check(!Geometry.canExtendWithoutCrossing(outline.subList(0, 2),
                new Point2D.Double(5, 0), 3), "doubling back is rejected");
        List<Point2D> hiddenCorner = BuildingGeometry.regularize(List.of(
                new Point2D.Double(0, 0), new Point2D.Double(88, 0),
                new Point2D.Double(100, 12), new Point2D.Double(100, 100),
                new Point2D.Double(0, 100)));
        check(hiddenCorner.size() == 4 && hiddenCorner.stream().anyMatch(p -> p.distance(100, 0) < 2),
                "adjacent straight edges locate a corner hidden by a short obstruction");
        List<Point2D> angledRoof = List.of(new Point2D.Double(0, 0), new Point2D.Double(100, 0),
                new Point2D.Double(130, 70), new Point2D.Double(30, 70));
        check(BuildingGeometry.regularize(angledRoof).equals(angledRoof),
                "clear non-square building angles are retained");
        List<Point2D> jaggedRoof = List.of(
                new Point2D.Double(0, 0), new Point2D.Double(45, 0),
                new Point2D.Double(45, -8), new Point2D.Double(58, -8),
                new Point2D.Double(58, 0), new Point2D.Double(92, 0),
                new Point2D.Double(92, 8), new Point2D.Double(104, 8),
                new Point2D.Double(104, 0), new Point2D.Double(160, 0),
                new Point2D.Double(160, 100), new Point2D.Double(0, 100));
        List<Point2D> cleanRoof = BuildingGeometry.regularize(jaggedRoof);
        check(cleanRoof.size() == 4 && !Geometry.selfIntersects(cleanRoof, true),
                "small rooftop notches do not become footprint corners: " + cleanRoof);
        double roofAngle = Math.toRadians(25);
        List<Point2D> rotatedRoof = new java.util.ArrayList<>();
        for (Point2D point : jaggedRoof) rotatedRoof.add(new Point2D.Double(
                point.getX() * Math.cos(roofAngle) - point.getY() * Math.sin(roofAngle),
                point.getX() * Math.sin(roofAngle) + point.getY() * Math.cos(roofAngle)));
        check(BuildingGeometry.regularize(rotatedRoof).size() == 4,
                "wall fitting handles rotated buildings without retaining rooftop notches");
        List<Point2D> jaggedComplexRoof = List.of(
                new Point2D.Double(197, 262), new Point2D.Double(151, 139),
                new Point2D.Double(182, 144), new Point2D.Double(181, 91),
                new Point2D.Double(202, 57), new Point2D.Double(240, 99),
                new Point2D.Double(296, 83), new Point2D.Double(297, 69),
                new Point2D.Double(315, 47), new Point2D.Double(337, 69),
                new Point2D.Double(390, 11), new Point2D.Double(487, 10),
                new Point2D.Double(487, 84), new Point2D.Double(520, 84),
                new Point2D.Double(520, 55), new Point2D.Double(547, 99),
                new Point2D.Double(457, 141), new Point2D.Double(453, 88),
                new Point2D.Double(390, 119), new Point2D.Double(373, 175),
                new Point2D.Double(408, 247), new Point2D.Double(302, 296),
                new Point2D.Double(254, 236));
        List<Point2D> complexFit = BuildingGeometry.regularize(jaggedComplexRoof);
        check(complexFit.size() <= 14 && !Geometry.selfIntersects(complexFit, true),
                "complex roof outline loses spurious corners without crossing itself: " + complexFit);
        double firstWallAngle = Math.atan2(complexFit.get(1).getY() - complexFit.get(0).getY(),
                complexFit.get(1).getX() - complexFit.get(0).getX());
        for (int i = 0; i < complexFit.size(); i++) {
            Point2D aWall = complexFit.get(i), bWall = complexFit.get((i + 1) % complexFit.size());
            double wallAngle = Math.atan2(bWall.getY() - aWall.getY(), bWall.getX() - aWall.getX());
            check(Math.abs(Math.sin(2 * (wallAngle - firstWallAngle))) < 0.15,
                    "complex roof walls use two perpendicular directions");
        }
        check(Geometry.selfIntersects(List.of(new Point2D.Double(0, 0), new Point2D.Double(2, 2),
                new Point2D.Double(0, 2), new Point2D.Double(2, 0)), true), "crossing rejected");
        check(!Geometry.selfIntersects(List.of(new Point2D.Double(0, 0), new Point2D.Double(0, 2),
                new Point2D.Double(2, 2), new Point2D.Double(2, 0)), true), "simple area accepted");
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            image.setRGB(x, y, x < 16 ? 0xff000000 : 0xffffffff);
        }
        List<Point> magnetic = MagneticTracer.trace(image, new Point(15, 3), new Point(15, 28),
                8, 5, () -> false);
        check(!magnetic.isEmpty() && magnetic.get(0).equals(new Point(15, 3)) &&
                magnetic.get(magnetic.size() - 1).equals(new Point(15, 28)), "magnetic path follows visible edge");
        Point snapped = MagneticTracer.snap(image, new Point(12, 16), 6);
        check(snapped != null && Math.abs(snapped.x - 15) <= 1 && snapped.y == 16,
                "drag pointer snaps to nearby image edge");
        BufferedImage hueEdge = new BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) {
            hueEdge.setRGB(x, y, x < 32 ? 0xffc80000 : 0xff003b00);
        }
        Point hueSnap = MagneticTracer.snap(hueEdge, new Point(12, 24), 24);
        check(hueSnap != null && Math.abs(hueSnap.x - 32) <= 1,
                "drag detects a color boundary beyond the former 12-pixel search");
        check(!MagneticTracer.trace(hueEdge, new Point(31, 4), new Point(31, 43),
                12, 5, () -> false).isEmpty(), "tracing follows an equal-brightness color boundary");
        BufferedImage translucent = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            translucent.setRGB(x, y, x < 16 ? 0x80000000 : 0x80ffffff);
        }
        check(!MagneticTracer.trace(translucent, new Point(15, 3), new Point(15, 28),
                8, 5, () -> false).isEmpty(), "partly transparent imagery remains traceable");
        BufferedImage blank = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        check(MagneticTracer.trace(blank, new Point(15, 3), new Point(15, 28), 8, 5,
                () -> false).isEmpty(), "missing imagery produces no path");
        check(MagneticTracer.snap(blank, new Point(15, 16), 6) == null,
                "drag does not snap to transparent imagery");
        BufferedImage broken = new BufferedImage(32, 48, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 32; x++) {
            broken.setRGB(x, y, y >= 14 && y <= 34 ? 0xffffffff
                    : x < 16 ? 0xff000000 : 0xffffffff);
        }
        check(MagneticTracer.trace(broken, new Point(15, 4), new Point(15, 43),
                12, 5, () -> false).isEmpty(), "broken imagery edge cannot be bridged");
        BufferedImage curved = new BufferedImage(96, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 96; x++) {
            int boundary = 45 + (int) Math.round(20 * Math.sin((y - 10) * Math.PI / 100));
            curved.setRGB(x, y, x < boundary ? 0xff000000 : 0xffffffff);
        }
        check(MagneticTracer.trace(curved, new Point(44, 10), new Point(44, 110),
                12, 12, () -> false).isEmpty(), "narrow straight corridor cannot follow a curved edge");
        check(!MagneticTracer.trace(curved, new Point(44, 10), new Point(44, 110),
                12, 35, () -> false).isEmpty(), "wider corridor follows a long curved edge");
        BufferedImage roof = new BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 160; y++) for (int x = 0; x < 160; x++) {
            roof.setRGB(x, y, x >= 45 && x <= 95 && y >= 55 && y <= 85
                    ? 0xffb8b8b8 : 0xff427633);
        }
        List<Point2D> footprint = BuildingDetector.detect(roof, new Point(70, 70));
        check(footprint.size() == 4 && !Geometry.selfIntersects(footprint, true),
                "roof double-click proposes a simple closed footprint");
        check(footprint.stream().mapToDouble(Point2D::getX).min().orElseThrow() <= 45
                && footprint.stream().mapToDouble(Point2D::getX).max().orElseThrow() >= 95,
                "proposed footprint encloses the roof");
        check(BuildingDetector.detect(roof, new Point(20, 20)).isEmpty(),
                "large background region is not mistaken for a building");
        BufferedImage shadowedRoof = new BufferedImage(180, 180, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 180; y++) for (int x = 0; x < 180; x++) {
            boolean roofPixel = x >= 55 && x <= 115 && y >= 60 && y <= 110;
            boolean sameColoredBridge = x < 55 && y >= 82 && y <= 86;
            shadowedRoof.setRGB(x, y, roofPixel || sameColoredBridge ? 0xff30363b : 0xff578048);
        }
        List<Point2D> shadowedFootprint = BuildingDetector.detect(shadowedRoof, new Point(85, 85));
        check(shadowedFootprint.size() == 4
                && shadowedFootprint.stream().mapToDouble(Point2D::getX).min().orElseThrow() <= 58
                && shadowedFootprint.stream().mapToDouble(Point2D::getX).max().orElseThrow() >= 112,
                "roof-edge fallback works when a similarly colored strip reaches the crop border");
        BufferedImage joinedRoof = new BufferedImage(180, 180, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 180; y++) for (int x = 0; x < 180; x++) {
            boolean verticalPart = x >= 60 && x <= 100 && y >= 40 && y <= 130;
            boolean sideWing = x > 100 && x <= 140 && y >= 90 && y <= 130;
            joinedRoof.setRGB(x, y, verticalPart || sideWing
                    ? (x < 95 ? 0xff464a50 : 0xff373c44) : 0xff577b48);
        }
        List<Point2D> joinedFootprint = BuildingDetector.detect(joinedRoof, new Point(80, 70));
        check(joinedFootprint.size() >= 6 && !Geometry.selfIntersects(joinedFootprint, true),
                "multi-wing roof is proposed as a simple polygon rather than one rectangle");
        BufferedImage twoToneRoof = new BufferedImage(180, 180, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 180; y++) for (int x = 0; x < 180; x++) {
            boolean main = x >= 60 && x <= 100 && y >= 40 && y <= 130;
            boolean wing = x > 100 && x <= 140 && y >= 90 && y <= 130;
            twoToneRoof.setRGB(x, y, main ? 0xff666a70 : wing ? 0xff34383e : 0xff577b48);
        }
        List<Point2D> twoToneFootprint = BuildingDetector.detect(twoToneRoof, new Point(80, 70));
        check(twoToneFootprint.size() >= 6 && !Geometry.selfIntersects(twoToneFootprint, true)
                && twoToneFootprint.stream().mapToDouble(Point2D::getX).max().orElseThrow() >= 136,
                "joined roof wing survives a central facet rectangle fit");
        BufferedImage longRoof = new BufferedImage(513, 513, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 513; y++) for (int x = 0; x < 513; x++) {
            boolean upper = x >= 230 && x <= 300 && y >= 80 && y <= 370;
            boolean lowerWing = x >= 150 && x < 230 && y >= 300 && y <= 370;
            longRoof.setRGB(x, y, upper || lowerWing ? 0xff4c5057 : 0xff5c8249);
        }
        List<Point2D> longFootprint = BuildingDetector.detect(longRoof, new Point(260, 120));
        check(longFootprint.size() >= 6 && !Geometry.selfIntersects(longFootprint, true)
                && longFootprint.stream().mapToDouble(Point2D::getY).max().orElseThrow() >= 368
                && longFootprint.stream().mapToDouble(Point2D::getX).min().orElseThrow() <= 154,
                "clicking the upper roof retains a lower wing beyond the old crop distance");
        DataSet data = new DataSet();
        data.addPrimitive(a); data.addPrimitive(b); data.addPrimitive(c); data.addPrimitive(d); data.addPrimitive(forest);
        Relation multipolygon = new Relation();
        multipolygon.put("type", "multipolygon");
        multipolygon.addMember(new RelationMember("outer", forest));
        data.addPrimitive(multipolygon);
        Node middle = new Node(new LatLon(0, 0.5));
        Way adjacent = new Way();
        adjacent.setNodes(List.of(middle, b, c));
        SequenceCommand edit = new SequenceCommand("follow", List.of(new AddCommand(data, middle),
                new ChangeNodesCommand(data, forest, List.of(a, middle, b, c, d, a)),
                new AddCommand(data, adjacent)));
        check(edit.executeCommand(), "command executes");
        check(forest.getNode(1) == middle && adjacent.getNode(0) == middle, "shared node identity");
        check("forest".equals(forest.get("landuse")), "tags preserved");
        check(multipolygon.getMember(0).getMember() == forest, "relation member identity preserved");
        edit.undoCommand();
        check(forest.getNodes().equals(List.of(a, b, c, d, a)), "undo restores existing way");
        check(!data.containsNode(middle) && !data.containsWay(adjacent), "undo removes additions");
        check(edit.executeCommand(), "redo executes");
        check(forest.getNode(1) == middle, "redo restores shared geometry");
        if (args.length > 0) {
            try {
                PluginInformation info = new PluginInformation(new File(args[0]));
                check("org.openstreetmap.josm.plugins.smartdraw.SmartDrawPlugin".equals(info.className), "plugin manifest");
            } catch (Exception failure) { throw new AssertionError("JAR plugin metadata", failure); }
        }
        System.out.println("SmartDraw core tests passed");
    }
}
