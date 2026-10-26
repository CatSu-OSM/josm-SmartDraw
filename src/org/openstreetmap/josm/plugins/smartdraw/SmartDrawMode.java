package org.openstreetmap.josm.plugins.smartdraw;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.FlowLayout;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GridLayout;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Shortcut;

/** Freehand map mode with explicit, previewed traversal of compatible OSM ways. */
public final class SmartDrawMode extends MapMode implements MapViewPaintable {
    private enum DrawKind {
        WAY("Way"), AREA("Area"), BUILDING("Building");
        private final String label;
        DrawKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final Color FREE = new Color(255, 145, 30);
    private static final Color FOLLOW = new Color(30, 190, 90);
    private static final Color MAGNETIC = new Color(255, 145, 30);
    private final MapFrame frame;
    private final TagRules rules = new TagRules();
    private final List<TracePoint> draft = new ArrayList<>();
    private final List<TracePoint> followPreview = new ArrayList<>();
    private final List<TracePoint> magneticPreview = new ArrayList<>();
    private List<TracePoint> simplificationPreview = List.of();
    private List<TracePoint> displayedPreview = List.of();
    private final Map<Way, List<Candidate>> pending = new HashMap<>();
    private final Map<KeyStroke, Object> previousKeyBindings = new HashMap<>();
    private final AtomicInteger magneticEpoch = new AtomicInteger();
    private DrawKind kind = DrawKind.WAY;
    private final SmartDrawControls controls = new SmartDrawControls();
    private final KeyEventDispatcher shiftListener;
    private Timer magneticTimer;
    private ExecutorService magneticWorker;
    private BufferedImage dragImagery;
    private Candidate hovered;
    private Candidate followStart;
    private Point cursor;
    private Point pendingBuildingPress;
    private boolean dragging;
    private boolean close;
    private boolean buildingProposal;
    private boolean smartSnap = true;
    private boolean followEnabled = true;
    private boolean magneticEnabled;
    private boolean shiftHeld;
    private boolean overlapWarning = true;
    private boolean forward = true;
    private int snapPixels = 14;
    private int densityPixels = 6;
    private double simplifyPixels = 5;
    private double previewEpsilon;
    private double epsilonMultiplier = 1.1;
    private double maxPointsPerKm = 150;
    private int densityWindow = 10;
    private int confirmDiscardNodes = 10;
    private double confirmDiscardMeters = 100;
    private boolean autoSimplify = true;
    private boolean previewReady;
    private double magneticSensitivity = 12;
    private int magneticRadius = 18;
    private final Shortcut reverseShortcut;

