# Design and source review

SmartDraw is an independent JOSM `MapMode` and `MapViewPaintable`. The installed FastDraw plugin's `FastDrawingMode` uses the same two JOSM interfaces, while its `DrawnPolyLine` owns point collection and simplification. FastDraw's mode and helpers are package-private and its editing workflow includes more state than SmartDraw needs for mixed freehand and existing-way traversal. This project therefore uses the public JOSM plugin and command APIs without linking FastDraw or copying its source.

The installed JOSM runtime exposes `MapFrame.addMapMode`, `MapView.addTemporaryLayer`, `NavigatableComponent.getPoint2D/getLatLon`, `DataSet.searchWays/searchNodes`, `AddCommand`, `ChangeNodesCommand`, and `SequenceCommand`. SmartDraw uses the spatial search for candidates and commits all node additions, existing-way insertions, and the new way as one undoable sequence. An existing way object keeps its tags, ID, history, and relation membership when its node list changes.

For magnetism, SmartDraw asks the visible imagery layer to paint a bounded screen crop through JOSM. This delegates projection, tile placement, and imagery offset handling to JOSM. A background shortest-path search favors strong luminance gradients inside a cursor-to-anchor corridor. Missing or flat imagery yields no suggestion. The paint capture occurs on the Swing event thread and is bounded, while path analysis runs off the event thread with stale-result cancellation.

ContourMerge changes source contours to align with targets. SmartDraw does not use that operation because its Follow Way mode must preserve the target way and only insert shared nodes at explicitly chosen segment entry or exit points. JOSM Draw and Improve Way Accuracy remain separate map modes; SmartDraw reuses their public coordinate and command infrastructure instead of inheriting their interaction state.

References:

- JOSM plugin development: https://josm.openstreetmap.de/wiki/DevelopersGuide/DevelopingPlugins
- JOSM MapMode API: https://josm.openstreetmap.de/doc/org/openstreetmap/josm/actions/mapmode/MapMode.html
- JOSM imagery layer API: https://josm.openstreetmap.de/doc/org/openstreetmap/josm/gui/layer/ImageryLayer.html
- FastDraw behavior: https://wiki.openstreetmap.org/wiki/JOSM/Plugins/FastDraw
- ContourMerge behavior: https://josm.openstreetmap.de/wiki/Help/Plugin/ContourMerge
- JOSM shortcuts: https://josm.openstreetmap.de/wiki/Shortcuts
