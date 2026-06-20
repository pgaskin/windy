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

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getThemeConfig()

    @OutputDirectory
    abstract DirectoryProperty getJavaOutputDir()

    @OutputDirectory
    abstract DirectoryProperty getResOutputDir()

    @OutputFile
    abstract RegularFileProperty getManifestOutput()

    @TaskAction
    void generate() {
        def themes = parseThemes(themeConfig.get().asFile.text)

        def javaRoot = javaOutputDir.get().asFile
        javaRoot.deleteDir()
        def pkgDir = new File(javaRoot, PKG.replace('.', '/'))
        pkgDir.mkdirs()
        new File(pkgDir, "${OUTER}.java").setText(renderJava(themes), "UTF-8")

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
            themes << [
                index    : themes.size(),
                className: toPascal(ident),
                resName  : "windy_" + ident.toLowerCase().replace("_", ""),
                label    : label.group(1),
            ]
        }
        if (themes.isEmpty()) {
            throw new GradleException("No themes parsed from `Theme::ALL` in config.rs")
        }
        return themes
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

    static String renderWallpaperXml(Map t) {
        """\
<?xml version="1.0" encoding="utf-8"?>
<wallpaper xmlns:android="http://schemas.android.com/apk/res/android"
    android:thumbnail="@drawable/${t.resName}"
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
