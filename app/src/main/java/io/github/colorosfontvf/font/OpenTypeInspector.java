package io.github.colorosfontvf.font;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java binary OpenType / SFNT / TTC font inspector.
 * Extracts variable axes ({@code fvar}), style metadata ({@code OS/2}, {@code head}, {@code post}),
 * names ({@code name}), glyph coverage ({@code cmap}), and OpenType layout table presence.
 */
public final class OpenTypeInspector {

    public static final int TAG_WGHT = 0x77676874; // "wght"
    public static final int TAG_ITAL = 0x6974616C; // "ital"
    public static final int TAG_SLNT = 0x736C6E74; // "slnt"
    public static final int TAG_WDTH = 0x77647468; // "wdth"
    public static final int TAG_OPSZ = 0x6F70737A; // "opsz"

    private static final int SFNT_TTC = 0x74746366;      // "ttcf"
    private static final int SFNT_TRUETYPE = 0x00010000; // 1.0
    private static final int SFNT_OTTO = 0x4F54544F;     // "OTTO"
    private static final int SFNT_TRUE = 0x74727565;     // "true"

    private static final int TABLE_OS2 = 0x4F532F32;  // "OS/2"
    private static final int TABLE_HEAD = 0x68656164; // "head"
    private static final int TABLE_POST = 0x706F7374; // "post"
    private static final int TABLE_FVAR = 0x66766172; // "fvar"
    private static final int TABLE_NAME = 0x6E616D65; // "name"
    private static final int TABLE_CMAP = 0x636D6170; // "cmap"
    private static final int TABLE_GSUB = 0x47535542; // "GSUB"
    private static final int TABLE_GPOS = 0x47504F53; // "GPOS"

    private static final int MAX_TTC_FACES = 64;
    private static final int MAX_TABLES = 256;
    private static final int MAX_AXES = 64;

    private static final int[] LATIN_PROBES = {'A', 'a', 'e', '0'};
    private static final int[] HANS_PROBES = {0x4E2D, 0x56FD, 0x6C49, 0x8BED, 0x4F53}; // 中国汉语体
    private static final int[] HANT_PROBES = {0x570B, 0x6F22, 0x8A9E, 0x9AD4, 0x81FA}; // 國漢語體臺

    private OpenTypeInspector() {
    }

    /** Variable axis record parsed from an OpenType {@code fvar} table. */
    public static final class AxisInfo {
        public final String tag;
        public final int tagValue;
        public final float minValue;
        public final float defaultValue;
        public final float maxValue;
        public final int flags;

        public AxisInfo(String tag, int tagValue, float minValue, float defaultValue, float maxValue, int flags) {
            this.tag = tag;
            this.tagValue = tagValue;
            this.minValue = minValue;
            this.defaultValue = defaultValue;
            this.maxValue = maxValue;
            this.flags = flags;
        }

        public boolean isVariable() {
            return maxValue > minValue;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s[%.4g..%.4g..%.4g]", tag, minValue, defaultValue, maxValue);
        }
    }

    /** Probe-based Unicode script coverage extracted from {@code cmap}. */
    public static final class ScriptCoverage {
        public final boolean hasLatin;
        public final boolean hasHans;
        public final boolean hasHant;

        public ScriptCoverage(boolean hasLatin, boolean hasHans, boolean hasHant) {
            this.hasLatin = hasLatin;
            this.hasHans = hasHans;
            this.hasHant = hasHant;
        }
    }

    /** Parsed metadata for a single SFNT font face (standalone file or one face inside a TTC). */
    public static final class FontFaceInfo {
        public final File file;
        public final int ttcIndex;
        public final String postScriptName;
        public final String familyName;
        public final String subfamilyName;
        public final String fullName;
        public final int weight;
        public final boolean italic;
        public final List<AxisInfo> axes;
        public final ScriptCoverage coverage;
        public final boolean hasGsub;
        public final boolean hasGpos;

        public FontFaceInfo(
                File file,
                int ttcIndex,
                String postScriptName,
                String familyName,
                String subfamilyName,
                String fullName,
                int weight,
                boolean italic,
                List<AxisInfo> axes,
                ScriptCoverage coverage,
                boolean hasGsub,
                boolean hasGpos) {
            this.file = file;
            this.ttcIndex = ttcIndex;
            this.postScriptName = postScriptName;
            this.familyName = familyName;
            this.subfamilyName = subfamilyName;
            this.fullName = fullName;
            this.weight = weight;
            this.italic = italic;
            this.axes = Collections.unmodifiableList(new ArrayList<>(axes));
            this.coverage = coverage;
            this.hasGsub = hasGsub;
            this.hasGpos = hasGpos;
        }

