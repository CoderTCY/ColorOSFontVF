package io.github.colorosfontvf.font;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests covering OpenType SFNT/TTC inspection, cmap script probing,
 * companion scanning, and Android Minikin VariableFamilyType planning.
 */
public class OpenTypeAndPlanTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void inspectStaticVariantsAndPostItalicFallback() {
        byte[] regularBytes = buildSyntheticSfnt(400, false, false, "Demo-Regular", "Demo", "Regular",
                Collections.emptyList(), new int[]{'A', 'a', 'e', '0'}, false);
        List<OpenTypeInspector.FontFaceInfo> regFaces =
                OpenTypeInspector.inspect(ByteBuffer.wrap(regularBytes), new File("Customized-Regular.ttf"));
        assertEquals(1, regFaces.size());
        OpenTypeInspector.FontFaceInfo reg = regFaces.get(0);
        assertEquals(400, reg.weight);
        assertFalse(reg.italic);
        assertEquals("Demo-Regular", reg.postScriptName);
        assertTrue(reg.coverage.hasLatin);
        assertFalse(reg.coverage.hasHans);

        byte[] boldBytes = buildSyntheticSfnt(700, false, false, "Demo-Bold", "Demo", "Bold",
                Collections.emptyList(), new int[]{'A', 'a', 'e', '0'}, false);
        OpenTypeInspector.FontFaceInfo bold =
                OpenTypeInspector.inspect(ByteBuffer.wrap(boldBytes), new File("Customized-Bold.ttf")).get(0);
        assertEquals(700, bold.weight);
        assertFalse(bold.italic);

        byte[] postItalicBytes = buildSyntheticSfnt(400, false, true, "Demo-Oblique", "Demo", "Oblique",
                Collections.emptyList(), new int[]{'A', 'a', 'e', '0'}, false);
        OpenTypeInspector.FontFaceInfo postItalic =
                OpenTypeInspector.inspect(ByteBuffer.wrap(postItalicBytes), new File("Customized-Italic.ttf")).get(0);
        assertTrue(postItalic.italic);

