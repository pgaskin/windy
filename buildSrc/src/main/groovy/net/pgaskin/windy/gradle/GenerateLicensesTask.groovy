// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

package net.pgaskin.windy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

// based on what I had claude do for cmus-android, but modified to use cargo-licenses
abstract class GenerateLicensesTask extends DefaultTask {
    static final String ASSET = "windy/licenses.html"

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getCargoLock()

    @Internal
    abstract DirectoryProperty getCrateDir()

    @OutputDirectory
    abstract DirectoryProperty getAssetsOutputDir()

    @Inject
    abstract ExecOperations getExecOperations()

    @TaskAction
    void generate() {
        def assetsRoot = assetsOutputDir.get().asFile
        assetsRoot.deleteDir()
        def asset = new File(assetsRoot, ASSET)
        asset.parentFile.mkdirs()

        if (!isCargoLicensesInstalled()) {
            throw new GradleException("cargo-licenses is required (cargo install licenses)")
        }

        def collected = new File(temporaryDir, "licenses")
        collected.deleteDir()
        collected.mkdirs()

        def env = new LinkedHashMap<String, String>(System.getenv())
        env.put("CARGO_TERM_PROGRESS_WHEN", "never")
        env.put("CARGO_TERM_COLOR", "never")

        // TODO: this isn't reproducible
        execOperations.exec {
            it.workingDir = crateDir.get().asFile
            it.commandLine = ["cargo", "licenses", "collect", "--path", collected.absolutePath]
            it.environment = env
        }

        asset.setText(renderHtml(parseLicenses(collected)), "UTF-8")
    }

    private boolean isCargoLicensesInstalled() {
        try {
            def out = new ByteArrayOutputStream()
            def result = execOperations.exec {
                it.commandLine = ["cargo", "licenses", "--help"]
                it.standardOutput = out
                it.errorOutput = out
                it.ignoreExitValue = true
            }
            return result.exitValue == 0
        } catch (Exception ex) {
            logger.info("failed to run cargo-licenses: ${ex}")
            return false
        }
    }

    static Map<String, List<Map>> parseLicenses(File dir) {
        def crates = new TreeMap<String, List<Map>>(String.CASE_INSENSITIVE_ORDER)
        dir.listFiles()?.sort { it.name }?.each { file ->
            if (!file.isFile()) {
                return
            }
            def sep = file.name.lastIndexOf("-LICENSE")
            def crate = sep < 0 ? file.name : file.name.substring(0, sep)
            crates.computeIfAbsent(crate, { [] }) << [
                name: sep < 0 ? file.name : file.name.substring(sep + 1),
                text: file.getText("UTF-8"),
            ]
        }
        return crates
    }

    static String renderHtml(Map<String, List<Map>> crates) {
        def body = new StringBuilder()
        crates.each { crate, licenses ->
            body << "<details>\n<summary>${htmlEscape(crate)}</summary>\n"
            licenses.each { license ->
                if (licenses.size() > 1) {
                    body << "<div class=\"fname\">${htmlEscape(license.name.toString())}</div>\n"
                }
                body << "<pre>${htmlEscape(license.text.toString())}</pre>\n"
            }
            body << "</details>\n"
        }

        def css = '''
:root { color-scheme: light dark; }
body { font-family: sans-serif; margin: 0; padding: 28px 16px 40px; line-height: 1.5;
       background: #ffffff; color: #202124; }
h1 { font-size: 1.3rem; margin: 0 0 4px; }
.intro { opacity: .7; margin: 0 0 16px; }
details { margin: 10px 0; }
summary { padding: 16px; font-size: 1.05rem; font-weight: 600; cursor: pointer;
          border-radius: 8px; background: rgba(128,128,128,.14); min-height: 24px; }
.src { font-size: .82rem; margin: 10px 2px 0; overflow-wrap: anywhere; }
.fname { font-family: monospace; font-size: .8rem; opacity: .7; margin: 12px 2px 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: .72rem; margin: 8px 0 0;
      padding: 12px; border: 1px solid rgba(128,128,128,.35); border-radius: 8px;
      background: rgba(128,128,128,.08); }
a { color: inherit; }
@media (prefers-color-scheme: dark) {
  body { background: #121212; color: #e3e3e3; }
}
'''

        """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>Third-party licenses</title>
<style>${css}</style>
</head>
<body>
<h1>Third-party licenses</h1>
<p class="intro">Windy Live Wallpaper uses third-party Rust crates.</p>
${body}</body>
</html>
"""
    }

    static String htmlEscape(String s) {
        s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    }
}
