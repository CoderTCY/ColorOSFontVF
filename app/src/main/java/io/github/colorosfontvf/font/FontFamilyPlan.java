package io.github.colorosfontvf.font;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java planner translating inspected OpenType faces into Android {@code FontConfig.FontFamily}
 * entries compatible with {@code SystemFonts.resolveVarFamilyType()} (Minikin VariationFamilyType 0..3).
 */
public final class FontFamilyPlan {

    /** Mirrors {@code FontConfig.Font.VAR_TYPE_AXES_NONE}. */
    public static final int VAR_TYPE_AXES_NONE = 0;
    /** Mirrors {@code FontConfig.Font.VAR_TYPE_AXES_WGHT}. */
    public static final int VAR_TYPE_AXES_WGHT = 1;
    /** Mirrors {@code FontConfig.Font.VAR_TYPE_AXES_ITAL}. */
    public static final int VAR_TYPE_AXES_ITAL = 2;

    /** Mirrors {@code FontStyle.SLANT_UPRIGHT}. */
    public static final int SLANT_UPRIGHT = 0;
    /** Mirrors {@code FontStyle.SLANT_ITALIC}. */
    public static final int SLANT_ITALIC = 1;

    /** Specification for one {@code FontConfig.Font} entry. */
    public static final class FontEntrySpec {
        public final File file;
        public final String postScriptName;
        public final int weight;
        public final int slant;
        public final int ttcIndex;
        public final String variationSettings;
        public final int varTypeAxes;

        public FontEntrySpec(
                File file,
                String postScriptName,
                int weight,
                int slant,
                int ttcIndex,
                String variationSettings,
                int varTypeAxes) {
            this.file = file;
            this.postScriptName = postScriptName;
            this.weight = weight;
            this.slant = slant;
            this.ttcIndex = ttcIndex;
            this.variationSettings = variationSettings != null ? variationSettings : "";
            this.varTypeAxes = varTypeAxes;
        }
    }

    /** Specification for one {@code FontConfig.FontFamily} entry. */
    public static final class FamilySpec {
        public final List<FontEntrySpec> entries;
        public final String languageTags;
        public final int expectedVarFamilyType;

        public FamilySpec(List<FontEntrySpec> entries, String languageTags, int expectedVarFamilyType) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.languageTags = languageTags != null ? languageTags : "";
            this.expectedVarFamilyType = expectedVarFamilyType;
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    private FontFamilyPlan() {
    }