        public AxisInfo getAxis(String tag) {
            for (AxisInfo axis : axes) {
                if (axis.tag.equals(tag)) {
                    return axis;
                }
            }
            return null;
        }

        public boolean hasVariableAxis(String tag) {
            AxisInfo axis = getAxis(tag);
            return axis != null && axis.isVariable();
        }

        public boolean supportsWght() {
            return hasVariableAxis("wght");
        }

        public boolean supportsItal() {
            return hasVariableAxis("ital");
        }

        public boolean supportsSlnt() {
            return hasVariableAxis("slnt");
        }

        public boolean supportsWdth() {
            return hasVariableAxis("wdth");
        }

        public boolean supportsOpsz() {
            return hasVariableAxis("opsz");
        }

        public float getBestSlantAngle() {
            AxisInfo slnt = getAxis("slnt");
            if (slnt == null || !slnt.isVariable()) {
                return 0.0f;
            }
            if (slnt.minValue < slnt.defaultValue) {
                return Math.max(slnt.minValue, -12.0f);
            }
            if (slnt.maxValue > slnt.defaultValue) {
                return Math.min(slnt.maxValue, 12.0f);
            }
            return 0.0f;
        }

        public FontFaceInfo withWeightAndItalic(int newWeight, boolean newItalic) {
            return new FontFaceInfo(
                    file,
                    ttcIndex,
                    postScriptName,
                    familyName,
                    subfamilyName,
                    fullName,
                    newWeight,
                    newItalic,
                    axes,
                    coverage,
                    hasGsub,
                    hasGpos
            );
        }
    }

    private static final class TableSlice {
        final int offset;
        final int length;

        TableSlice(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }

    /** Converts a 4-character OpenType tag string into its packed 32-bit integer representation. */
    public static int tagToInt(String tag) {
        if (tag == null || tag.length() != 4) {
            return 0;
        }
        return ((tag.charAt(0) & 0xFF) << 24)
                | ((tag.charAt(1) & 0xFF) << 16)
                | ((tag.charAt(2) & 0xFF) << 8)
                | (tag.charAt(3) & 0xFF);
    }

    /** Converts a packed 32-bit OpenType tag integer into a 4-character ASCII string. */
    public static String intToTag(int value) {
        char[] c = {
                (char) ((value >>> 24) & 0xFF),
                (char) ((value >>> 16) & 0xFF),
                (char) ((value >>> 8) & 0xFF),
                (char) (value & 0xFF)
        };
        return new String(c);
    }

