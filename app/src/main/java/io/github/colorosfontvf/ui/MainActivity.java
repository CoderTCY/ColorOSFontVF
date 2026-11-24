package io.github.colorosfontvf.ui;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.app.Activity;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontVariationAxis;
import android.graphics.fonts.SystemFonts;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.colorosfontvf.R;
import io.github.colorosfontvf.font.CustomFontScanner;
import io.github.colorosfontvf.font.FontFamilyPlan;
import io.github.colorosfontvf.font.OpenTypeInspector;

/**
 * Diagnostics and live verification activity for inspecting ColorOS custom font configuration,
 * OpenType tables, variable-font axes, weights 100–900, and OpenType layout features.
 */
public class MainActivity extends Activity {

    private static final String CUSTOM_FONT_DIR = "/data/format_unclear/font/";
    private static final String CUSTOM_FONT_PATH = CUSTOM_FONT_DIR + "Customized-Regular.ttf";
    private static final String LAUNCHER_ALIAS = "io.github.colorosfontvf.ui.LauncherAlias";
    private static final String[] PROBED_AXES = {"wght", "ital", "slnt", "wdth", "opsz", "GRAD", "CASL", "MONO"};
    private static final int[] WEIGHT_STEPS = {100, 200, 300, 400, 500, 600, 700, 800, 900};

    private int mWghtValue = 400;
    private float mWdthValue = 100.0f;
    private float mSlntValue = 0.0f;
    private float mOpszValue = 14.0f;

    private boolean mSupportsWght;
    private boolean mSupportsWdth;
    private boolean mSupportsSlnt;
    private boolean mSupportsOpsz;

    private TextView mPlaygroundPreview;
    private TextView mPlaygroundSettingsLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int basePad = dp(16);
        root.setPadding(basePad, basePad, basePad, basePad);

        scrollView.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets sys = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        root.addView(buildHeaderBanner());
        root.addView(buildCard(getString(R.string.title_settings), buildSettingsContent()));
        root.addView(buildCard(getString(R.string.title_diagnostics), buildDiagnosticsContent()));
        root.addView(buildCard(getString(R.string.title_weight_matrix), buildWeightMatrixContent()));
        root.addView(buildCard(getString(R.string.title_variation_playground), buildPlaygroundContent()));
        root.addView(buildCard(getString(R.string.title_opentype_features), buildOpenTypeFeatureContent()));
        root.addView(buildCard(getString(R.string.title_usage), buildUsageContent()));

