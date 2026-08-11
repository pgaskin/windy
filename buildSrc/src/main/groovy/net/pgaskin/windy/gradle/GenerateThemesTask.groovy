// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

package net.pgaskin.windy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class GenerateThemesTask extends DefaultTask {
    static final String PKG = "net.pgaskin.windy"
    static final String OUTER = "WindyWallpaperService"
    static final String BASE = "WindyWallpaperServiceBase"
    static final String THEMES = "Themes"
    static final String STYLES = "Styles"
    static final String SETTINGS = "SettingsActivity"

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getThemeConfig()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getStyleConfig()

    @OutputDirectory
    abstract DirectoryProperty getJavaOutputDir()

    @OutputDirectory
    abstract DirectoryProperty getResOutputDir()

    @OutputFile
    abstract RegularFileProperty getManifestOutput()

    @TaskAction
    void generate() {
        def themes = parseThemes(themeConfig.get().asFile.text)
        def styles = parseStyles(styleConfig.get().asFile.text)

        def javaRoot = javaOutputDir.get().asFile
        javaRoot.deleteDir()
        def pkgDir = new File(javaRoot, PKG.replace('.', '/'))
        pkgDir.mkdirs()
        new File(pkgDir, "${OUTER}.java").setText(renderJava(themes), "UTF-8")
        new File(pkgDir, "${THEMES}.java").setText(renderThemesJava(themes), "UTF-8")
        new File(pkgDir, "${STYLES}.java").setText(renderStylesJava(styles), "UTF-8")

        def resRoot = resOutputDir.get().asFile
        resRoot.deleteDir()
        def xmlDir = new File(resRoot, "xml")
        xmlDir.mkdirs()
        themes.each { t ->
            new File(xmlDir, "${t.resName}.xml").setText(renderWallpaperXml(t), "UTF-8")
        }

        def manifest = manifestOutput.get().asFile
        manifest.parentFile.mkdirs()
        manifest.setText(renderManifest(themes), "UTF-8")
    }

    static List<Map> parseThemes(String src) {
        def m = (src =~ /(?s)pub const ALL\s*:[^=]*=\s*&\[(.*?)]\s*;/)
        if (!m.find()) {
            throw new GradleException("Could not find `Theme::ALL` array in config.rs")
        }
        def themes = []
        m.group(1).eachLine { line ->
            def entry = (line =~ /Theme::([A-Z0-9_]+)\s*,/)
            if (!entry.find()) {
                return
            }
            def ident = entry.group(1)
            def label = (line =~ /\/\/\s*(\S.*?)\s*$/)
            if (!label.find()) {
                throw new GradleException("Theme `Theme::${ident}` is missing `// Label` comment in config.rs")
            }
            def full = label.group(1)
            def sep = full.lastIndexOf(", ")
            themes << [
                index    : themes.size(),
                ident    : ident,
                className: toPascal(ident),
                resName  : "windy_" + ident.toLowerCase().replace("_", ""),
                label    : full,
                // just the theme name, i.e. "Windy, Deep blue" -> "Deep blue"
                name     : sep < 0 ? full : full.substring(sep + 2),
            ]
        }
        if (themes.isEmpty()) {
            throw new GradleException("No themes parsed from `Theme::ALL` in config.rs")
        }
        return themes
    }

    static List<String> parseStyles(String src) {
        def m = (src =~ /(?s)pub const ALL\s*:[^=]*=\s*&\[(.*?)]\s*;/)
        if (!m.find()) {
            throw new GradleException("Could not find `Style::ALL` array in color.rs")
        }
        def styles = []
        (m.group(1) =~ /Style::([A-Z0-9_]+)/).each { match ->
            def ident = match[1]
            def name = (src =~ /(?s)pub const ${ident}\s*:\s*Style\s*=\s*Style\s*\{.*?name\s*:\s*"([^"]*)"/)
            if (!name.find()) {
                throw new GradleException("Could not find the name of `Style::${ident}` in color.rs")
            }
            styles << name.group(1)
        }
        if (styles.isEmpty()) {
            throw new GradleException("No styles parsed from `Style::ALL` in color.rs")
        }
        return styles
    }

    static String renderStylesJava(List<String> styles) {
        def sb = new StringBuilder()
        sb << "package ${PKG};\n\n"
        sb << "/** Generated theme styles, generated from core/src/color.rs; do not edit. */\n"
        sb << "public final class ${STYLES} {\n"
        sb << "    /** Style names, indexed by the value shared with the native renderer. */\n"
        sb << "    public static final String[] ALL = {\n"
        styles.each { sb << "        ${javaStr(it)},\n" }
        sb << "    };\n\n"
        sb << "    private ${STYLES}() {\n"
        sb << "    }\n"
        sb << "}\n"
        sb.toString()
    }

    static String toPascal(String ident) {
        ident.split("_").collect { it.isEmpty() ? it : it[0].toUpperCase() + it.substring(1).toLowerCase() }.join("")
    }

    static String renderJava(List<Map> themes) {
        def sb = new StringBuilder()
        sb << "package ${PKG};\n\n"
        sb << "public abstract class ${OUTER} extends ${BASE} {\n"
        themes.eachWithIndex { t, i ->
            if (i > 0) sb << "\n"
            sb << "    public static final class ${t.className} extends ${OUTER} {\n"
            sb << "        @Override protected int themeIndex() { return ${t.index}; }\n"
            sb << "    }\n"
        }
        sb << "}\n"
        sb.toString()
    }

    static String renderThemesJava(List<Map> themes) {
        def sb = new StringBuilder()
        sb << "package ${PKG};\n\n"
        sb << "/** Wallpaper themes, generated from core/src/config.rs; do not edit. */\n"
        sb << "public final class ${THEMES} {\n"
        sb << "    public static final class Entry {\n"
        sb << "        /** Index shared with the native renderer. */\n"
        sb << "        public final int index;\n"
        sb << "        /** Theme name, e.g. \"Deep blue\". */\n"
        sb << "        public final String name;\n"
        sb << "        /** Wallpaper picker label, e.g. \"Windy, Deep blue\". */\n"
        sb << "        public final String label;\n"
        sb << "        /** Pre-rendered preview image. */\n"
        sb << "        public final int thumbnail;\n"
        sb << "        /** Class name of the theme's wallpaper service. */\n"
        sb << "        public final String service;\n"
        sb << "\n"
        sb << "        private Entry(int index, String name, String label, int thumbnail, String service) {\n"
        sb << "            this.index = index;\n"
        sb << "            this.name = name;\n"
        sb << "            this.label = label;\n"
        sb << "            this.thumbnail = thumbnail;\n"
        sb << "            this.service = service;\n"
        sb << "        }\n"
        sb << "    }\n\n"
        sb << "    public static final Entry[] ALL = {\n"
        themes.each { t ->
            sb << "        new Entry(${t.index}, ${javaStr(t.name)}, ${javaStr(t.label)}, R.drawable.${t.resName}, ${javaStr(PKG + "." + OUTER + "\$" + t.className)}),\n"
        }
        sb << "    };\n\n"
        def custom = themes.find { it.ident == "CUSTOM" }
        if (custom == null) {
            throw new GradleException("Could not find `Theme::CUSTOM` in `Theme::ALL` in config.rs (the app's custom colors need it)")
        }
        sb << "    public static final int CUSTOM = ${custom.index};\n\n"
        sb << "    /** Returns a theme by index, clamped to a valid one. */\n"
        sb << "    public static Entry get(int index) {\n"
        sb << "        return ALL[Math.max(0, Math.min(index, ALL.length - 1))];\n"
        sb << "    }\n\n"
        sb << "    private ${THEMES}() {\n"
        sb << "    }\n"
        sb << "}\n"
        sb.toString()
    }

    static String javaStr(String s) {
        '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'
    }

    static String renderWallpaperXml(Map t) {
        """\
<?xml version="1.0" encoding="utf-8"?>
<wallpaper xmlns:android="http://schemas.android.com/apk/res/android"
    android:thumbnail="@drawable/${t.resName}"
    android:settingsActivity="${PKG}.${SETTINGS}"
    android:supportsMultipleDisplays="true" />
"""
    }

    static String renderManifest(List<Map> themes) {
        def sb = new StringBuilder()
        sb << '<?xml version="1.0" encoding="utf-8"?>\n'
        sb << '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
        sb << '    <application>\n'
        themes.eachWithIndex { t, i ->
            if (i > 0) sb << '\n'
            sb << '        <service\n'
            sb << "            android:name=\"${PKG}.${OUTER}\$${t.className}\"\n"
            sb << '            android:directBootAware="true"\n'
            sb << '            android:enabled="true"\n'
            sb << '            android:exported="true"\n'
            sb << "            android:label=\"${xmlAttr(t.label)}\"\n"
            sb << '            android:permission="android.permission.BIND_WALLPAPER">\n'
            sb << '            <intent-filter>\n'
            sb << '                <action android:name="android.service.wallpaper.WallpaperService" />\n'
            sb << '            </intent-filter>\n'
            sb << '            <meta-data\n'
            sb << '                android:name="android.service.wallpaper"\n'
            sb << "                android:resource=\"@xml/${t.resName}\" />\n"
            sb << '        </service>\n'
        }
        sb << '    </application>\n'
        sb << '</manifest>\n'
        sb.toString()
    }

    static String xmlAttr(String s) {
        s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }
}