    /** Inspects an OpenType/TrueType or TTC file on disk, returning all valid faces found. */
    public static List<FontFaceInfo> inspect(File file) {
        if (file == null || !file.isFile()) {
            return Collections.emptyList();
        }
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < 12 || size > Integer.MAX_VALUE) {
                return Collections.emptyList();
            }
            ByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            return inspect(mapped, file);
        } catch (Throwable ignored) {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                return inspect(ByteBuffer.wrap(bytes), file);
            } catch (IOException | RuntimeException e) {
                return Collections.emptyList();
            }
        }
    }

    /** Inspects an in-memory OpenType/TrueType or TTC byte buffer. */
    public static List<FontFaceInfo> inspect(ByteBuffer source, File sourceFile) {
        if (source == null || source.limit() < 12) {
            return Collections.emptyList();
        }
        ByteBuffer buf = source.asReadOnlyBuffer();
        buf.order(ByteOrder.BIG_ENDIAN);
        int limit = buf.limit();

        try {
            int magic = buf.getInt(0);
            if (magic == SFNT_TTC) {
                int numFonts = buf.getInt(8);
                if (numFonts <= 0 || numFonts > MAX_TTC_FACES || 12L + (long) numFonts * 4L > limit) {
                    return Collections.emptyList();
                }
                List<FontFaceInfo> faces = new ArrayList<>(numFonts);
                for (int i = 0; i < numFonts; i++) {
                    int faceOffset = buf.getInt(12 + i * 4);
                    FontFaceInfo face = parseFace(buf, limit, faceOffset, i, sourceFile);
                    if (face != null) {
                        faces.add(face);
                    }
                }
                return faces;
            }
            FontFaceInfo single = parseFace(buf, limit, 0, 0, sourceFile);
            return single != null ? Collections.singletonList(single) : Collections.emptyList();
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static FontFaceInfo parseFace(ByteBuffer buf, int limit, int faceOffset, int ttcIndex, File sourceFile) {
        if (faceOffset < 0 || faceOffset + 12L > limit) {
            return null;
        }
        int sfntVersion = buf.getInt(faceOffset);
        if (sfntVersion != SFNT_TRUETYPE && sfntVersion != SFNT_OTTO && sfntVersion != SFNT_TRUE) {
            return null;
        }
        int numTables = readUInt16(buf, faceOffset + 4);
        if (numTables <= 0 || numTables > MAX_TABLES || faceOffset + 12L + (long) numTables * 16L > limit) {
            return null;
        }

        Map<Integer, TableSlice> tables = new HashMap<>(numTables * 2);
        for (int i = 0; i < numTables; i++) {
            int entryOff = faceOffset + 12 + i * 16;
            int tag = buf.getInt(entryOff);
            int tableOffset = buf.getInt(entryOff + 8);
            int tableLength = buf.getInt(entryOff + 12);
            if (tableOffset >= 0 && tableLength >= 0 && (long) tableOffset + tableLength <= limit) {
                tables.put(tag, new TableSlice(tableOffset, tableLength));
            }
        }

        int weight = 400;
        boolean os2Italic = false;
        TableSlice os2 = tables.get(TABLE_OS2);
        if (os2 != null) {
            if (os2.length >= 6) {
                weight = clamp(readUInt16(buf, os2.offset + 4), 1, 1000);
            }
            if (os2.length >= 64) {
                int fsSelection = readUInt16(buf, os2.offset + 62);
                os2Italic = (fsSelection & 0x0201) != 0; // ITALIC (0x1) | OBLIQUE (0x200)
            }
        }

        boolean headItalic = false;
        TableSlice head = tables.get(TABLE_HEAD);
        if (head != null && head.length >= 46) {
            int macStyle = readUInt16(buf, head.offset + 44);
            headItalic = (macStyle & 0x0002) != 0;
        }

        boolean postItalic = false;
        TableSlice post = tables.get(TABLE_POST);
        if (post != null && post.length >= 8) {
            postItalic = buf.getInt(post.offset + 4) != 0;
        }

        boolean italic = os2Italic || headItalic || postItalic;
        List<AxisInfo> axes = parseFvar(buf, tables.get(TABLE_FVAR));
        Map<Integer, String> names = parseNames(buf, tables.get(TABLE_NAME));
        ScriptCoverage coverage = parseCmapCoverage(buf, tables.get(TABLE_CMAP));

        String familyName = firstNonEmpty(names.get(16), names.get(1), "");
        String subfamilyName = firstNonEmpty(names.get(17), names.get(2), "");
        String fullName = firstNonEmpty(names.get(4), familyName, "");
        String postScriptName = firstNonEmpty(names.get(6), defaultPostScriptName(sourceFile), "Customized-Regular");

        return new FontFaceInfo(
                sourceFile,
                ttcIndex,
                postScriptName,
                familyName,
                subfamilyName,
                fullName,
                weight,
                italic,
                axes,
                coverage,
                tables.containsKey(TABLE_GSUB),
                tables.containsKey(TABLE_GPOS)
        );
    }

    private static List<AxisInfo> parseFvar(ByteBuffer buf, TableSlice fvar) {
        if (fvar == null || fvar.length < 16) {
            return Collections.emptyList();
        }
        int major = readUInt16(buf, fvar.offset);
        int minor = readUInt16(buf, fvar.offset + 2);
        if (major != 1 || minor != 0) {
            return Collections.emptyList();
        }
        int axesOffset = readUInt16(buf, fvar.offset + 4);
        int axisCount = readUInt16(buf, fvar.offset + 8);
        int axisSize = readUInt16(buf, fvar.offset + 10);
        if (axisCount <= 0 || axisCount > MAX_AXES || axisSize < 20) {
            return Collections.emptyList();
        }
        if ((long) axesOffset + (long) axisCount * axisSize > fvar.length) {
            return Collections.emptyList();
        }

        List<AxisInfo> result = new ArrayList<>(axisCount);
        for (int i = 0; i < axisCount; i++) {
            int ao = fvar.offset + axesOffset + i * axisSize;
            int tagVal = buf.getInt(ao);
            float minVal = buf.getInt(ao + 4) / 65536.0f;
            float defVal = buf.getInt(ao + 8) / 65536.0f;
            float maxVal = buf.getInt(ao + 12) / 65536.0f;
            int flags = readUInt16(buf, ao + 16);
            result.add(new AxisInfo(intToTag(tagVal), tagVal, minVal, defVal, maxVal, flags));
        }
        return result;
    }

    private static Map<Integer, String> parseNames(ByteBuffer buf, TableSlice nameTable) {
        if (nameTable == null || nameTable.length < 6) {
            return Collections.emptyMap();
        }
        int count = readUInt16(buf, nameTable.offset + 2);
        int stringRelOffset = readUInt16(buf, nameTable.offset + 4);
        int stringBase = nameTable.offset + stringRelOffset;
        int tableEnd = nameTable.offset + nameTable.length;
        if (stringBase > tableEnd || 6L + (long) count * 12L > nameTable.length) {
            return Collections.emptyMap();
        }

        Map<Integer, String> bestValue = new HashMap<>();
        Map<Integer, Integer> bestScore = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int recOff = nameTable.offset + 6 + i * 12;
            int platformId = readUInt16(buf, recOff);
            int encodingId = readUInt16(buf, recOff + 2);
            int languageId = readUInt16(buf, recOff + 4);
            int nameId = readUInt16(buf, recOff + 6);
            if (nameId != 1 && nameId != 2 && nameId != 4 && nameId != 6 && nameId != 16 && nameId != 17) {
                continue;
            }
            int length = readUInt16(buf, recOff + 8);
            int offset = readUInt16(buf, recOff + 10);
            int strOff = stringBase + offset;
            if (length <= 0 || strOff < stringBase || (long) strOff + length > tableEnd) {
                continue;
            }

            int score = scoreNameRecord(platformId, encodingId, languageId);
            if (score <= 0) {
                continue;
            }
            Integer prevScore = bestScore.get(nameId);
            if (prevScore != null && prevScore >= score) {
                continue;
            }

            byte[] raw = new byte[length];
            for (int b = 0; b < length; b++) {
                raw[b] = buf.get(strOff + b);
            }
            String decoded = (platformId == 3 || platformId == 0)
                    ? new String(raw, StandardCharsets.UTF_16BE)
                    : new String(raw, StandardCharsets.ISO_8859_1);
            decoded = decoded.replace("\u0000", "").trim();
            if (!decoded.isEmpty()) {
                bestScore.put(nameId, score);
                bestValue.put(nameId, decoded);
            }
        }
        return bestValue;
    }

    private static int scoreNameRecord(int platformId, int encodingId, int languageId) {
        if (platformId == 3 && (encodingId == 1 || encodingId == 10)) {
            if (languageId == 0x0409) {
                return 100;
            }
            if (languageId == 0x0804 || languageId == 0x0404) {
                return 90;
            }
            return 80;
        }
        if (platformId == 0) {
            return 60;
        }
        if (platformId == 1 && encodingId == 0) {
            return 40;
        }
        return 0;
    }

    private static ScriptCoverage parseCmapCoverage(ByteBuffer buf, TableSlice cmap) {
        if (cmap == null || cmap.length < 4) {
            return new ScriptCoverage(false, false, false);
        }
        int numTables = readUInt16(buf, cmap.offset + 2);
        int cmapEnd = cmap.offset + cmap.length;
        if (4L + (long) numTables * 8L > cmap.length) {
            return new ScriptCoverage(false, false, false);
        }

        int bestSubOffset = -1;
        int bestFormat = -1;
        int bestPriority = -1;

        for (int i = 0; i < numTables; i++) {
            int recOff = cmap.offset + 4 + i * 8;
            int platformId = readUInt16(buf, recOff);
            int encodingId = readUInt16(buf, recOff + 2);
            int relOffset = buf.getInt(recOff + 4);
            if (relOffset < 4 || relOffset + 4 > cmap.length) {
                continue;
            }
            int subOff = cmap.offset + relOffset;
            int format = readUInt16(buf, subOff);
            if (format != 4 && format != 12) {
                continue;
            }
            int priority = scoreCmapSubtable(platformId, encodingId, format);
            if (priority > bestPriority) {
                bestPriority = priority;
                bestFormat = format;
                bestSubOffset = subOff;
            }
        }

        if (bestSubOffset < 0) {
            return new ScriptCoverage(false, false, false);
        }

        boolean hasLatin = countCovered(buf, bestSubOffset, cmapEnd, bestFormat, LATIN_PROBES) >= 3;
        boolean hasHans = countCovered(buf, bestSubOffset, cmapEnd, bestFormat, HANS_PROBES) >= 3;
        boolean hasHant = countCovered(buf, bestSubOffset, cmapEnd, bestFormat, HANT_PROBES) >= 3;
        return new ScriptCoverage(hasLatin, hasHans, hasHant);
    }

    private static int scoreCmapSubtable(int platformId, int encodingId, int format) {
        if (format == 12) {
            if (platformId == 3 && encodingId == 10) return 100;
            if (platformId == 0) return 90;
            return 70;
        }
        if (platformId == 3 && encodingId == 1) return 60;
        if (platformId == 0) return 50;
        return 20;
    }

    private static int countCovered(ByteBuffer buf, int subOff, int cmapEnd, int format, int[] codePoints) {
        int count = 0;
        for (int cp : codePoints) {
            if (format == 12 ? cmapFormat12Contains(buf, subOff, cmapEnd, cp) : cmapFormat4Contains(buf, subOff, cmapEnd, cp)) {
                count++;
            }
        }
        return count;
    }

    private static boolean cmapFormat12Contains(ByteBuffer buf, int subOff, int cmapEnd, int codePoint) {
        if ((long) subOff + 16L > cmapEnd) {
            return false;
        }
        int length = buf.getInt(subOff + 4);
        int subEnd = (length > 0 && (long) subOff + length <= cmapEnd) ? subOff + length : cmapEnd;
        int numGroups = buf.getInt(subOff + 12);
        if (numGroups <= 0 || (long) subOff + 16L + (long) numGroups * 12L > subEnd) {
            return false;
        }
        long target = codePoint & 0xFFFFFFFFL;
        int lo = 0;
        int hi = numGroups - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int gOff = subOff + 16 + mid * 12;
            long startChar = buf.getInt(gOff) & 0xFFFFFFFFL;
            long endChar = buf.getInt(gOff + 4) & 0xFFFFFFFFL;
            if (target < startChar) {
                hi = mid - 1;
            } else if (target > endChar) {
                lo = mid + 1;
            } else {
                long startGlyphId = buf.getInt(gOff + 8) & 0xFFFFFFFFL;
                return startGlyphId + (target - startChar) != 0;
            }
        }
        return false;
    }

    private static boolean cmapFormat4Contains(ByteBuffer buf, int subOff, int cmapEnd, int codePoint) {
        if (codePoint < 0 || codePoint > 0xFFFF || (long) subOff + 16L > cmapEnd) {
            return false;
        }
        int length = readUInt16(buf, subOff + 2);
        int subEnd = (length > 0 && (long) subOff + length <= cmapEnd) ? subOff + length : cmapEnd;
        int segCount = readUInt16(buf, subOff + 6) / 2;
        if (segCount <= 0) {
            return false;
        }
        int endCodesOff = subOff + 14;
        int startCodesOff = endCodesOff + segCount * 2 + 2;
        int idDeltasOff = startCodesOff + segCount * 2;
        int idRangeOffsetsOff = idDeltasOff + segCount * 2;
        if ((long) idRangeOffsetsOff + (long) segCount * 2L > subEnd) {
            return false;
        }

        int lo = 0;
        int hi = segCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int endCode = readUInt16(buf, endCodesOff + mid * 2);
            int startCode = readUInt16(buf, startCodesOff + mid * 2);
            if (codePoint > endCode) {
                lo = mid + 1;
            } else if (codePoint < startCode) {
                hi = mid - 1;
            } else {
                int idDelta = buf.getShort(idDeltasOff + mid * 2);
                int idRangeOffsetPos = idRangeOffsetsOff + mid * 2;
                int idRangeOffset = readUInt16(buf, idRangeOffsetPos);
                if (idRangeOffset == 0) {
                    return ((codePoint + idDelta) & 0xFFFF) != 0;
                }
                int glyphIdAddr = idRangeOffsetPos + idRangeOffset + (codePoint - startCode) * 2;
                if (glyphIdAddr < idRangeOffsetsOff || glyphIdAddr + 2 > subEnd) {
                    return false;
                }
                int glyphId = readUInt16(buf, glyphIdAddr);
                return glyphId != 0 && ((glyphId + idDelta) & 0xFFFF) != 0;
            }
        }
        return false;
    }

    private static int readUInt16(ByteBuffer buf, int offset) {
        return buf.getShort(offset) & 0xFFFF;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String firstNonEmpty(String a, String b, String fallback) {
        if (a != null && !a.isEmpty()) return a;
        if (b != null && !b.isEmpty()) return b;
        return fallback;
    }

    private static String defaultPostScriptName(File file) {
        if (file == null) {
            return "Customized-Regular";
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
