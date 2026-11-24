package io.github.colorosfontvf.font;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Discovers the primary FlipFont file and optional companion style/family files
 * in the same directory, performing locale-aware deduplication for multi-face TTC fonts.
 */
public final class CustomFontScanner {

    private static final String[] FONT_EXTENSIONS = {".ttf", ".otf", ".ttc"};

    private static final class CompanionRule {
        final String stem;
        final int hintedWeight;
        final boolean hintedItalic;

        CompanionRule(String stem, int hintedWeight, boolean hintedItalic) {
            this.stem = stem;
            this.hintedWeight = hintedWeight;
            this.hintedItalic = hintedItalic;
        }
    }

    private static final CompanionRule[] SANS_COMPANIONS = {
            new CompanionRule("Customized-Italic", 400, true),
            new CompanionRule("Customized-Thin", 100, false),
            new CompanionRule("Customized-ThinItalic", 100, true),
            new CompanionRule("Customized-ExtraLight", 200, false),
            new CompanionRule("Customized-ExtraLightItalic", 200, true),
            new CompanionRule("Customized-Light", 300, false),
            new CompanionRule("Customized-LightItalic", 300, true),
            new CompanionRule("Customized-Medium", 500, false),
            new CompanionRule("Customized-MediumItalic", 500, true),
            new CompanionRule("Customized-SemiBold", 600, false),
            new CompanionRule("Customized-SemiBoldItalic", 600, true),
            new CompanionRule("Customized-Bold", 700, false),
            new CompanionRule("Customized-BoldItalic", 700, true),
            new CompanionRule("Customized-ExtraBold", 800, false),
            new CompanionRule("Customized-ExtraBoldItalic", 800, true),
            new CompanionRule("Customized-Black", 900, false),
            new CompanionRule("Customized-BlackItalic", 900, true)
    };

    private static final CompanionRule[] CONDENSED_COMPANIONS = {
            new CompanionRule("Customized-Condensed", 400, false),
            new CompanionRule("Customized-CondensedBold", 700, false),
            new CompanionRule("Customized-CondensedItalic", 400, true),
            new CompanionRule("Customized-CondensedBoldItalic", 700, true)
    };

    private static final CompanionRule[] SERIF_COMPANIONS = {
            new CompanionRule("Customized-Serif", 400, false),
            new CompanionRule("Customized-SerifBold", 700, false),
            new CompanionRule("Customized-SerifItalic", 400, true),
            new CompanionRule("Customized-SerifBoldItalic", 700, true)
    };

    private static final CompanionRule[] MONO_COMPANIONS = {
            new CompanionRule("Customized-Mono", 400, false),
            new CompanionRule("Customized-MonoBold", 700, false),
            new CompanionRule("Customized-MonoItalic", 400, true),
            new CompanionRule("Customized-MonoBoldItalic", 700, true)
    };

    /** Regional preference bucket used when deduplicating TTC faces with identical weight/italic. */
    public enum Region {
        SC,
        TC,
        HK,
        JP,
        KR
    }

    /** Complete scanned set of custom font faces organized by target Android font families. */
    public static final class ScannedCustomFontSet {
        public final File primaryFile;
        public final List<OpenTypeInspector.FontFaceInfo> sansFaces;
        public final List<OpenTypeInspector.FontFaceInfo> condensedFaces;
        public final List<OpenTypeInspector.FontFaceInfo> serifFaces;
        public final List<OpenTypeInspector.FontFaceInfo> monoFaces;
        public final List<OpenTypeInspector.FontFaceInfo> hansFaces;
        public final List<OpenTypeInspector.FontFaceInfo> hantFaces;
        public final long fingerprint;

        public ScannedCustomFontSet(
                File primaryFile,
                List<OpenTypeInspector.FontFaceInfo> sansFaces,
                List<OpenTypeInspector.FontFaceInfo> condensedFaces,
                List<OpenTypeInspector.FontFaceInfo> serifFaces,
                List<OpenTypeInspector.FontFaceInfo> monoFaces,
                List<OpenTypeInspector.FontFaceInfo> hansFaces,
                List<OpenTypeInspector.FontFaceInfo> hantFaces,
                long fingerprint) {
            this.primaryFile = primaryFile;
            this.sansFaces = Collections.unmodifiableList(new ArrayList<>(sansFaces));
            this.condensedFaces = Collections.unmodifiableList(new ArrayList<>(condensedFaces));
            this.serifFaces = Collections.unmodifiableList(new ArrayList<>(serifFaces));
            this.monoFaces = Collections.unmodifiableList(new ArrayList<>(monoFaces));
            this.hansFaces = Collections.unmodifiableList(new ArrayList<>(hansFaces));
            this.hantFaces = Collections.unmodifiableList(new ArrayList<>(hantFaces));
            this.fingerprint = fingerprint;
        }

        public boolean isEmpty() {
            return sansFaces.isEmpty();
        }

