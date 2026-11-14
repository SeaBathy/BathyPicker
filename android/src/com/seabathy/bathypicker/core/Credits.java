package com.seabathy.bathypicker.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The _sources.txt next to each GeoTIFF: credits for everything in it (sources_text in bathy_picker.py). */
public final class Credits {
    private Credits() {}

    public static String text(Planner.Plan plan, String finalName, String appVersion) {
        List<String> lines = new ArrayList<>();
        lines.add("Sources for " + finalName);
        lines.add("Made with BathyPicker for Android " + appVersion + " on "
                + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ". Not for navigation.");
        lines.add("");
        if (!plan.order.isEmpty()) {
            lines.add("Base layer:");
            lines.add("  " + Catalog.CITATION);
            lines.add("  Licence: CC BY 4.0");
            lines.add("");
        }
        if (!plan.chosen.isEmpty()) {
            lines.add("Original surveys on top (first one wins where they overlap):");
            if (plan.blend) {
                double mx = 0;
                for (Surveys.Survey s : plan.chosen) {
                    Double used = plan.bandUsedM.get(s.id);
                    mx = Math.max(mx, used != null ? used : plan.bandM.getOrDefault(s.id, 0.0));
                }
                lines.add(String.format(Locale.ROOT, "  Each survey's edges and gaps fade in from the layer underneath over a band of up to "
                        + "%.0f m (narrower for small surveys), so there is no step; further in, the survey's own depths are used exactly.", mx));
            }
            for (Surveys.Survey s : plan.chosen) {
                String how = s.shiftM == 0 && s.sign == 1 ? "depths used as stated"
                        : "depths " + (s.sign < 0 ? "flipped and " : "") + "shifted " + (s.shiftM >= 0 ? "+" : "") + Planner.g(s.shiftM)
                        + " m to match sea level";
                lines.add("  " + s.name + " (" + (s.year != null ? String.valueOf(s.year) : "year unknown") + "), " + Planner.g(s.resM)
                        + " m, " + how + (s.statedDatum != null && !s.statedDatum.isEmpty() ? ", stated datum " + s.statedDatum : "") + ".");
                String credit = !s.credit.isEmpty() ? s.credit : s.source;
                String lic = !s.licence.isEmpty() ? s.licence : "see source";
                lines.add("    " + credit + (credit.contains(lic) ? "" : " Licence: " + lic + "."));
                for (Surveys.SFile f : s.files) lines.add("    " + f.url);
            }
            lines.add("");
        }
        // What CC BY 4.0 asks for besides the credits themselves (the same text as the desktop's).
        lines.add("Changes: the data above was reprojected and resampled onto this file's grid and merged into one "
                + "layer; surveys shifted to sea level or blended at their edges are noted above.");
        lines.add("Licence for all data above: CC BY 4.0, https://creativecommons.org/licenses/by/4.0/");
        lines.add("");
        return String.join("\n", lines);
    }
}
