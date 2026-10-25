package org.openstreetmap.josm.plugins.smartdraw;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.openstreetmap.josm.data.osm.Way;

/** Exact key/value rules; a bare key matches any value. Exclusions win. */
public final class TagRules {
    private String includes = "landuse=forest,natural=wood,landuse=farmland,natural=water,waterway=riverbank,landuse=meadow,landuse=grass";
    private String excludes = "highway,railway";
    private boolean unrestricted;

    public void configure(String includes, String excludes, boolean unrestricted) {
        this.includes = includes;
        this.excludes = excludes;
        this.unrestricted = unrestricted;
    }

    public boolean eligible(Way way) {
        if (way == null || !way.isUsable() || way.hasIncompleteNodes()) return false;
        if (matches(way, excludes)) return false;
        return unrestricted || matches(way, includes);
    }

    private static boolean matches(Way way, String rules) {
        return Arrays.stream(rules.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).anyMatch(rule -> {
                    int split = rule.indexOf('=');
                    String key = split < 0 ? rule : rule.substring(0, split).trim();
                    String value = way.get(key);
                    return value != null && (split < 0 || value.equals(rule.substring(split + 1).trim()));
                });
    }

    public String includes() { return includes; }
    public String excludes() { return excludes; }
    public boolean unrestricted() { return unrestricted; }
}