        scrollView.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);
    }

    private View buildHeaderBanner() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(12));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        box.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.module_description);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setAlpha(0.75f);
        subtitle.setPadding(0, dp(4), 0, 0);
        box.addView(subtitle);

        return box;
    }

    private View buildCard(String titleText, View content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        card.setPadding(p, p, p, p);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x14808080);
        bg.setStroke(dp(1), 0x22808080);
        card.setBackground(bg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        card.setLayoutParams(cardLp);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        title.setPadding(0, 0, 0, dp(8));
        card.addView(title);

        card.addView(content);
        return card;
    }

    private View buildDiagnosticsContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");

        Typeface defaultTf = Typeface.DEFAULT;
        Typeface sysSansEn = Typeface.create("sys-sans-en", Typeface.NORMAL);
        Typeface condensedTf = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

        sb.append("\nFontCollection Supported Axes (incl. fallbacks):\n");
        sb.append("  DEFAULT: ").append(formatSupportedAxes(defaultTf)).append('\n');
        sb.append("  sys-sans-en: ").append(formatSupportedAxes(sysSansEn)).append('\n');
        sb.append("  sans-serif-condensed: ").append(formatSupportedAxes(condensedTf)).append('\n');

        mSupportsWght = isAxisSupported(defaultTf, "wght");
        mSupportsWdth = isAxisSupported(defaultTf, "wdth");
        mSupportsSlnt = isAxisSupported(defaultTf, "slnt");
        mSupportsOpsz = isAxisSupported(defaultTf, "opsz");

        sb.append("\nSystem Font Map Diagnostics (this process):\n");
        appendSystemFontDiagnostics(sb);

        sb.append("\nCustom Font File Inspection:\n");
        File customFile = new File(CUSTOM_FONT_PATH);
        if (!customFile.exists()) {
            sb.append("  ").append(CUSTOM_FONT_PATH).append(" not found (using system default font).");
        } else if (!customFile.canRead()) {
            sb.append("  ").append(CUSTOM_FONT_PATH).append(" exists but is NOT readable by app UID!");
        } else {
            CustomFontScanner.ScannedCustomFontSet set = CustomFontScanner.scan(customFile, Locale.getDefault());
            sb.append("  File: ").append(customFile.getAbsolutePath())
                    .append(" (").append(customFile.length() / 1024).append(" KB)\n");
            if (set.isEmpty()) {
                sb.append("  Failed to parse SFNT/TTC tables.\n");
            } else {
                OpenTypeInspector.FontFaceInfo primary = set.sansFaces.get(0);
                sb.append("  Format: ").append(set.sansFaces.size() > 1 ? "Multi-face/TTC/Companions" : "Single SFNT")
                        .append(" | Primary PostScript: ").append(primary.postScriptName).append('\n');
                sb.append("  OS/2 Weight=").append(primary.weight).append(" Italic=").append(primary.italic).append('\n');
                sb.append("  fvar Axes: ").append(primary.axes.isEmpty() ? "NONE (static font)" : primary.axes).append('\n');
                sb.append("  cmap: Latin=").append(primary.coverage.hasLatin)
                        .append(" Hans=").append(primary.coverage.hasHans)
                        .append(" Hant=").append(primary.coverage.hasHant).append('\n');
                sb.append("  Layout tables: GSUB=").append(primary.hasGsub)
                        .append(" GPOS=").append(primary.hasGpos).append('\n');

                FontFamilyPlan.FamilySpec plan = FontFamilyPlan.buildFamilySpec(set.sansFaces, "", "");
                sb.append("  Planned FontConfig: entries=").append(plan.entries.size())
                        .append(", varFamilyType=").append(plan.expectedVarFamilyType)
                        .append(", serif=").append(!set.serifFaces.isEmpty())
                        .append(", mono=").append(!set.monoFaces.isEmpty())
                        .append(", condensed=").append(!set.condensedFaces.isEmpty() || primary.supportsWdth()).append('\n');

                sb.append("  Companion files (Customized-*):\n");
                File[] companions = customFile.getParentFile() != null
                        ? customFile.getParentFile().listFiles((d, n) ->
                                n.startsWith("Customized-") && n.endsWith(".ttf"))
                        : null;
                if (companions == null || companions.length == 0) {
                    sb.append("    (none found)\n");
                } else {
                    for (File c : companions) {
                        sb.append("    ").append(c.getName())
                                .append(": readable=").append(c.canRead()).append('\n');
                    }
                }

                sb.append("  Planned entry precheck (Font.Builder):\n");
                for (FontFamilyPlan.FontEntrySpec entry : plan.entries) {
                    String result;
                    try {
                        Font.Builder builder = new Font.Builder(entry.file)
                                .setTtcIndex(entry.ttcIndex)
                                .setWeight(entry.weight)
                                .setSlant(entry.slant);
                        if (entry.variationSettings != null && !entry.variationSettings.isEmpty()) {
                            builder.setFontVariationSettings(entry.variationSettings);
                        }
                        builder.build();
                        result = "OK";
                    } catch (Throwable t) {
                        result = "FAIL: " + t.getMessage();
                    }
                    sb.append("    ").append(entry.file.getName()).append('#').append(entry.ttcIndex)
                            .append(" w=").append(entry.weight).append(" sl=").append(entry.slant)
                            .append(" -> ").append(result).append('\n');
                }
                OpenTypeInspector.AxisInfo wdth = primary.getAxis("wdth");
                if (wdth != null) mWdthValue = wdth.defaultValue;
                OpenTypeInspector.AxisInfo slnt = primary.getAxis("slnt");
                if (slnt != null) mSlntValue = slnt.defaultValue;
                OpenTypeInspector.AxisInfo opsz = primary.getAxis("opsz");
                if (opsz != null) mOpszValue = opsz.defaultValue;
            }
        }

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        return tv;
    }

    private View buildWeightMatrixContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        for (int weight : WEIGHT_STEPS) {
            TextView row = new TextView(this);
            row.setText(String.format(Locale.ROOT, "w%-3d  永和九年 Abcdefg 0123456789", weight));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            row.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
            row.setPadding(0, dp(2), 0, dp(2));
            box.addView(row);
        }

        TextView italicRow = new TextView(this);
        italicRow.setText("400i  Italic 永和九年 Abcdefg 0123456789");
        italicRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        italicRow.setTypeface(Typeface.create(Typeface.DEFAULT, 400, true));
        italicRow.setPadding(0, dp(4), 0, dp(2));
        box.addView(italicRow);

        TextView boldItalicRow = new TextView(this);
        boldItalicRow.setText("700i  BoldItalic 永和九年 Abcdefg 0123456789");
        boldItalicRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        boldItalicRow.setTypeface(Typeface.create(Typeface.DEFAULT, 700, true));
        boldItalicRow.setPadding(0, dp(2), 0, dp(2));
        box.addView(boldItalicRow);

        TextView condensedRow = new TextView(this);
        condensedRow.setText("cond  Condensed 永和九年 Abcdefg 0123456789");
        condensedRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        condensedRow.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        condensedRow.setPadding(0, dp(2), 0, dp(2));
        box.addView(condensedRow);

        return box;
    }

    private View buildPlaygroundContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        mPlaygroundPreview = new TextView(this);
        mPlaygroundPreview.setText("ColorOSFontVF 可变字体预览\nThe quick brown fox jumps 0123456789");
        mPlaygroundPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mPlaygroundPreview.setPadding(0, dp(4), 0, dp(8));
        box.addView(mPlaygroundPreview);

        mPlaygroundSettingsLabel = new TextView(this);
        mPlaygroundSettingsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mPlaygroundSettingsLabel.setTypeface(Typeface.MONOSPACE);
        box.addView(mPlaygroundSettingsLabel);

        addAxisSlider(box, "wght", 100, 1000, mWghtValue, 10, value -> {
            mWghtValue = Math.round(value);
            updatePlayground();
        });

        if (mSupportsWdth) {
            addAxisSlider(box, "wdth", 50, 150, Math.round(mWdthValue), 5, value -> {
                mWdthValue = value;
                updatePlayground();
            });
        }
        if (mSupportsSlnt) {
            addAxisSlider(box, "slnt", -15, 15, Math.round(mSlntValue), 1, value -> {
                mSlntValue = value;
                updatePlayground();
            });
        }
        if (mSupportsOpsz) {
            addAxisSlider(box, "opsz", 8, 144, Math.round(mOpszValue), 1, value -> {
                mOpszValue = value;
                updatePlayground();
            });
        }

        updatePlayground();
        return box;
    }

    private interface IntSliderListener {
        void onChanged(int value);
    }

    private void addAxisSlider(
            LinearLayout parent,
            String tag,
            int min,
            int max,
            int initial,
            int step,
            IntSliderListener listener) {
        boolean supported = isAxisSupported(Typeface.DEFAULT, tag);
        TextView label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setPadding(0, dp(6), 0, 0);
        label.setText(String.format(Locale.ROOT, "Axis '%s': %d (supported=%s)", tag, initial, supported));
        parent.addView(label);

        SeekBar seekBar = new SeekBar(this);
        int range = Math.max(1, (max - min) / step);
        seekBar.setMax(range);
        seekBar.setProgress(Math.max(0, Math.min(range, (initial - min) / step)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = min + progress * step;
                label.setText(String.format(Locale.ROOT, "Axis '%s': %d (supported=%s)", tag, val, supported));
                listener.onChanged(val);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        parent.addView(seekBar);
    }

    private void updatePlayground() {
        StringBuilder var = new StringBuilder();
        var.append("'wght' ").append(mWghtValue);
        if (mSupportsWdth) {
            var.append(",'wdth' ").append(Math.round(mWdthValue));
        }
        if (mSupportsSlnt) {
            var.append(",'slnt' ").append(Math.round(mSlntValue));
        }
        if (mSupportsOpsz) {
            var.append(",'opsz' ").append(Math.round(mOpszValue));
        }
        String settings = var.toString();
        boolean applied = mPlaygroundPreview.setFontVariationSettings(settings);
        mPlaygroundSettingsLabel.setText("setFontVariationSettings(\"" + settings + "\") -> " + applied);
    }

    private View buildOpenTypeFeatureContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        addFeatureRow(box, "Default", null, "fi fl ffi -> => !=  0123456789 11111 88888  Small Caps 2026");
        addFeatureRow(box, "tnum (Tabular)", "'tnum' 1", "11111.00  88888.00  0123456789");
        addFeatureRow(box, "pnum (Proportional)", "'pnum' 1", "11111.00  88888.00  0123456789");
        addFeatureRow(box, "zero (Slashed 0)", "'zero' 1", "0O0O  2026-11-22  0123456789");
        addFeatureRow(box, "liga + calt", "'liga' 1, 'calt' 1", "fi fl ffi -> => != === <= >=");
        addFeatureRow(box, "smcp (Small Caps)", "'smcp' 1", "ColorOSFontVF Small Caps 2026");

        return box;
    }

    private void addFeatureRow(LinearLayout parent, String label, String featureSettings, String sample) {
        TextView title = new TextView(this);
        title.setText(label + (featureSettings != null ? " [" + featureSettings + "]" : ""));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setAlpha(0.7f);
        title.setPadding(0, dp(4), 0, 0);
        parent.addView(title);

        TextView text = new TextView(this);
        text.setText(sample);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (featureSettings != null) {
            text.setFontFeatureSettings(featureSettings);
        }
        parent.addView(text);
    }

    private View buildSettingsContent() {
        Switch hideIconSwitch = new Switch(this);
        hideIconSwitch.setText(R.string.switch_hide_icon);
        hideIconSwitch.setChecked(isIconHidden());
        hideIconSwitch.setOnCheckedChangeListener((btn, checked) -> {
            setIconHidden(checked);
            Toast.makeText(
                    this,
                    checked ? R.string.toast_icon_hidden : R.string.toast_icon_shown,
                    Toast.LENGTH_SHORT
            ).show();
        });
        return hideIconSwitch;
    }

    private boolean isIconHidden() {
        return getPackageManager().getComponentEnabledSetting(
                new ComponentName(this, LAUNCHER_ALIAS))
                != PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void setIconHidden(boolean hidden) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, LAUNCHER_ALIAS),
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    private View buildUsageContent() {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setText("1. Open Vector Manager -> Modules -> Enable ColorOSFontVF.\n"
                + "2. Ensure scope includes 'system' (required) and 'com.android.settings' (recommended).\n"
                + "3. Place your variable or static font at:\n"
                + "   /data/format_unclear/font/Customized-Regular.ttf (chmod 0644).\n"
                + "4. Optional companion filenames in /data/format_unclear/font/:\n"
                + "   - Styles: Customized-Italic.ttf, Customized-Thin..Black.ttf, *Italic.ttf\n"
                + "   - Condensed: Customized-Condensed[.ttf|Bold|Italic|BoldItalic]\n"
                + "   - Serif: Customized-Serif[.ttf|Bold|Italic|BoldItalic]\n"
                + "   - Mono: Customized-Mono[.ttf|Bold|Italic|BoldItalic]\n"
                + "5. Reboot device so system_server rebuilds FontConfig + SharedMemory(\"fontMap\").");
        return tv;
    }

    private static boolean isAxisSupported(Typeface tf, String axisTag) {
        if (tf == null || axisTag == null) {
            return false;
        }
        Paint paint = new Paint();
        paint.setTypeface(tf);
        return paint.setFontVariationSettings("'" + axisTag + "' 1");
    }

    private String formatSupportedAxes(Typeface tf) {
        if (tf == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("[");
        int count = 0;
        for (String axis : PROBED_AXES) {
            if (isAxisSupported(tf, axis)) {
                if (count++ > 0) out.append(", ");
                out.append(axis);
            }
        }
        return count == 0 ? "NONE" : out.append(']').toString();
    }

    private void appendSystemFontDiagnostics(StringBuilder sb) {
        Typeface individual = Typeface.create("individual-font", Typeface.NORMAL);
        boolean hasIndividual = individual != null && !individual.equals(Typeface.DEFAULT);

        Set<Font> available = SystemFonts.getAvailableFonts();
        List<String> activeCustomEntries = new ArrayList<>();
        for (Font font : available) {
            File file = font.getFile();
            if (file != null && file.getAbsolutePath().startsWith(CUSTOM_FONT_DIR)) {
                FontVariationAxis[] axes = font.getAxes();
                activeCustomEntries.add(String.format(
                        Locale.ROOT,
                        "%s#%d w=%d sl=%d axes=%s",
                        file.getName(),
                        font.getTtcIndex(),
                        font.getStyle().getWeight(),
                        font.getStyle().getSlant(),
                        axes != null ? Arrays.toString(axes) : "[]"
                ));
            }
        }

        String mode;
        if (!hasIndividual && !activeCustomEntries.isEmpty()) {
            mode = "ColorOSFontVF native FontConfig mode";
        } else if (hasIndividual) {
            mode = "Stock OPlus FlipFont mode ('individual-font' present)";
        } else {
            mode = "System default (no custom font injected)";
        }

        sb.append("  Mode: ").append(mode).append('\n');
        sb.append("  SystemFonts total=").append(available.size())
                .append("  has 'individual-font'=").append(hasIndividual).append('\n');
        if (!activeCustomEntries.isEmpty()) {
            sb.append("  Active custom SystemFonts entries (").append(activeCustomEntries.size()).append("):\n");
            for (int i = 0; i < Math.min(activeCustomEntries.size(), 8); i++) {
                sb.append("    - ").append(activeCustomEntries.get(i)).append('\n');
            }
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