    /**
     * Plans a {@link FamilySpec} for the given inspected faces, selecting the richest variable-font
     * strategy supported by Android's Minikin engine:
     * <ol>
     *     <li>Upright VF + Italic VF pair ({@code varFamilyType = 3}, upright entry first, italic second)</li>
     *     <li>Single VF with {@code wght} + {@code ital} ({@code varFamilyType = 2})</li>
     *     <li>Single VF with {@code wght} + {@code slnt} ({@code varFamilyType = 3}, synthesized upright/italic)</li>
     *     <li>Single VF with {@code wght} ({@code varFamilyType = 1})</li>
     *     <li>Static multi-weight / multi-style or single-face family ({@code varFamilyType = 0})</li>
     * </ol>
     */
    public static FamilySpec buildFamilySpec(
            List<OpenTypeInspector.FontFaceInfo> rawFaces,
            String fixedExtraVariation,
            String languageTags) {
        if (rawFaces == null || rawFaces.isEmpty()) {
            return new FamilySpec(Collections.emptyList(), languageTags, 0);
        }

        List<OpenTypeInspector.FontFaceInfo> faces = new ArrayList<>();
        for (OpenTypeInspector.FontFaceInfo f : rawFaces) {
            if (f != null && f.file != null) {
                faces.add(f);
            }
        }
        if (faces.isEmpty()) {
            return new FamilySpec(Collections.emptyList(), languageTags, 0);
        }

        String baseVar = fixedExtraVariation != null ? fixedExtraVariation.trim() : "";

        // 1. Upright VF + Italic VF pair -> varFamilyType = 3 (upright first, italic second)
        OpenTypeInspector.FontFaceInfo uprightVf = findFirstVariableWght(faces, false);
        OpenTypeInspector.FontFaceInfo italicVf = findFirstVariableWght(faces, true);
        if (uprightVf != null && italicVf != null) {
            List<FontEntrySpec> entries = new ArrayList<>(2);
            entries.add(new FontEntrySpec(
                    uprightVf.file, uprightVf.postScriptName, 400, SLANT_UPRIGHT,
                    uprightVf.ttcIndex, baseVar, VAR_TYPE_AXES_WGHT));
            entries.add(new FontEntrySpec(
                    italicVf.file, italicVf.postScriptName, 400, SLANT_ITALIC,
                    italicVf.ttcIndex, baseVar, VAR_TYPE_AXES_WGHT));
            return new FamilySpec(entries, languageTags, 3);
        }

        // 2. Single VF with wght + ital -> varFamilyType = 2
        OpenTypeInspector.FontFaceInfo wghtItalVf = findFirst(faces, f -> f.supportsWght() && f.supportsItal());
        if (wghtItalVf != null) {
            return new FamilySpec(
                    Collections.singletonList(new FontEntrySpec(
                            wghtItalVf.file, wghtItalVf.postScriptName, 400, SLANT_UPRIGHT,
                            wghtItalVf.ttcIndex, baseVar, VAR_TYPE_AXES_WGHT | VAR_TYPE_AXES_ITAL)),
                    languageTags,
                    2
            );
        }

        // 3. Single VF with wght + slnt (no ital) -> varFamilyType = 3 (upright + slanted)
        OpenTypeInspector.FontFaceInfo wghtSlntVf = findFirst(faces, f -> f.supportsWght() && f.supportsSlnt());
        if (wghtSlntVf != null) {
            float angle = wghtSlntVf.getBestSlantAngle();
            if (angle != 0.0f) {
                String slantVar = mergeVariation(baseVar, "'slnt' " + formatAxisValue(angle));
                List<FontEntrySpec> entries = new ArrayList<>(2);
                entries.add(new FontEntrySpec(
                        wghtSlntVf.file, wghtSlntVf.postScriptName, 400, SLANT_UPRIGHT,
                        wghtSlntVf.ttcIndex, baseVar, VAR_TYPE_AXES_WGHT));
                entries.add(new FontEntrySpec(
                        wghtSlntVf.file, wghtSlntVf.postScriptName, 400, SLANT_ITALIC,
                        wghtSlntVf.ttcIndex, slantVar, VAR_TYPE_AXES_WGHT));
                return new FamilySpec(entries, languageTags, 3);
            }
        }

        // 4. Single VF with wght only -> varFamilyType = 1
        OpenTypeInspector.FontFaceInfo wghtOnlyVf = uprightVf != null ? uprightVf : italicVf;
        if (wghtOnlyVf != null) {
            int slant = wghtOnlyVf.italic ? SLANT_ITALIC : SLANT_UPRIGHT;
            return new FamilySpec(
                    Collections.singletonList(new FontEntrySpec(
                            wghtOnlyVf.file, wghtOnlyVf.postScriptName, 400, slant,
                            wghtOnlyVf.ttcIndex, baseVar, VAR_TYPE_AXES_WGHT)),
                    languageTags,
                    1
            );
        }

        // 5. Static family (or non-wght slnt VF) -> varFamilyType = 0
        if (faces.size() == 1 && !faces.get(0).italic && faces.get(0).supportsSlnt()) {
            OpenTypeInspector.FontFaceInfo solo = faces.get(0);
            float angle = solo.getBestSlantAngle();
            if (angle != 0.0f) {
                List<FontEntrySpec> entries = new ArrayList<>(2);
                entries.add(new FontEntrySpec(
                        solo.file, solo.postScriptName, solo.weight, SLANT_UPRIGHT,
                        solo.ttcIndex, baseVar, VAR_TYPE_AXES_NONE));
                entries.add(new FontEntrySpec(
                        solo.file, solo.postScriptName, solo.weight, SLANT_ITALIC,
                        solo.ttcIndex, mergeVariation(baseVar, "'slnt' " + formatAxisValue(angle)),
                        VAR_TYPE_AXES_NONE));
                return new FamilySpec(entries, languageTags, 0);
            }
        }

        List<OpenTypeInspector.FontFaceInfo> sorted = new ArrayList<>(faces);
        sorted.sort((a, b) -> {
            if (a.italic != b.italic) {
                return a.italic ? 1 : -1;
            }
            if (a.weight != b.weight) {
                return Integer.compare(a.weight, b.weight);
            }
            return Integer.compare(a.ttcIndex, b.ttcIndex);
        });

        List<FontEntrySpec> entries = new ArrayList<>(sorted.size());
        for (OpenTypeInspector.FontFaceInfo f : sorted) {
            entries.add(new FontEntrySpec(
                    f.file,
                    f.postScriptName,
                    f.weight,
                    f.italic ? SLANT_ITALIC : SLANT_UPRIGHT,
                    f.ttcIndex,
                    baseVar,
                    VAR_TYPE_AXES_NONE
            ));
        }
        return new FamilySpec(entries, languageTags, 0);
    }