        public List<File> collectAllUniqueFiles() {
            Map<String, File> unique = new LinkedHashMap<>();
            addFiles(unique, sansFaces);
            addFiles(unique, condensedFaces);
            addFiles(unique, serifFaces);
            addFiles(unique, monoFaces);
            addFiles(unique, hansFaces);
            addFiles(unique, hantFaces);
            return new ArrayList<>(unique.values());
        }

        private static void addFiles(Map<String, File> out, List<OpenTypeInspector.FontFaceInfo> faces) {
            for (OpenTypeInspector.FontFaceInfo face : faces) {
                if (face.file != null) {
                    out.putIfAbsent(face.file.getAbsolutePath(), face.file);
                }
            }
        }
    }

    private CustomFontScanner() {
    }

    /** Scans the primary custom font file and its sibling companions using the default locale. */
    public static ScannedCustomFontSet scan(File primaryFile) {
        return scan(primaryFile, Locale.getDefault());
    }

    /** Scans the primary custom font file and its sibling companions for the given locale. */
    public static ScannedCustomFontSet scan(File primaryFile, Locale primaryLocale) {
        if (primaryFile == null || !primaryFile.isFile()) {
            return new ScannedCustomFontSet(
                    primaryFile,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    0L
            );
        }

        List<OpenTypeInspector.FontFaceInfo> rawSans = new ArrayList<>(OpenTypeInspector.inspect(primaryFile));
        File parentDir = primaryFile.getParentFile();
        File[] siblingFiles = parentDir != null ? parentDir.listFiles() : null;

        collectCompanions(siblingFiles, SANS_COMPANIONS, rawSans);
        List<OpenTypeInspector.FontFaceInfo> condensed = new ArrayList<>();
        collectCompanions(siblingFiles, CONDENSED_COMPANIONS, condensed);
        List<OpenTypeInspector.FontFaceInfo> serif = new ArrayList<>();
        collectCompanions(siblingFiles, SERIF_COMPANIONS, serif);
        List<OpenTypeInspector.FontFaceInfo> mono = new ArrayList<>();
        collectCompanions(siblingFiles, MONO_COMPANIONS, mono);

        Region preferredRegion = resolveRegion(primaryLocale);
        List<OpenTypeInspector.FontFaceInfo> sansFaces = selectFacesForRegion(rawSans, preferredRegion);
        List<OpenTypeInspector.FontFaceInfo> condensedFaces = selectFacesForRegion(condensed, preferredRegion);
        List<OpenTypeInspector.FontFaceInfo> serifFaces = selectFacesForRegion(serif, preferredRegion);
        List<OpenTypeInspector.FontFaceInfo> monoFaces = selectFacesForRegion(mono, preferredRegion);

        List<OpenTypeInspector.FontFaceInfo> hansFaces = buildFallbackRegionFaces(rawSans, Region.SC, true);
        List<OpenTypeInspector.FontFaceInfo> hantFaces = buildFallbackRegionFaces(rawSans, Region.TC, false);

        long fingerprint = computeFingerprint(sansFaces, condensedFaces, serifFaces, monoFaces, hansFaces, hantFaces);
        return new ScannedCustomFontSet(
                primaryFile,
                sansFaces,
                condensedFaces,
                serifFaces,
                monoFaces,
                hansFaces,
                hantFaces,
                fingerprint
        );
    }

    private static List<OpenTypeInspector.FontFaceInfo> buildFallbackRegionFaces(
            List<OpenTypeInspector.FontFaceInfo> rawSans, Region region, boolean wantHans) {
        List<OpenTypeInspector.FontFaceInfo> filtered = new ArrayList<>();
        for (OpenTypeInspector.FontFaceInfo face : rawSans) {
            if (wantHans ? face.coverage.hasHans : face.coverage.hasHant) {
                filtered.add(face);
            }
        }
        return selectFacesForRegion(filtered, region);
    }

    private static void collectCompanions(
            File[] siblings, CompanionRule[] rules, List<OpenTypeInspector.FontFaceInfo> out) {
        if (siblings == null || siblings.length == 0) {
            return;
        }
        Map<String, File> byLowerName = new LinkedHashMap<>();
        for (File f : siblings) {
            if (f != null && f.isFile()) {
                byLowerName.put(f.getName().toLowerCase(Locale.ROOT), f);
            }
        }
        for (CompanionRule rule : rules) {
            File matched = null;
            for (String ext : FONT_EXTENSIONS) {
                matched = byLowerName.get((rule.stem + ext).toLowerCase(Locale.ROOT));
                if (matched != null) {
                    break;
                }
            }
            if (matched == null) {
                continue;
            }
            List<OpenTypeInspector.FontFaceInfo> inspected = OpenTypeInspector.inspect(matched);
            for (OpenTypeInspector.FontFaceInfo face : inspected) {
                if (inspected.size() == 1 && face.weight == 400 && !face.italic
                        && (rule.hintedWeight != 400 || rule.hintedItalic)) {
                    out.add(face.withWeightAndItalic(rule.hintedWeight, rule.hintedItalic));
                } else {
                    out.add(face);
                }
            }
        }
    }

