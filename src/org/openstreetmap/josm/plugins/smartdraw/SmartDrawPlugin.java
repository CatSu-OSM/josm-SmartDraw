package org.openstreetmap.josm.plugins.smartdraw;

import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

/** Installs the SmartDraw map mode when an editable map frame appears. */
public final class SmartDrawPlugin extends Plugin {
    public SmartDrawPlugin(PluginInformation info) {
        super(info);
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            newFrame.addMapMode(new IconToggleButton(new SmartDrawMode(newFrame)));
        }
    }
}