    /**
     * Builds the {@code sans-serif-condensed} specification from explicit condensed companion files
     * if present, or via the {@code wdth} axis on the primary sans faces when available.
     */
    public static FamilySpec buildCondensedSpec(CustomFontScanner.ScannedCustomFontSet set) {
        if (set == null || set.isEmpty()) {
            return new FamilySpec(Collections.emptyList(), "", 0);
        }
        if (!set.condensedFaces.isEmpty()) {
            return buildFamilySpec(set.condensedFaces, "", "");
        }
        OpenTypeInspector.FontFaceInfo primary = set.sansFaces.get(0);
        OpenTypeInspector.AxisInfo wdth = primary.getAxis("wdth");
        if (wdth != null && wdth.isVariable() && wdth.minValue < wdth.defaultValue) {
            float targetWdth = Math.max(wdth.minValue, Math.min(75.0f, wdth.defaultValue - 1.0f));
            return buildFamilySpec(set.sansFaces, "'wdth' " + formatAxisValue(targetWdth), "");
        }
        return buildFamilySpec(set.sansFaces, "", "");
    }

    static String mergeVariation(String a, String b) {
        boolean hasA = a != null && !a.isEmpty();
        boolean hasB = b != null && !b.isEmpty();
        if (hasA && hasB) {
            return a + "," + b;
        }
        return hasA ? a : (hasB ? b : "");
    }

    static String formatAxisValue(float value) {
        int rounded = Math.round(value);
        if (Math.abs(value - rounded) < 0.001f) {
            return Integer.toString(rounded);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private interface FacePredicate {
        boolean test(OpenTypeInspector.FontFaceInfo face);
    }

    private static OpenTypeInspector.FontFaceInfo findFirstVariableWght(
            List<OpenTypeInspector.FontFaceInfo> faces, boolean italic) {
        OpenTypeInspector.FontFaceInfo best = null;
        for (OpenTypeInspector.FontFaceInfo face : faces) {
            if (face.italic == italic && face.supportsWght()) {
                if (best == null || Math.abs(face.weight - 400) < Math.abs(best.weight - 400)) {
                    best = face;
                }
            }
        }
        return best;
    }

    private static OpenTypeInspector.FontFaceInfo findFirst(
            List<OpenTypeInspector.FontFaceInfo> faces, FacePredicate predicate) {
        for (OpenTypeInspector.FontFaceInfo face : faces) {
            if (predicate.test(face)) {
                return face;
            }
        }
        return null;
    }
}