        FontFamilyPlan.FamilySpec spec = FontFamilyPlan.buildFamilySpec(
                Arrays.asList(bold, postItalic, reg), "", "");
        assertEquals(0, spec.expectedVarFamilyType);
        assertEquals(3, spec.entries.size());
        assertEquals(400, spec.entries.get(0).weight);
        assertEquals(0, spec.entries.get(0).slant);
        assertEquals(700, spec.entries.get(1).weight);
        assertEquals(0, spec.entries.get(1).slant);
        assertEquals(1, spec.entries.get(2).slant);
    }

    @Test
    public void planSingleWghtVariableFont() {
        byte[] vfBytes = buildSyntheticSfnt(400, false, false, "Inter-VF", "Inter", "Regular",
                Collections.singletonList(new AxisDef("wght", 100f, 400f, 900f)),
                new int[]{'A', 'a', 'e', '0'}, false);
        OpenTypeInspector.FontFaceInfo face =
                OpenTypeInspector.inspect(ByteBuffer.wrap(vfBytes), new File("Customized-Regular.ttf")).get(0);

        assertTrue(face.supportsWght());
        assertFalse(face.supportsItal());

        FontFamilyPlan.FamilySpec spec = FontFamilyPlan.buildFamilySpec(
                Collections.singletonList(face), "", "");
        assertEquals(1, spec.expectedVarFamilyType);
        assertEquals(1, spec.entries.size());
        assertEquals(FontFamilyPlan.VAR_TYPE_AXES_WGHT, spec.entries.get(0).varTypeAxes);
        assertEquals(400, spec.entries.get(0).weight);
        assertEquals(0, spec.entries.get(0).slant);
    }

    @Test
    public void planSingleWghtItalVariableFont() {
        byte[] vfBytes = buildSyntheticSfnt(400, false, false, "Mono-VF", "Mono", "Regular",
                Arrays.asList(new AxisDef("wght", 100f, 400f, 800f), new AxisDef("ital", 0f, 0f, 1f)),
                new int[]{'A', 'a', 'e', '0'}, false);
        OpenTypeInspector.FontFaceInfo face =
                OpenTypeInspector.inspect(ByteBuffer.wrap(vfBytes), new File("Customized-Regular.ttf")).get(0);

        FontFamilyPlan.FamilySpec spec = FontFamilyPlan.buildFamilySpec(
                Collections.singletonList(face), "", "");
        assertEquals(2, spec.expectedVarFamilyType);
        assertEquals(1, spec.entries.size());
        assertEquals(FontFamilyPlan.VAR_TYPE_AXES_WGHT | FontFamilyPlan.VAR_TYPE_AXES_ITAL,
                spec.entries.get(0).varTypeAxes);
    }

    @Test
    public void planSingleWghtSlntVariableFont() {
        byte[] vfBytes = buildSyntheticSfnt(400, false, false, "Flex-VF", "Flex", "Regular",
                Arrays.asList(new AxisDef("wght", 100f, 400f, 1000f), new AxisDef("slnt", -12f, 0f, 0f)),
                new int[]{'A', 'a', 'e', '0'}, false);
        OpenTypeInspector.FontFaceInfo face =
                OpenTypeInspector.inspect(ByteBuffer.wrap(vfBytes), new File("Customized-Regular.ttf")).get(0);

        FontFamilyPlan.FamilySpec spec = FontFamilyPlan.buildFamilySpec(
                Collections.singletonList(face), "", "");
        assertEquals(3, spec.expectedVarFamilyType);
        assertEquals(2, spec.entries.size());
        assertEquals(0, spec.entries.get(0).slant);
        assertEquals("", spec.entries.get(0).variationSettings);
        assertEquals(1, spec.entries.get(1).slant);
        assertEquals("'slnt' -12", spec.entries.get(1).variationSettings);
        assertEquals(FontFamilyPlan.VAR_TYPE_AXES_WGHT, spec.entries.get(0).varTypeAxes);
        assertEquals(FontFamilyPlan.VAR_TYPE_AXES_WGHT, spec.entries.get(1).varTypeAxes);
    }

    @Test
    public void planCondensedFromWdthAxis() throws IOException {
        byte[] vfBytes = buildSyntheticSfnt(400, false, false, "Wide-VF", "Wide", "Regular",
                Arrays.asList(new AxisDef("wght", 100f, 400f, 900f), new AxisDef("wdth", 75f, 100f, 125f)),
                new int[]{'A', 'a', 'e', '0'}, false);
        File primary = tempFolder.newFile("Customized-Regular.ttf");
        Files.write(primary.toPath(), vfBytes);

        CustomFontScanner.ScannedCustomFontSet scanned = CustomFontScanner.scan(primary, Locale.SIMPLIFIED_CHINESE);
        FontFamilyPlan.FamilySpec condensed = FontFamilyPlan.buildCondensedSpec(scanned);
        assertEquals(1, condensed.expectedVarFamilyType);
        assertEquals(1, condensed.entries.size());
        assertEquals("'wdth' 75", condensed.entries.get(0).variationSettings);
    }

    @Test
    public void planUprightAndItalicVfPairOrderedCorrectly() {
        byte[] upright = buildSyntheticSfnt(400, false, false, "Serif-VF", "Serif", "Regular",
                Collections.singletonList(new AxisDef("wght", 100f, 400f, 900f)),
                new int[]{'A', 'a', 'e', '0'}, false);
        byte[] italic = buildSyntheticSfnt(400, true, false, "Serif-Italic-VF", "Serif", "Italic",
                Collections.singletonList(new AxisDef("wght", 100f, 400f, 900f)),
                new int[]{'A', 'a', 'e', '0'}, false);

        OpenTypeInspector.FontFaceInfo upInfo =
                OpenTypeInspector.inspect(ByteBuffer.wrap(upright), new File("Customized-Regular.ttf")).get(0);
        OpenTypeInspector.FontFaceInfo itInfo =
                OpenTypeInspector.inspect(ByteBuffer.wrap(italic), new File("Customized-Italic.ttf")).get(0);

        // Pass italic before upright to verify planner always orders upright first, italic second.
        FontFamilyPlan.FamilySpec spec = FontFamilyPlan.buildFamilySpec(
                Arrays.asList(itInfo, upInfo), "", "");
        assertEquals(3, spec.expectedVarFamilyType);
        assertEquals(2, spec.entries.size());
        assertEquals(0, spec.entries.get(0).slant);
        assertEquals("Serif-VF", spec.entries.get(0).postScriptName);
        assertEquals(1, spec.entries.get(1).slant);
        assertEquals("Serif-Italic-VF", spec.entries.get(1).postScriptName);
    }

    @Test
    public void inspectMultiFaceTtcAndSelectRegionalCjkFallbacks() throws IOException {
        int[] hansCps = {'A', 'a', 'e', '0', 0x4E2D, 0x56FD, 0x6C49, 0x8BED, 0x4F53};
        int[] hantCps = {'A', 'a', 'e', '0', 0x570B, 0x6F22, 0x8A9E, 0x9AD4, 0x81FA};

        byte[] jpFace = buildSyntheticSfnt(400, false, false, "Sarasa-Gothic-J", "Sarasa Gothic J", "Regular",
                Collections.singletonList(new AxisDef("wght", 100f, 400f, 900f)), hansCps, true);
        byte[] scFace = buildSyntheticSfnt(400, false, false, "Sarasa-Gothic-SC", "Sarasa Gothic SC", "Regular",
                Collections.singletonList(new AxisDef("wght", 100f, 400f, 900f)), hansCps, true);
        byte[] tcFace = buildSyntheticSfnt(400, false, false, "Sarasa-Gothic-TC", "Sarasa Gothic TC", "Regular",
                Collections.singletonList(new AxisDef("wght", 100f, 400f, 900f)), hantCps, true);

        byte[] ttcBytes = buildSyntheticTtc(Arrays.asList(jpFace, scFace, tcFace));
        File ttcFile = tempFolder.newFile("Customized-Regular.ttf");
        Files.write(ttcFile.toPath(), ttcBytes);

        CustomFontScanner.ScannedCustomFontSet scSet = CustomFontScanner.scan(ttcFile, Locale.SIMPLIFIED_CHINESE);
        assertEquals(1, scSet.sansFaces.size());
        assertEquals(1, scSet.sansFaces.get(0).ttcIndex);
        assertEquals("Sarasa-Gothic-SC", scSet.sansFaces.get(0).postScriptName);
        assertTrue(scSet.sansFaces.get(0).coverage.hasHans);

        assertEquals(1, scSet.hansFaces.size());
        assertEquals(1, scSet.hansFaces.get(0).ttcIndex);
        assertEquals(1, scSet.hantFaces.size());
        assertEquals(2, scSet.hantFaces.get(0).ttcIndex);
        assertTrue(scSet.hantFaces.get(0).coverage.hasHant);
    }

    @Test
    public void cmapFormat4AndCorruptBufferResilience() {
        byte[] fmt4Font = buildSyntheticSfnt(400, false, false, "Format4-Hans", "Format4", "Regular",
                Collections.emptyList(), new int[]{'A', 'a', 'e', '0', 0x4E2D, 0x56FD, 0x6C49, 0x8BED}, false);
        OpenTypeInspector.FontFaceInfo info =
                OpenTypeInspector.inspect(ByteBuffer.wrap(fmt4Font), new File("fmt4.ttf")).get(0);
        assertTrue(info.coverage.hasLatin);
        assertTrue(info.coverage.hasHans);
        assertFalse(info.coverage.hasHant);

        assertTrue(OpenTypeInspector.inspect((ByteBuffer) null, null).isEmpty());
        assertTrue(OpenTypeInspector.inspect(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}), null).isEmpty());
        assertTrue(OpenTypeInspector.inspect(ByteBuffer.wrap(new byte[64]), null).isEmpty());
        assertNotNull(OpenTypeInspector.inspect(new File("nonexistent_font_file.ttf")));
    }

    // ---- Synthetic SFNT / TTC Test Fixtures ----

    private static final class AxisDef {
        final String tag;
        final float min;
        final float def;
        final float max;

        AxisDef(String tag, float min, float def, float max) {
            this.tag = tag;
            this.min = min;
            this.def = def;
            this.max = max;
        }
    }

    private static byte[] buildSyntheticTtc(List<byte[]> sfntFaces) throws IOException {
        // Combine raw tables safely by shifting offsets via wrapper SFNT builders:
        // Actually in TTC, table offsets inside each SFNT directory are relative to start of TTC file.
        // Here each sfntFace has its own header+tables starting at offset 0; we shift each tableOffset
        // inside its table directory by the face's placement offset in the TTC!
        int headerSize = 12 + sfntFaces.size() * 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(0x74746366); // ttcf
        dos.writeShort(1);
        dos.writeShort(0);
        dos.writeInt(sfntFaces.size());

        int cursor = headerSize;
        int[] faceOffsets = new int[sfntFaces.size()];
        for (int i = 0; i < sfntFaces.size(); i++) {
            faceOffsets[i] = cursor;
            dos.writeInt(cursor);
            cursor += sfntFaces.get(i).length;
        }

        for (int i = 0; i < sfntFaces.size(); i++) {
            byte[] face = Arrays.copyOf(sfntFaces.get(i), sfntFaces.get(i).length);
            ByteBuffer bb = ByteBuffer.wrap(face);
            int numTables = bb.getShort(4) & 0xFFFF;
            for (int t = 0; t < numTables; t++) {
                int offPos = 12 + t * 16 + 8;
                int relTableOff = bb.getInt(offPos);
                bb.putInt(offPos, relTableOff + faceOffsets[i]);
            }
            dos.write(face);
        }
        dos.flush();
        return out.toByteArray();
    }

    private static byte[] buildSyntheticSfnt(
            int weight,
            boolean os2Italic,
            boolean postItalic,
            String postScriptName,
            String familyName,
            String subfamilyName,
            List<AxisDef> axes,
            int[] codePoints,
            boolean useCmapFormat12) {
        try {
            Map<String, byte[]> tables = new LinkedHashMap<>();
            tables.put("OS/2", buildOs2Table(weight, os2Italic));
            tables.put("head", buildHeadTable(false));
            tables.put("post", buildPostTable(postItalic));
            tables.put("name", buildNameTable(familyName, subfamilyName, postScriptName));
            tables.put("cmap", useCmapFormat12 ? buildCmapFormat12(codePoints) : buildCmapFormat4(codePoints));
            if (!axes.isEmpty()) {
                tables.put("fvar", buildFvarTable(axes));
            }
            tables.put("GSUB", new byte[]{0, 1, 0, 0});
            tables.put("GPOS", new byte[]{0, 1, 0, 0});

            int numTables = tables.size();
            int dirEnd = 12 + numTables * 16;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeInt(0x00010000);
            dos.writeShort(numTables);
            dos.writeShort(16);
            dos.writeShort(0);
            dos.writeShort(0);

            int offset = dirEnd;
            for (Map.Entry<String, byte[]> e : tables.entrySet()) {
                byte[] tagBytes = e.getKey().getBytes(StandardCharsets.US_ASCII);
                dos.write(tagBytes, 0, 4);
                dos.writeInt(0);
                dos.writeInt(offset);
                dos.writeInt(e.getValue().length);
                offset += (e.getValue().length + 3) & ~3;
            }
            for (byte[] data : tables.values()) {
                dos.write(data);
                int pad = ((data.length + 3) & ~3) - data.length;
                for (int p = 0; p < pad; p++) dos.writeByte(0);
            }
            dos.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] buildOs2Table(int weight, boolean italic) {
        byte[] buf = new byte[78];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.putShort(4, (short) weight);
        bb.putShort(62, (short) (italic ? 0x0001 : 0x0040));
        return buf;
    }

    private static byte[] buildHeadTable(boolean italic) {
        byte[] buf = new byte[54];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.putShort(44, (short) (italic ? 0x0002 : 0x0000));
        return buf;
    }

    private static byte[] buildPostTable(boolean italicAngleNonZero) {
        byte[] buf = new byte[32];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.putInt(0, 0x00030000);
        bb.putInt(4, italicAngleNonZero ? (-12 << 16) : 0);
        return buf;
    }

    private static byte[] buildFvarTable(List<AxisDef> axes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeShort(1); // major
        dos.writeShort(0); // minor
        dos.writeShort(16); // axesArrayOffset
        dos.writeShort(2); // reserved
        dos.writeShort(axes.size());
        dos.writeShort(20); // axisSize
        dos.writeShort(0); // instanceCount
        dos.writeShort(0); // instanceSize
        for (AxisDef axis : axes) {
            dos.write(axis.tag.getBytes(StandardCharsets.US_ASCII), 0, 4);
            dos.writeInt(Math.round(axis.min * 65536f));
            dos.writeInt(Math.round(axis.def * 65536f));
            dos.writeInt(Math.round(axis.max * 65536f));
            dos.writeShort(0);
            dos.writeShort(256);
        }
        dos.flush();
        return out.toByteArray();
    }

    private static byte[] buildNameTable(String family, String subfamily, String psName) throws IOException {
        int[] ids = {1, 2, 4, 6};
        String[] vals = {family, subfamily, family + " " + subfamily, psName};
        ByteArrayOutputStream strStorage = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);
        int headerSize = 6 + ids.length * 12;
        dos.writeShort(0); // format
        dos.writeShort(ids.length);
        dos.writeShort(headerSize);
        for (int i = 0; i < ids.length; i++) {
            byte[] encoded = vals[i].getBytes(StandardCharsets.UTF_16BE);
            int strOffset = strStorage.size();
            strStorage.write(encoded);
            dos.writeShort(3); // Windows
            dos.writeShort(1); // Unicode BMP
            dos.writeShort(0x0409); // en-US
            dos.writeShort(ids[i]);
            dos.writeShort(encoded.length);
            dos.writeShort(strOffset);
        }
        dos.write(strStorage.toByteArray());
        dos.flush();
        return out.toByteArray();
    }

    private static byte[] buildCmapFormat12(int[] codePoints) throws IOException {
        int[] sorted = Arrays.copyOf(codePoints, codePoints.length);
        Arrays.sort(sorted);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeShort(0); // version
        dos.writeShort(1); // numTables
        dos.writeShort(3); // platform Windows
        dos.writeShort(10); // encoding UCS-4
        dos.writeInt(12); // subtable offset
        int subLen = 16 + sorted.length * 12;
        dos.writeShort(12); // format 12
        dos.writeShort(0);
        dos.writeInt(subLen);
        dos.writeInt(0);
        dos.writeInt(sorted.length);
        for (int i = 0; i < sorted.length; i++) {
            dos.writeInt(sorted[i]);
            dos.writeInt(sorted[i]);
            dos.writeInt(i + 1);
        }
        dos.flush();
        return out.toByteArray();
    }

    private static byte[] buildCmapFormat4(int[] codePoints) throws IOException {
        int[] sorted = Arrays.copyOf(codePoints, codePoints.length);
        Arrays.sort(sorted);
        int segCount = sorted.length + 1;
        int subLen = 16 + segCount * 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeShort(0);
        dos.writeShort(1);
        dos.writeShort(3);
        dos.writeShort(1);
        dos.writeInt(12);

        dos.writeShort(4); // format 4
        dos.writeShort(subLen);
        dos.writeShort(0);
        dos.writeShort(segCount * 2);
        dos.writeShort(2);
        dos.writeShort(0);
        dos.writeShort(0);
        for (int cp : sorted) dos.writeShort(cp);
        dos.writeShort(0xFFFF);
        dos.writeShort(0); // reservedPad
        for (int cp : sorted) dos.writeShort(cp);
        dos.writeShort(0xFFFF);
        for (int i = 0; i < sorted.length; i++) dos.writeShort(1 - sorted[i] + i);
        dos.writeShort(1);
        for (int i = 0; i < segCount; i++) dos.writeShort(0);
        dos.flush();
        return out.toByteArray();
    }
}