    public SmartDrawMode(MapFrame frame) {
        super("SmartDraw", "draw", "Freehand drawing and boundary following",
                Shortcut.registerShortcut("smartdraw:mode", "Mode: SmartDraw", KeyEvent.VK_G, Shortcut.ALT_SHIFT),
                Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        this.frame = frame;
        shiftListener = event -> {
            if (event.getKeyCode() == KeyEvent.VK_SHIFT) {
                shiftHeld = event.getID() == KeyEvent.KEY_PRESSED;
                if (!shiftHeld && !magneticEnabled && !previewReady) {
                    magneticEpoch.incrementAndGet();
                    magneticPreview.clear();
                    if (magneticTimer != null) magneticTimer.stop();
                    setMagneticStatus("Hold Shift or enable Magnetic");
                    frame.mapView.repaint();
                }
            }
            return false;
        };
        reverseShortcut = Shortcut.registerShortcut("smartdraw:reverse", "SmartDraw: reverse follow direction",
                KeyEvent.VK_R, Shortcut.ALT_CTRL);
        var pref = Config.getPref();
        try { kind = DrawKind.valueOf(pref.get("smartdraw.kind", DrawKind.WAY.name())); }
        catch (IllegalArgumentException ignored) { kind = DrawKind.WAY; }
        smartSnap = pref.getBoolean("smartdraw.snap", true);
        followEnabled = pref.getBoolean("smartdraw.follow", true);
        magneticEnabled = pref.getBoolean("smartdraw.magnetic", false);
        magneticSensitivity = pref.getDouble("smartdraw.magneticSensitivity", 12);
        magneticRadius = pref.getInt("smartdraw.magneticRadius", 18);
        overlapWarning = pref.getBoolean("smartdraw.overlapWarning", true);
        snapPixels = pref.getInt("smartdraw.snapPixels", 14);
        densityPixels = pref.getInt("smartdraw.densityPixels", 6);
        simplifyPixels = pref.getDouble("smartdraw.simplifyPixels", 5);
        epsilonMultiplier = Math.max(1.01, pref.getDouble("smartdraw.epsilonMultiplier", 1.1));
        maxPointsPerKm = Math.max(1, pref.getDouble("smartdraw.maxPointsPerKm", 150));
        densityWindow = Math.max(2, pref.getInt("smartdraw.densityWindow", 10));
        autoSimplify = pref.getBoolean("smartdraw.autoSimplify", true);
        confirmDiscardNodes = Math.max(1, pref.getInt("smartdraw.confirmDiscardNodes", 10));
        confirmDiscardMeters = Math.max(1, pref.getDouble("smartdraw.confirmDiscardMeters", 100));
        rules.configure(pref.get("smartdraw.includes", rules.includes()),
                pref.get("smartdraw.excludes", rules.excludes()),
                pref.getBoolean("smartdraw.unrestricted", false));
        controls.magnetic.setSelected(magneticEnabled);
        controls.kind.setSelectedItem(kind);
    }

    @Override public void enterMode() {
        super.enterMode();
        MapView view = frame.mapView;
        view.addMouseListener(this);
        view.addMouseMotionListener(this);
        view.addTemporaryLayer(this);
        frame.addTopPanel(controls);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(shiftListener);
        setMagneticStatus(idleStatus());
        magneticWorker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "SmartDraw magnetic tracer");
            thread.setDaemon(true);
            return thread;
        });
        magneticTimer = new Timer(100, event -> calculateMagnetic());
        magneticTimer.setRepeats(false);
        bind(view, "ENTER", "smartdraw.finish", this::enterOrFinish);
        bind(view, "ESCAPE", "smartdraw.cancel", this::confirmCancel);
        bind(view, "BACK_SPACE", "smartdraw.back", this::back);
        bind(view, "UP", "smartdraw.more-detail", () -> adjustSimplification(false));
        bind(view, "DOWN", "smartdraw.less-detail", () -> adjustSimplification(true));
        bind(view, "Q", "smartdraw.options", this::options);
        bind(view, "M", "smartdraw.magnetic", () -> setMagneticEnabled(!magneticEnabled));
        bind(view, reverseShortcut.getKeyStroke(), "smartdraw.reverse", () -> { forward = !forward; refreshFollow(); });
    }

    private void bind(MapView view, String key, String action, Runnable runnable) {
        bind(view, KeyStroke.getKeyStroke(key), action, runnable);
    }

    private void bind(MapView view, KeyStroke key, String action, Runnable runnable) {
        previousKeyBindings.putIfAbsent(key, view.getInputMap(JComponent.WHEN_FOCUSED).get(key));
        view.getInputMap(JComponent.WHEN_FOCUSED).put(key, action);
        view.getActionMap().put(action, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { runnable.run(); }
        });
    }

    @Override public void exitMode() {
        cancel();
        magneticTimer.stop();
        magneticWorker.shutdownNow();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(shiftListener);
        frame.removeTopPanel(SmartDrawControls.class);
        MapView view = frame.mapView;
        view.removeMouseListener(this);
        view.removeMouseMotionListener(this);
        view.removeTemporaryLayer(this);
        for (Map.Entry<KeyStroke, Object> entry : previousKeyBindings.entrySet()) {
            if (entry.getValue() == null) view.getInputMap(JComponent.WHEN_FOCUSED).remove(entry.getKey());
            else view.getInputMap(JComponent.WHEN_FOCUSED).put(entry.getKey(), entry.getValue());
        }
        previousKeyBindings.clear();
        for (String action : new String[] {"finish", "cancel", "back", "reverse",
                "more-detail", "less-detail", "options", "magnetic"}) {
            view.getActionMap().remove("smartdraw." + action);
        }
        super.exitMode();
    }

    @Override public String getModeHelpText() {
        return "Drag/click: draw | Building: double-click roof | Alt-click: follow | " + reverseShortcut.getKeyText()
                + ": reverse | Enter: simplify/save | Up/Down: detail | M: magnetic | Q: options | Backspace: edit";
    }

    @Override public void mousePressed(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1 || layer() == null) return;
        if (previewReady) invalidateSimplificationPreview();
        frame.mapView.requestFocusInWindow();
        if (kind == DrawKind.BUILDING && e.getClickCount() >= 2 && draft.size() <= 1) {
            proposeBuilding(e.getPoint());
            return;
        }
        if (kind == DrawKind.BUILDING && draft.isEmpty() && e.getClickCount() == 1
                && !e.isAltDown() && !e.isControlDown()) {
            pendingBuildingPress = e.getPoint();
            dragging = true;
            return;
        }
        cursor = e.getPoint();
        updateCandidate(e);
        if (magneticActive(e) && !e.isAltDown() && !e.isControlDown() && acceptMagnetic(e)) {
            dragging = false;
            dragImagery = null;
            frame.mapView.repaint();
            return;
        }
        if (e.isAltDown() && followEnabled) {
            if (hovered == null || hovered.way == null) return;
            if (followStart == null || followStart.way != hovered.way) {
                if (followStart != null && !draft.isEmpty() && draft.get(draft.size() - 1).node == followStart.node) {
                    draft.remove(draft.size() - 1);
                    invalidateSimplificationPreview();
                }
                followStart = hovered.materialize();
                if (draft.isEmpty() || !same(draft.get(draft.size() - 1), followStart.node)) {
                    add(new TracePoint(followStart.node, FOLLOW, true));
                }
            } else {
                hovered = hovered.materialize();
                refreshFollow();
                if (followPreview.size() < 2) return;
                for (int i = 1; i < followPreview.size(); i++) add(followPreview.get(i));
                List<Candidate> inserts = pending.computeIfAbsent(hovered.way, ignored -> new ArrayList<>());
                if (followStart.segment >= 0 && !inserts.contains(followStart)) inserts.add(followStart);
                if (hovered.segment >= 0 && !inserts.contains(hovered)) inserts.add(hovered);
                followStart = null;
                followPreview.clear();
            }
            frame.mapView.repaint();
            return;
        }
        dragging = true;
        magneticEpoch.incrementAndGet();
        magneticPreview.clear();
        if (magneticTimer != null) magneticTimer.stop();
        if (magneticActive(e) && !e.isControlDown()) {
            dragImagery = captureDragImagery();
            appendMagneticAt(e);
        } else {
            appendAt(e);
        }
    }

    @Override public void mouseDragged(MouseEvent e) {
        if (!dragging) return;
        if (pendingBuildingPress != null) {
            if (pendingBuildingPress.distance(e.getPoint()) < 5) return;
            Point press = pendingBuildingPress;
            pendingBuildingPress = null;
            add(new TracePoint(frame.mapView.getLatLon(press.x, press.y), FREE, false));
        }
        cursor = e.getPoint();
        if (magneticActive(e) && !e.isControlDown()) {
            if (dragImagery == null) dragImagery = captureDragImagery();
            appendMagneticAt(e);
        }
        else appendAt(e);
    }

    @Override public void mouseReleased(MouseEvent e) {
        if (pendingBuildingPress != null) {
            pendingBuildingPress = null;
            dragging = false;
            return;
        }
        if (dragging && magneticActive(e) && !e.isControlDown()) appendMagneticAt(e);
        dragImagery = null;
        dragging = false;
    }

    @Override public void mouseMoved(MouseEvent e) {
        if (previewReady) return;
        cursor = e.getPoint();
        updateCandidate(e);
        refreshFollow();
        requestMagnetic(e);
        frame.mapView.repaint();
    }

    private boolean magneticActive(MouseEvent e) { return magneticEnabled || shiftHeld || e.isShiftDown(); }

    private void setMagneticStatus(String message) { controls.status.setText(message); }

    private void setMagneticEnabled(boolean enabled) {
        magneticEnabled = enabled;
        controls.magnetic.setSelected(enabled);
        Config.getPref().putBoolean("smartdraw.magnetic", enabled);
        if (!enabled) {
            magneticEpoch.incrementAndGet();
            magneticPreview.clear();
            dragImagery = null;
            if (magneticTimer != null) magneticTimer.stop();
        }
        if (previewReady) showSimplificationStatus();
        else setMagneticStatus(enabled ? "Magnetic on: move along the imagery edge"
                : "Magnetic off: press M or hold Shift to trace imagery");
        frame.mapView.repaint();
        frame.mapView.requestFocusInWindow();
    }

    private String idleStatus() {
        if (kind == DrawKind.BUILDING) return "Double-click a roof to propose a building outline";
        if (kind == DrawKind.AREA) return "Draw an area; Enter previews simplification, Enter again saves";
        return "Draw a way; Enter previews simplification, Enter again saves";
    }

    private ImageryLayer visibleImagery() {
        for (ImageryLayer candidate : MainApplication.getLayerManager().getLayersOfType(ImageryLayer.class)) {
            if (candidate.isVisible()) return candidate;
        }
        return null;
    }

    private BufferedImage captureImagery(MapView view, ImageryLayer imagery, Rectangle area) {
        BufferedImage image = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.translate(-area.x, -area.y);
            graphics.setClip(area);
            view.paintLayer(imagery, graphics);
            return image;
        } catch (RuntimeException failure) {
            return null;
        } finally {
            graphics.dispose();
        }
    }

    private BufferedImage captureDragImagery() {
        MapView view = frame.mapView;
        ImageryLayer imagery = visibleImagery();
        int width = view.getWidth(), height = view.getHeight();
        if (imagery == null || width < 3 || height < 3 || (long) width * height > 16_000_000) return null;
        return captureImagery(view, imagery, new Rectangle(0, 0, width, height));
    }

    private void proposeBuilding(Point click) {
        cancel();
        MapView view = frame.mapView;
        ImageryLayer imagery = visibleImagery();
        if (imagery == null) { setMagneticStatus("Building detection needs visible imagery"); return; }
        Rectangle area = new Rectangle(click.x - 256, click.y - 256, 513, 513)
                .intersection(new Rectangle(0, 0, view.getWidth(), view.getHeight()));
        if (area.width < 20 || area.height < 20) return;
        BufferedImage image = captureImagery(view, imagery, area);
        if (image == null) { setMagneticStatus("Could not read imagery at the building"); return; }
        int epoch = magneticEpoch.get();
        setMagneticStatus("Detecting roof outline...");
        magneticWorker.submit(() -> {
            List<Point2D> corners = BuildingDetector.detect(image,
                    new Point(click.x - area.x, click.y - area.y));
            SwingUtilities.invokeLater(() -> {
                if (magneticEpoch.get() != epoch || kind != DrawKind.BUILDING || layer() == null) return;
                if (corners.isEmpty()) {
                    setMagneticStatus("No clear roof boundary found; draw this building manually");
                    return;
                }
                for (Point2D corner : corners) {
                    draft.add(new TracePoint(view.getLatLon(corner.getX() + area.x,
                            corner.getY() + area.y), FREE, true));
                }
                close = false;
                buildingProposal = true;
                setMagneticStatus("Building outline proposed; Enter confirms or Escape cancels");
                view.repaint();
            });
        });
    }

    private int magneticSpanLimit(MapView view) {
        return Math.max(140, view.getWidth() / 2);
    }

    private long magneticCropLimit(MapView view, int margin) {
        long side = magneticSpanLimit(view) + 2L * margin + 1;
        return side * side;
    }

    private int magneticTraceRadius(double span, int snapRadius) {
        return Math.min(120, Math.max(snapRadius, (int) Math.ceil(span * 0.35)));
    }

    private void appendMagneticAt(MouseEvent e) {
        if (dragImagery == null) {
            setMagneticStatus("Imagery unavailable; move to a visible imagery area");
            return;
        }
        MapView view = frame.mapView;
        int dragRadius = Math.max(32, magneticRadius);
        Point snapped = MagneticTracer.snap(dragImagery, e.getPoint(), dragRadius);
        if (snapped == null) {
            setMagneticStatus("No detectable imagery edge within " + dragRadius + " px of the cursor");
            return;
        }
        if (draft.isEmpty()) {
            add(new TracePoint(view.getLatLon(snapped.x, snapped.y), MAGNETIC, false));
            setMagneticStatus("Drag along the imagery edge");
            return;
        }
        Point anchor = view.getPoint(draft.get(draft.size() - 1).location());
        if (anchor.distance(snapped) < Math.max(densityPixels, 10)) return;
        if (anchor.distance(snapped) > magneticSpanLimit(view)) {
            setMagneticStatus("Move closer to the last traced point; gap was not connected");
            return;
        }
        int traceRadius = magneticTraceRadius(anchor.distance(snapped), dragRadius);
        int margin = Math.max(8, traceRadius + 3);
        Rectangle area = new Rectangle(Math.min(anchor.x, snapped.x) - margin,
                Math.min(anchor.y, snapped.y) - margin,
                Math.abs(anchor.x - snapped.x) + margin * 2 + 1,
                Math.abs(anchor.y - snapped.y) + margin * 2 + 1);
        area = area.intersection(new Rectangle(0, 0, dragImagery.getWidth(), dragImagery.getHeight()));
        List<Point> path = List.of();
        if (area.width >= 3 && area.height >= 3
                && (long) area.width * area.height <= magneticCropLimit(view, margin)) {
            path = MagneticTracer.trace(dragImagery.getSubimage(area.x, area.y, area.width, area.height),
                    new Point(anchor.x - area.x, anchor.y - area.y),
                    new Point(snapped.x - area.x, snapped.y - area.y),
                    Math.max(magneticSensitivity, 12), traceRadius, () -> false);
        }
        if (path.size() > 1) {
            List<Point2D> pixels = new ArrayList<>();
            for (Point point : path) pixels.add(new Point2D.Double(point.x + area.x, point.y + area.y));
            List<Point2D> simplified = Geometry.simplify(Geometry.smooth(pixels, 3), 2.5);
            List<Point2D> existing = new ArrayList<>();
            for (TracePoint point : draft) existing.add(view.getPoint2D(point.location()));
            for (int i = 1; i < simplified.size(); i++) {
                Point2D point = simplified.get(i);
                if (!Geometry.canExtendWithoutCrossing(existing, point, 3)) {
                    setMagneticStatus("Trace would cross the draft; move back along the boundary");
                    return;
                }
                add(new TracePoint(view.getLatLon(point.getX(), point.getY()), MAGNETIC, false));
                existing.add(point);
            }
            setMagneticStatus("Snapping to imagery edge while dragging");
        } else {
            setMagneticStatus("Could not follow the edge to this point; drag closer along the boundary");
        }
    }

    private void requestMagnetic(MouseEvent e) {
        magneticPreview.clear();
        magneticEpoch.incrementAndGet();
        if (magneticTimer == null) return;
        magneticTimer.stop();
        if (draft.isEmpty()) {
            setMagneticStatus(idleStatus());
            frame.mapView.repaint(); return;
        }
        if (followStart != null || !magneticActive(e)) {
            setMagneticStatus("Hold Shift or enable Magnetic");
            frame.mapView.repaint(); return;
        }
        Point anchor = frame.mapView.getPoint(draft.get(draft.size() - 1).location());
        if (anchor.distance(e.getPoint()) < densityPixels) { setMagneticStatus("Move away from the anchor"); return; }
        if (anchor.distance(e.getPoint()) > magneticSpanLimit(frame.mapView)) {
            setMagneticStatus("Move closer to the anchor (under half the map width)"); return;
        }
        setMagneticStatus("Analyzing imagery...");
        magneticTimer.restart();
    }

    private void calculateMagnetic() {
        if (draft.isEmpty() || cursor == null || magneticWorker == null) return;
        MapView view = frame.mapView;
        int epoch = magneticEpoch.get();
        Point anchor = view.getPoint(draft.get(draft.size() - 1).location());
        Point goal = new Point(cursor);
        int traceRadius = magneticTraceRadius(anchor.distance(goal), magneticRadius);
        int margin = Math.max(8, traceRadius + 3);
        Rectangle area = new Rectangle(Math.min(anchor.x, goal.x) - margin,
                Math.min(anchor.y, goal.y) - margin,
                Math.abs(anchor.x - goal.x) + margin * 2 + 1,
                Math.abs(anchor.y - goal.y) + margin * 2 + 1);
        area = area.intersection(new Rectangle(0, 0, view.getWidth(), view.getHeight()));
        if (area.width < 3 || area.height < 3
                || (long) area.width * area.height > magneticCropLimit(view, margin)) {
            setMagneticStatus("Shorten the span or reduce attraction radius"); return;
        }
        ImageryLayer imagery = visibleImagery();
        if (imagery == null) { setMagneticStatus("No visible imagery layer"); return; }
        BufferedImage image = captureImagery(view, imagery, area);
        if (image == null) { setMagneticStatus("Imagery preview unavailable"); return; }
        Point localAnchor = new Point(anchor.x - area.x, anchor.y - area.y);
        Point localGoal = new Point(goal.x - area.x, goal.y - area.y);
        Rectangle crop = area;
        magneticWorker.submit(() -> {
            List<Point> path = MagneticTracer.trace(image, localAnchor, localGoal,
                    magneticSensitivity, traceRadius, () -> magneticEpoch.get() != epoch || Thread.currentThread().isInterrupted());
            SwingUtilities.invokeLater(() -> {
                if (magneticEpoch.get() != epoch) return;
                magneticPreview.clear();
                if (path.size() > 1) {
                    List<Point2D> pixels = new ArrayList<>();
                    for (Point p : path) pixels.add(new Point2D.Double(p.x + crop.x, p.y + crop.y));
                    for (Point2D p : Geometry.simplify(pixels, 1.5)) {
                        magneticPreview.add(new TracePoint(view.getLatLon(p.getX(), p.getY()), MAGNETIC, false));
                    }
                }
                setMagneticStatus(magneticPreview.size() > 1 ? "Orange path ready: click to accept"
                        : "No usable edge here; move the cursor or click to draw manually");
                view.repaint();
            });
        });
    }

    private boolean acceptMagnetic(MouseEvent e) {
        if (magneticPreview.size() < 2 || draft.isEmpty()) return false;
        MapView view = frame.mapView;
        if (view.getPoint2D(magneticPreview.get(magneticPreview.size() - 1).location()).distance(e.getPoint()) > 8) return false;
        if (view.getPoint2D(magneticPreview.get(0).location()).distance(view.getPoint2D(draft.get(draft.size() - 1).location())) > 4) return false;
        for (int i = 1; i < magneticPreview.size(); i++) add(magneticPreview.get(i));
        magneticPreview.clear();
        magneticEpoch.incrementAndGet();
        setMagneticStatus("Magnetic path added");
        return true;
    }

    private void appendAt(MouseEvent e) {
        TracePoint next;
        if (hovered != null && hovered.segment < 0 && smartSnap && !e.isControlDown()) {
            next = new TracePoint(hovered.node, FREE, true);
        } else {
            next = new TracePoint(frame.mapView.getLatLon(e.getX(), e.getY()), FREE, e.isControlDown());
        }
        if (!draft.isEmpty()) {
            TracePoint last = draft.get(draft.size() - 1);
            if (same(last, next.node)) return;
            Point2D a = frame.mapView.getPoint2D(last.location());
            if (draft.size() >= 3 && frame.mapView.getPoint2D(draft.get(0).location()).distance(e.getPoint()) < snapPixels) {
                invalidateSimplificationPreview();
                close = true;
                frame.mapView.repaint();
                return;
            }
            if (a.distance(e.getPoint()) < densityPixels) return;
        }
        add(next);
    }

    private boolean same(TracePoint point, Node node) { return node != null && point.node == node; }

    private void add(TracePoint point) {
        invalidateSimplificationPreview();
        if (draft.isEmpty() || draft.get(draft.size() - 1).node != point.node || point.node == null) draft.add(point);
        close = false;
        buildingProposal = false;
        if (draft.size() == 1) setMagneticStatus("Anchor placed: drag along the edge or draw manually");
        frame.mapView.repaint();
    }

    private void updateCandidate(MouseEvent e) {
        hovered = null;
        if (layer() == null || (!smartSnap && !e.isAltDown()) || e.isControlDown()) return;
        MapView view = frame.mapView;
        Point p = e.getPoint();
        LatLon a = view.getLatLon(p.x - snapPixels, p.y - snapPixels);
        LatLon b = view.getLatLon(p.x + snapPixels, p.y + snapPixels);
        DataSet ds = layer().data;
        double best = snapPixels;
        for (Way way : ds.searchWays(new BBox(a, b))) {
            if (!rules.eligible(way)) continue;
            List<Node> nodes = way.getNodes();
            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                if (!node.isUsable()) continue;
                double distance = view.getPoint2D(node).distance(p);
                if (distance <= best) { best = distance; hovered = new Candidate(way, node); }
                if (i == 0 || (!e.isAltDown() && followStart == null)) continue;
                Point2D start = view.getPoint2D(nodes.get(i - 1));
                Point2D end = view.getPoint2D(node);
                double dx = end.getX() - start.getX(), dy = end.getY() - start.getY();
                double length2 = dx * dx + dy * dy;
                if (length2 == 0) continue;
                double t = ((p.x - start.getX()) * dx + (p.y - start.getY()) * dy) / length2;
                if (t <= 0.01 || t >= 0.99) continue;
                double distanceToSegment = Geometry.segmentDistance(p, start, end);
                if (distanceToSegment < best && best > 5 && distanceToSegment <= snapPixels) {
                    best = distanceToSegment;
                    hovered = new Candidate(way, i - 1, t,
                            nodes.get(i - 1).getCoor().interpolate(node.getCoor(), t));
                }
            }
            for (Candidate insertion : pending.getOrDefault(way, List.of())) {
                double distance = view.getPoint2D(insertion.node).distance(p);
                if (distance <= best) { best = distance; hovered = insertion; }
            }
        }
        if (followStart == null && !e.isAltDown()) {
            for (Node node : ds.searchNodes(new BBox(a, b))) {
                if (!node.isUsable() || !node.isSelectable()) continue;
                List<Way> parents = node.getParentWays();
                if (!parents.isEmpty() && parents.stream().noneMatch(rules::eligible)) continue;
                double distance = view.getPoint2D(node).distance(p);
                if (distance <= best) { best = distance; hovered = new Candidate(null, node); }
            }
        }
    }

    private void refreshFollow() {
        followPreview.clear();
        if (followStart == null || hovered == null || followStart.way != hovered.way) return;
        Way way = hovered.way;
        List<Candidate> insertions = new ArrayList<>(pending.getOrDefault(way, List.of()));
        if (followStart.segment >= 0 && !insertions.contains(followStart)) insertions.add(followStart);
        Candidate endpoint = hovered.node == null ? hovered.materialize() : hovered;
        if (endpoint.segment >= 0 && !insertions.contains(endpoint)) insertions.add(endpoint);
        List<Node> virtual = virtualNodes(way, insertions);
        int start = virtual.indexOf(followStart.node);
        int end = virtual.indexOf(endpoint.node);
        if (start < 0 || end < 0) return;
        boolean direction = way.isClosed() ? forward : start <= end;
        try {
            for (Node node : FollowPath.between(virtual, way.isClosed(), start, end, direction)) {
                followPreview.add(new TracePoint(node, FOLLOW, true));
            }
        } catch (IllegalArgumentException ignored) { followPreview.clear(); }
    }

    private static List<Node> virtualNodes(Way way, List<Candidate> insertions) {
        List<Node> result = new ArrayList<>();
        List<Candidate> ordered = new ArrayList<>(insertions);
        ordered.sort(Comparator.comparingInt((Candidate c) -> c.segment).thenComparingDouble(c -> c.t));
        int next = 0;
        for (int i = 0; i < way.getNodesCount() - 1; i++) {
            result.add(way.getNode(i));
            while (next < ordered.size() && ordered.get(next).segment == i) result.add(ordered.get(next++).node);
        }
        result.add(way.lastNode());
        return result;
    }

    private void back() {
        if (previewReady) {
            invalidateSimplificationPreview();
            setMagneticStatus("Simplification preview closed; edit the draft or press Enter again");
            frame.mapView.repaint();
            return;
        }
        if (followStart != null) {
            if (!draft.isEmpty() && draft.get(draft.size() - 1).node == followStart.node) draft.remove(draft.size() - 1);
            followStart = null; followPreview.clear();
        }
        else if (!draft.isEmpty()) draft.remove(draft.size() - 1);
        close = false;
        buildingProposal = false;
        frame.mapView.repaint();
    }

    private void cancel() {
        invalidateSimplificationPreview();
        draft.clear(); followPreview.clear(); magneticPreview.clear(); pending.clear(); followStart = null; close = false;
        buildingProposal = false;
        pendingBuildingPress = null;
        dragImagery = null;
        magneticEpoch.incrementAndGet();
        if (magneticTimer != null) magneticTimer.stop();
        setMagneticStatus(idleStatus());
        frame.mapView.repaint();
    }

    private void confirmCancel() {
        List<LatLon> points = new ArrayList<>(draft.size());
        for (TracePoint point : draft) points.add(point.location());
        if (!DraftDiscardPolicy.needsConfirmation(points, confirmDiscardNodes, confirmDiscardMeters)) {
            cancel();
            return;
        }
        Object[] choices = {"Keep drawing", "Discard draft"};
        int choice = JOptionPane.showOptionDialog(frame,
                String.format(Locale.ROOT, "Discard this unsaved draft? It has %d nodes and is about %.0f m long.",
                        points.size(), DraftDiscardPolicy.lengthMeters(points)),
                "Discard SmartDraw draft?", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, choices, choices[0]);
        if (choice == 1) cancel();
        else frame.mapView.requestFocusInWindow();
    }

    private void invalidateSimplificationPreview() {
        previewReady = false;
        simplificationPreview = List.of();
        displayedPreview = List.of();
    }

    private void enterOrFinish() {
        if (kind != DrawKind.BUILDING && !previewReady) {
            if (draft.size() >= 2 && followStart == null) prepareSimplificationPreview();
            return;
        }
        finish();
    }

    private void prepareSimplificationPreview() {
        magneticEpoch.incrementAndGet();
        magneticPreview.clear();
        if (magneticTimer != null) magneticTimer.stop();
        previewEpsilon = Math.max(0.001, simplifyPixels);
        refreshSimplificationPreview();
        int protectedNodes = Math.min(2, draft.size());
        for (int i = 1; i + 1 < draft.size(); i++) if (draft.get(i).fixed) protectedNodes++;
        if (autoSimplify) {
            for (int attempt = 0; attempt < 80
                    && previewEpsilon < 1000
                    && simplificationPreview.size() > protectedNodes
                    && pointsPerKm(simplificationPreview) > maxPointsPerKm; attempt++) {
                previewEpsilon *= epsilonMultiplier;
                refreshSimplificationPreview();
            }
        }
        refreshDisplayedPreview();
        previewReady = true;
        showSimplificationStatus();
        frame.mapView.repaint();
    }

    private void refreshSimplificationPreview() {
        simplificationPreview = simplified(previewEpsilon);
    }

    private void refreshDisplayedPreview() {
        displayedPreview = simplificationPreview;
        if (kind == DrawKind.AREA && layer() != null) {
            Map<Way, List<Candidate>> previewInsertions = new HashMap<>();
            for (Map.Entry<Way, List<Candidate>> entry : pending.entrySet())
                previewInsertions.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            displayedPreview = closeAlongExistingWay(
                    attachAreaEndpoints(simplificationPreview, previewInsertions), previewInsertions);
        }
    }

    private double pointsPerKm(List<TracePoint> points) {
        List<LatLon> coordinates = new ArrayList<>();
        for (TracePoint point : points) coordinates.add(point.location());
        return SimplificationStats.maxPointsPerKm(coordinates, densityWindow);
    }

    private void showSimplificationStatus() {
        setMagneticStatus(String.format(Locale.ROOT,
                "Eps %.2f px | %d nodes | %.0f p/km | Up: more, Down: fewer, Enter: save, Backspace: edit",
                previewEpsilon, displayedPreview.size(), pointsPerKm(displayedPreview)));
    }

    private void adjustSimplification(boolean fewerNodes) {
        if (!previewReady) {
            int previous = densityPixels;
            densityPixels = fewerNodes ? (int) Math.ceil(densityPixels * epsilonMultiplier)
                    : (int) Math.floor(densityPixels / epsilonMultiplier);
            if (densityPixels == previous) densityPixels += fewerNodes ? 1 : -1;
            densityPixels = Math.max(2, Math.min(80, densityPixels));
            Config.getPref().putInt("smartdraw.densityPixels", densityPixels);
            setMagneticStatus("Minimum point spacing: " + densityPixels + " px | Up: more, Down: fewer");
            return;
        }
        previewEpsilon = Math.max(0.001, Math.min(1000,
                fewerNodes ? previewEpsilon * epsilonMultiplier : previewEpsilon / epsilonMultiplier));
        refreshSimplificationPreview();
        refreshDisplayedPreview();
        showSimplificationStatus();
        frame.mapView.repaint();
    }

    private void finish() {
        OsmDataLayer layer = layer();
        if (layer == null || draft.size() < 2 || followStart != null) return;
        boolean closed = kind != DrawKind.WAY || close;
        List<TracePoint> result = previewReady ? new ArrayList<>(simplificationPreview) : simplified(simplifyPixels);
        Map<Way, List<Candidate>> finishPending = new HashMap<>();
        for (Map.Entry<Way, List<Candidate>> entry : pending.entrySet())
            finishPending.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        if (kind == DrawKind.AREA) {
            result = attachAreaEndpoints(result, finishPending);
            result = closeAlongExistingWay(result, finishPending);
        }
        if (result.size() < 2) return;
        if (closed && result.size() < 3) {
            JOptionPane.showMessageDialog(frame, "A closed area needs at least three corners.",
                    "SmartDraw", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<Point2D> screen = new ArrayList<>();
        for (TracePoint point : result) screen.add(frame.mapView.getPoint2D(point.location()));
        if (Geometry.selfIntersects(screen, closed)) {
            JOptionPane.showMessageDialog(frame, "The draft crosses itself. Adjust the geometry before finishing.",
                    "SmartDraw", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (closed) {
            double twiceArea = 0;
            for (int i = 0; i < screen.size(); i++) {
                Point2D p = screen.get(i), q = screen.get((i + 1) % screen.size());
                twiceArea += p.getX() * q.getY() - q.getX() * p.getY();
            }
            if (Math.abs(twiceArea) < 2) {
                JOptionPane.showMessageDialog(frame, "The closed area has no usable area.",
                        "SmartDraw", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        if (closed && overlapWarning && overlaps(result, layer.data) &&
                JOptionPane.showConfirmDialog(frame, "This area overlaps an existing closed way. Create it anyway?",
                    "SmartDraw overlap", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        List<Node> nodes = new ArrayList<>();
        List<Command> commands = new ArrayList<>();
        java.util.Set<Node> added = new java.util.HashSet<>();
        for (TracePoint point : result) {
            Node node = point.node == null ? new Node(point.location()) : point.node;
            if (nodes.isEmpty() || nodes.get(nodes.size() - 1) != node) nodes.add(node);
            if (node.getDataSet() == null && added.add(node)) commands.add(new AddCommand(layer.data, node));
        }
        for (Map.Entry<Way, List<Candidate>> entry : finishPending.entrySet()) {
            List<Candidate> usedInsertions = new ArrayList<>();
            for (Candidate candidate : entry.getValue()) {
                if (nodes.contains(candidate.node)) usedInsertions.add(candidate);
            }
            if (usedInsertions.isEmpty()) continue;
            for (Candidate candidate : usedInsertions) {
                if (added.add(candidate.node)) commands.add(new AddCommand(layer.data, candidate.node));
            }
            commands.add(new ChangeNodesCommand(layer.data, entry.getKey(), virtualNodes(entry.getKey(), usedInsertions)));
        }
        if (closed && nodes.size() >= 3 && nodes.get(0) != nodes.get(nodes.size() - 1)) nodes.add(nodes.get(0));
        if (nodes.size() < 2) return;
        Way way = new Way();
        way.setNodes(nodes);
        if (kind == DrawKind.BUILDING) way.put("building", "yes");
        commands.add(new AddCommand(layer.data, way));
        UndoRedoHandler.getInstance().add(new SequenceCommand("SmartDraw way", commands));
        layer.data.setSelected(way);
        cancel();
    }

    private List<TracePoint> simplified(double epsilon) {
        List<Point> screen = new ArrayList<>(draft.size());
        List<Boolean> fixed = new ArrayList<>(draft.size());
        for (TracePoint point : draft) {
            screen.add(frame.mapView.getPoint(point.location()));
            fixed.add(point.fixed);
        }
        List<TracePoint> output = new ArrayList<>();
        for (int index : FastDrawSimplifier.keptIndices(screen, fixed, epsilon)) output.add(draft.get(index));
        return output;
    }

    private List<TracePoint> attachAreaEndpoints(List<TracePoint> points,
            Map<Way, List<Candidate>> insertions) {
        if (points.size() < 2) return points;
        List<TracePoint> attached = new ArrayList<>(points);
        for (int index : new int[] {0, attached.size() - 1}) {
            if (attached.get(index).node != null) continue;
            Candidate nearby = nearestExistingEdge(attached.get(index).location(), insertions);
            if (nearby == null) continue;
            Candidate endpoint = nearby.materialize();
            attached.set(index, new TracePoint(endpoint.node, FOLLOW, true));
            if (endpoint.segment >= 0) {
                List<Candidate> wayInsertions = insertions.computeIfAbsent(endpoint.way, ignored -> new ArrayList<>());
                if (!wayInsertions.contains(endpoint)) wayInsertions.add(endpoint);
            }
        }
        return attached;
    }

    private Candidate nearestExistingEdge(LatLon location, Map<Way, List<Candidate>> insertions) {
        MapView view = frame.mapView;
        Point2D p = view.getPoint2D(location);
        int radius = Math.min(snapPixels, 8);
        LatLon a = view.getLatLon(p.getX() - radius, p.getY() - radius);
        LatLon b = view.getLatLon(p.getX() + radius, p.getY() + radius);
        double best = radius;
        Candidate nearest = null;
        for (Way way : layer().data.searchWays(new BBox(a, b))) {
            if (!rules.eligible(way)) continue;
            List<Node> nodes = way.getNodes();
            for (Node node : nodes) {
                double distance = view.getPoint2D(node).distance(p);
                if (distance <= best) { best = distance; nearest = new Candidate(way, node); }
            }
            for (Candidate candidate : insertions.getOrDefault(way, List.of())) {
                double distance = view.getPoint2D(candidate.node).distance(p);
                if (distance <= best) { best = distance; nearest = candidate; }
            }
            for (int i = 1; i < nodes.size(); i++) {
                Point2D start = view.getPoint2D(nodes.get(i - 1));
                Point2D end = view.getPoint2D(nodes.get(i));
                double dx = end.getX() - start.getX(), dy = end.getY() - start.getY();
                double lengthSquared = dx * dx + dy * dy;
                if (lengthSquared == 0) continue;
                double t = ((p.getX() - start.getX()) * dx + (p.getY() - start.getY()) * dy) / lengthSquared;
                if (t <= 0.01 || t >= 0.99) continue;
                double distance = Geometry.segmentDistance(p, start, end);
                if (distance < best - 0.1) {
                    best = distance;
                    nearest = new Candidate(way, i - 1, t,
                            nodes.get(i - 1).getCoor().interpolate(nodes.get(i).getCoor(), t));
                }
            }
        }
        return nearest;
    }

    /** Complete an area along a shared way when both draft ends use its nodes. */
    private List<TracePoint> closeAlongExistingWay(List<TracePoint> points,
            Map<Way, List<Candidate>> insertions) {
        if (points.size() < 3) return points;
        Node first = points.get(0).node, last = points.get(points.size() - 1).node;
        if (first == null || last == null || first == last) return points;
        Set<Way> ways = new LinkedHashSet<>(insertions.keySet());
        ways.addAll(first.getParentWays());
        ways.addAll(last.getParentWays());
        List<TracePoint> best = points;
        double bestLength = Double.POSITIVE_INFINITY;
        boolean bestOverlaps = true;
        MapView view = frame.mapView;
        for (Way way : ways) {
            if (!rules.eligible(way)) continue;
            List<Node> virtual = virtualNodes(way, insertions.getOrDefault(way, List.of()));
            int from = virtual.indexOf(last), to = virtual.indexOf(first);
            if (way.isClosed() && to == virtual.size() - 1) to = 0;
            if (from < 0 || to < 0) continue;
            for (boolean direction : new boolean[] {true, false}) {
                if (!way.isClosed() && direction != (from <= to)) continue;
                List<Node> path;
                try { path = FollowPath.between(virtual, way.isClosed(), from, to, direction); }
                catch (IllegalArgumentException ignored) { continue; }
                List<TracePoint> candidate = new ArrayList<>(points);
                for (int i = 1; i + 1 < path.size(); i++)
                    candidate.add(new TracePoint(path.get(i), FOLLOW, true));
                List<Point2D> screen = new ArrayList<>();
                for (TracePoint point : candidate) screen.add(view.getPoint2D(point.location()));
                if (Geometry.selfIntersects(screen, true)) continue;
                boolean candidateOverlaps = overlaps(candidate, layer().data);
                double length = 0;
                for (int i = 1; i < path.size(); i++)
                    length += view.getPoint2D(path.get(i - 1)).distance(view.getPoint2D(path.get(i)));
                if ((bestOverlaps && !candidateOverlaps)
                        || (bestOverlaps == candidateOverlaps && length < bestLength)) {
                    bestOverlaps = candidateOverlaps;
                    bestLength = length;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private boolean overlaps(List<TracePoint> result, DataSet data) {
        Path2D path = new Path2D.Double();
        double minLat = Double.POSITIVE_INFINITY, minLon = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
        boolean first = true;
        for (TracePoint point : result) {
            LatLon coord = point.location();
            minLat = Math.min(minLat, coord.lat()); maxLat = Math.max(maxLat, coord.lat());
            minLon = Math.min(minLon, coord.lon()); maxLon = Math.max(maxLon, coord.lon());
            Point2D pixel = frame.mapView.getPoint2D(coord);
            if (first) { path.moveTo(pixel.getX(), pixel.getY()); first = false; }
            else path.lineTo(pixel.getX(), pixel.getY());
        }
        path.closePath();
        Area proposed = new Area(path);
        if (proposed.isEmpty()) return false;
        for (Way way : data.searchWays(new BBox(new LatLon(minLat, minLon), new LatLon(maxLat, maxLon)))) {
            if (!way.isClosed() || !way.isUsable() || way.hasIncompleteNodes()) continue;
            Path2D existing = new Path2D.Double();
            for (int i = 0; i < way.getNodesCount(); i++) {
                Point2D pixel = frame.mapView.getPoint2D(way.getNode(i));
                if (i == 0) existing.moveTo(pixel.getX(), pixel.getY());
                else existing.lineTo(pixel.getX(), pixel.getY());
            }
            existing.closePath();
            Area intersection = new Area(existing);
            intersection.intersect(proposed);
            if (!intersection.isEmpty()) return true;
        }
        return false;
    }

    private OsmDataLayer layer() {
        return MainApplication.getLayerManager().getEditLayer();
    }

    @Override public void paint(Graphics2D graphics, MapView view, org.openstreetmap.josm.data.Bounds bounds) {
        if (draft.isEmpty() && hovered == null && magneticPreview.isEmpty()) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (previewReady) {
                for (int i = 1; i < displayedPreview.size(); i++)
                    draw(g, view, displayedPreview.get(i - 1), displayedPreview.get(i), MAGNETIC);
                if ((kind == DrawKind.AREA || close) && displayedPreview.size() > 2)
                    draw(g, view, displayedPreview.get(displayedPreview.size() - 1),
                            displayedPreview.get(0), MAGNETIC);
                return;
            }
            for (int i = 1; i < draft.size(); i++) draw(g, view, draft.get(i - 1), draft.get(i), draft.get(i).color);
            for (int i = 1; i < followPreview.size(); i++) draw(g, view, followPreview.get(i - 1), followPreview.get(i), FOLLOW);
            for (int i = 1; i < magneticPreview.size(); i++) draw(g, view, magneticPreview.get(i - 1), magneticPreview.get(i), MAGNETIC);
            if (!draft.isEmpty()) {
                Point p = view.getPoint(draft.get(draft.size() - 1).location());
                g.setColor(Color.WHITE); g.fillOval(p.x - 5, p.y - 5, 10, 10);
                g.setColor(FREE); g.fillOval(p.x - 3, p.y - 3, 6, 6);
            }
            if (hovered != null) {
                Point p = view.getPoint(hovered.coordinate);
                g.setColor(FOLLOW); g.drawOval(p.x - 7, p.y - 7, 14, 14);
            }
            if (((kind == DrawKind.WAY && close) || (kind == DrawKind.BUILDING && buildingProposal))
                    && draft.size() > 2)
                draw(g, view, draft.get(draft.size() - 1), draft.get(0), FREE);
        } finally { g.dispose(); }
    }

    private static void draw(Graphics2D g, MapView view, TracePoint a, TracePoint b, Color color) {
        Point p = view.getPoint(a.location()), q = view.getPoint(b.location());
        g.setColor(color); g.drawLine(p.x, p.y, q.x, q.y);
    }

    private void options() {
        JCheckBox snapping = new JCheckBox("Smart snapping", smartSnap);
        JCheckBox following = new JCheckBox("Follow existing ways", followEnabled);
        JCheckBox magnetic = new JCheckBox("Magnetic imagery preview", magneticEnabled);
        JCheckBox overlaps = new JCheckBox("Warn about area overlaps", overlapWarning);
        JCheckBox unrestricted = new JCheckBox("Unrestricted targets (exclusions still apply)", rules.unrestricted());
        JCheckBox autosimplify = new JCheckBox("Auto-simplify to maximum points per km", autoSimplify);
        JTextField includes = new JTextField(rules.includes());
        JTextField excludes = new JTextField(rules.excludes());
        JSpinner snap = new JSpinner(new SpinnerNumberModel(snapPixels, 2, 80, 1));
        JSpinner density = new JSpinner(new SpinnerNumberModel(densityPixels, 2, 80, 1));
        JSpinner simplify = new JSpinner(new SpinnerNumberModel(simplifyPixels, 0.001, 1000.0, 0.5));
        JSpinner multiplier = new JSpinner(new SpinnerNumberModel(epsilonMultiplier, 1.01, 3.0, 0.01));
        JSpinner maxPkm = new JSpinner(new SpinnerNumberModel(maxPointsPerKm, 1.0, 5000.0, 10.0));
        JSpinner pkmWindow = new JSpinner(new SpinnerNumberModel(densityWindow, 2, 100, 1));
        JSpinner discardNodes = new JSpinner(new SpinnerNumberModel(confirmDiscardNodes, 1, 10000, 1));
        JSpinner discardMeters = new JSpinner(new SpinnerNumberModel(confirmDiscardMeters, 1.0, 100000.0, 10.0));
        JSpinner sensitivity = new JSpinner(new SpinnerNumberModel(magneticSensitivity, 0.0, 20.0, 0.5));
        JSpinner radius = new JSpinner(new SpinnerNumberModel(magneticRadius, 4, 40, 1));
        JPanel panel = new JPanel(new GridLayout(0, 1));
        panel.add(snapping); panel.add(following); panel.add(magnetic); panel.add(overlaps);
        panel.add(unrestricted); panel.add(autosimplify);
        panel.add(new JLabel("Magnetic sensitivity")); panel.add(sensitivity);
        panel.add(new JLabel("Magnetic attraction radius (pixels)")); panel.add(radius);
        panel.add(new JLabel("Snap radius (screen pixels)")); panel.add(snap);
        panel.add(new JLabel("Minimum node spacing (screen pixels)")); panel.add(density);
        panel.add(new JLabel("Starting simplification epsilon (screen pixels)")); panel.add(simplify);
        panel.add(new JLabel("Up/Down epsilon multiplier")); panel.add(multiplier);
        panel.add(new JLabel("Maximum points per km")); panel.add(maxPkm);
        panel.add(new JLabel("Points-per-km window (segments)")); panel.add(pkmWindow);
        panel.add(new JLabel("Confirm Esc above this node count")); panel.add(discardNodes);
        panel.add(new JLabel("Confirm Esc above this length (metres)")); panel.add(discardMeters);
        panel.add(new JLabel("Included tags, comma separated")); panel.add(includes);
        panel.add(new JLabel("Excluded tags, comma separated")); panel.add(excludes);
        if (JOptionPane.showConfirmDialog(frame, panel, "SmartDraw options", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            smartSnap = snapping.isSelected(); followEnabled = following.isSelected(); overlapWarning = overlaps.isSelected();
            magneticSensitivity = (Double) sensitivity.getValue(); magneticRadius = (Integer) radius.getValue();
            rules.configure(includes.getText(), excludes.getText(), unrestricted.isSelected());
            snapPixels = (Integer) snap.getValue(); densityPixels = (Integer) density.getValue();
            simplifyPixels = (Double) simplify.getValue();
            epsilonMultiplier = (Double) multiplier.getValue();
            maxPointsPerKm = (Double) maxPkm.getValue();
            densityWindow = (Integer) pkmWindow.getValue();
            confirmDiscardNodes = (Integer) discardNodes.getValue();
            confirmDiscardMeters = (Double) discardMeters.getValue();
            autoSimplify = autosimplify.isSelected();
            var pref = Config.getPref();
            pref.putBoolean("smartdraw.snap", smartSnap);
            pref.putBoolean("smartdraw.follow", followEnabled);
            pref.putBoolean("smartdraw.overlapWarning", overlapWarning);
            pref.putDouble("smartdraw.magneticSensitivity", magneticSensitivity);
            pref.putInt("smartdraw.magneticRadius", magneticRadius);
            pref.putBoolean("smartdraw.unrestricted", rules.unrestricted());
            pref.putInt("smartdraw.snapPixels", snapPixels);
            pref.putInt("smartdraw.densityPixels", densityPixels);
            pref.putDouble("smartdraw.simplifyPixels", simplifyPixels);
            pref.putDouble("smartdraw.epsilonMultiplier", epsilonMultiplier);
            pref.putDouble("smartdraw.maxPointsPerKm", maxPointsPerKm);
            pref.putInt("smartdraw.densityWindow", densityWindow);
            pref.putBoolean("smartdraw.autoSimplify", autoSimplify);
            pref.putInt("smartdraw.confirmDiscardNodes", confirmDiscardNodes);
            pref.putDouble("smartdraw.confirmDiscardMeters", confirmDiscardMeters);
            pref.put("smartdraw.includes", rules.includes());
            pref.put("smartdraw.excludes", rules.excludes());
            setMagneticEnabled(magnetic.isSelected());
            if (previewReady) prepareSimplificationPreview();
            frame.mapView.requestFocusInWindow();
        }
    }

    private static final class Candidate {
        final Way way; final Node node; final int segment; final double t; final LatLon coordinate;
        Candidate(Way way, Node node) {
            this.way = way; this.node = node; this.segment = -1; this.t = -1; this.coordinate = node.getCoor();
        }
        Candidate(Way way, int segment, double t, LatLon coordinate) {
            this(way, null, segment, t, coordinate);
        }
        private Candidate(Way way, Node node, int segment, double t, LatLon coordinate) {
            this.way = way; this.node = node; this.segment = segment; this.t = t; this.coordinate = coordinate;
        }
        Candidate materialize() {
            return node != null ? this : new Candidate(way, new Node(coordinate), segment, t, coordinate);
        }
    }

    private final class SmartDrawControls extends JPanel {
        final JLabel status = new JLabel();
        final JCheckBox magnetic = new JCheckBox("Magnetic");
        final JComboBox<DrawKind> kind = new JComboBox<>(DrawKind.values());
        SmartDrawControls() {
            super(new FlowLayout(FlowLayout.LEFT, 8, 2));
            add(new JLabel("SmartDraw"));
            kind.addActionListener(event -> {
                DrawKind selected = (DrawKind) kind.getSelectedItem();
                if (selected == null || selected == SmartDrawMode.this.kind) return;
                if (!draft.isEmpty()) {
                    kind.setSelectedItem(SmartDrawMode.this.kind);
                    setMagneticStatus("Finish or cancel the current draft before switching modes");
                    return;
                }
                SmartDrawMode.this.kind = selected;
                Config.getPref().put("smartdraw.kind", selected.name());
                setMagneticStatus(selected == DrawKind.BUILDING
                        ? "Double-click a roof to propose a building outline"
                        : selected == DrawKind.AREA ? "Area closes after the simplification preview is saved"
                        : "Way remains open after the simplification preview is saved");
                frame.mapView.requestFocusInWindow();
            });
            add(kind);
            magnetic.addActionListener(event -> {
                setMagneticEnabled(magnetic.isSelected());
            });
            add(magnetic);
            JButton optionsButton = new JButton("Options...");
            optionsButton.addActionListener(event -> options());
            add(optionsButton);
            JButton reverseButton = new JButton("Reverse follow");
            reverseButton.addActionListener(event -> {
                forward = !forward;
                refreshFollow();
                frame.mapView.repaint();
                frame.mapView.requestFocusInWindow();
            });
            add(reverseButton);
            add(status);
        }
    }

    private static final class TracePoint {
        final Node node; final LatLon coordinate; final Color color; final boolean fixed;
        TracePoint(Node node, Color color, boolean fixed) {
            this.node = Objects.requireNonNull(node); this.coordinate = null; this.color = color; this.fixed = fixed;
        }
        TracePoint(LatLon coordinate, Color color, boolean fixed) {
            this.node = null; this.coordinate = Objects.requireNonNull(coordinate); this.color = color; this.fixed = fixed;
        }
        LatLon location() { return node == null ? coordinate : node.getCoor(); }
    }
}