    /** Resolves a JVM {@link Locale} into the closest CJK regional face bucket. */
    public static Region resolveRegion(Locale locale) {
        if (locale == null) {
            return Region.SC;
        }
        String lang = locale.getLanguage().toLowerCase(Locale.ROOT);
        String script = locale.getScript().toLowerCase(Locale.ROOT);
        String country = locale.getCountry().toUpperCase(Locale.ROOT);
        if ("ja".equals(lang)) {
            return Region.JP;
        }
        if ("ko".equals(lang)) {
            return Region.KR;
        }
        if ("hant".equals(script) || "TW".equals(country) || "MO".equals(country)) {
            return Region.TC;
        }
        if ("HK".equals(country)) {
            return Region.HK;
        }
        return Region.SC;
    }

    /**
     * Deduplicates faces that share the same {@code (weight, italic)} style, preferring faces
     * matching {@code targetRegion} and breaking ties by lowest {@code ttcIndex}.
     */
    public static List<OpenTypeInspector.FontFaceInfo> selectFacesForRegion(
            List<OpenTypeInspector.FontFaceInfo> faces, Region targetRegion) {
        if (faces == null || faces.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Integer, OpenTypeInspector.FontFaceInfo> chosen = new LinkedHashMap<>();
        Map<Integer, Integer> chosenScores = new LinkedHashMap<>();

        for (OpenTypeInspector.FontFaceInfo face : faces) {
            int styleKey = face.weight * 2 + (face.italic ? 1 : 0);
            int score = scoreFaceForRegion(face, targetRegion);
            OpenTypeInspector.FontFaceInfo prev = chosen.get(styleKey);
            Integer prevScore = chosenScores.get(styleKey);
            if (prev == null || score > prevScore
                    || (score == prevScore && face.ttcIndex < prev.ttcIndex)) {
                chosen.put(styleKey, face);
                chosenScores.put(styleKey, score);
            }
        }

        List<OpenTypeInspector.FontFaceInfo> result = new ArrayList<>(chosen.values());
        result.sort((a, b) -> {
            if (a.italic != b.italic) {
                return a.italic ? 1 : -1;
            }
            if (a.weight != b.weight) {
                return Integer.compare(a.weight, b.weight);
            }
            return Integer.compare(a.ttcIndex, b.ttcIndex);
        });
        return result;
    }

    private static int scoreFaceForRegion(OpenTypeInspector.FontFaceInfo face, Region target) {
        String label = (face.postScriptName + " " + face.familyName + " " + face.fullName).toUpperCase(Locale.ROOT);
        if (matchesRegion(label, target)) {
            return 100;
        }
        if (target == Region.HK && matchesRegion(label, Region.TC)) {
            return 75;
        }
        if (target == Region.TC && matchesRegion(label, Region.HK)) {
            return 60;
        }
        for (Region other : Region.values()) {
            if (other != target && matchesRegion(label, other)) {
                return 10;
            }
        }
        return 50;
    }

    private static boolean matchesRegion(String upperLabel, Region region) {
        switch (region) {
            case SC:
                return containsRegionToken(upperLabel, "SC")
                        || upperLabel.contains("HANS")
                        || containsRegionToken(upperLabel, "CN")
                        || containsRegionToken(upperLabel, "GB");
            case TC:
                return containsRegionToken(upperLabel, "TC")
                        || upperLabel.contains("HANT")
                        || containsRegionToken(upperLabel, "TW");
            case HK:
                return containsRegionToken(upperLabel, "HK")
                        || containsRegionToken(upperLabel, "HC");
            case JP:
                return containsRegionToken(upperLabel, "JP")
                        || containsRegionToken(upperLabel, "J");
            case KR:
                return containsRegionToken(upperLabel, "KR")
                        || containsRegionToken(upperLabel, "K");
            default:
                return false;
        }
    }

    private static boolean containsRegionToken(String text, String token) {
        int idx = text.indexOf(token);
        while (idx >= 0) {
            boolean leftBoundary = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int end = idx + token.length();
            boolean rightBoundary = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            // Also handle CamelCase / suffix styles like "NotoSansCJKsc" or "SarasaGothicSC"
            if (!leftBoundary && Character.isLowerCase(text.charAt(idx - 1)) && token.length() >= 2) {
                return true;
            }
            idx = text.indexOf(token, idx + 1);
        }
        return false;
    }

    @SafeVarargs
    private static long computeFingerprint(List<OpenTypeInspector.FontFaceInfo>... lists) {
        long hash = 1469598103934665603L;
        for (List<OpenTypeInspector.FontFaceInfo> list : lists) {
            for (OpenTypeInspector.FontFaceInfo face : list) {
                hash ^= face.file.getAbsolutePath().hashCode();
                hash *= 1099511628211L;
                hash ^= face.file.lastModified();
                hash *= 1099511628211L;
                hash ^= face.file.length();
                hash *= 1099511628211L;
                hash ^= face.ttcIndex;
                hash *= 1099511628211L;
            }
        }
        return hash;
    }
}
