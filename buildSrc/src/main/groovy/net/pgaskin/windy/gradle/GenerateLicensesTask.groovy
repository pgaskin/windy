// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

package net.pgaskin.windy.gradle

import groovy.json.JsonSlurper

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import java.util.regex.Pattern

import javax.inject.Inject

// based on what I had claude do for cmus-android, but modified to resolve the
// crates with cargo-metadata
abstract class GenerateLicensesTask extends DefaultTask {
    static final String ASSET = "windy/licenses.html"

    // rust target triples for the android abis (matches cargo-ndk)
    private static final Map<String, String> ABI_TARGETS = [
        "arm64-v8a"  : "aarch64-linux-android",
        "armeabi-v7a": "armv7-linux-androideabi",
        "x86"        : "i686-linux-android",
        "x86_64"     : "x86_64-linux-android",
    ]

    // files crates conventionally ship their license terms in
    private static final Pattern LICENSE_FILE = ~/(?i)^(licen[sc]e|copying|copyright|notice|unlicen[sc]e)([-._].*)?$/

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getCargoLock()

    @Input
    abstract SetProperty<String> getAbiFilters()

    @Internal
    abstract DirectoryProperty getCrateDir()

    @OutputDirectory
    abstract DirectoryProperty getAssetsOutputDir()

    @Inject
    abstract ExecOperations getExecOperations()

    @TaskAction
    void generate() {
        def abis = abiFilters.get()
        if (abis.isEmpty()) {
            throw new GradleException("NDK abiFilters must be set")
        }

        def assetsRoot = assetsOutputDir.get().asFile
        assetsRoot.deleteDir()
        def asset = new File(assetsRoot, ASSET)
        asset.parentFile.mkdirs()

        // the dependencies are resolved per-target since the crate graph is
        // platform-dependent, and a crate can be used by more than one abi
        def crates = new TreeMap<List<String>, Map>({ List<String> a, List<String> b ->
            def order = a[0] <=> b[0]
            order != 0 ? order : a[1] <=> b[1]
        } as Comparator)
        abis.toSorted().each { abi ->
            def target = ABI_TARGETS.get(abi)
            if (target == null) {
                throw new GradleException("unknown rust target for abi ${abi}")
            }
            resolveCrates(target).each { crate ->
                crates.putIfAbsent([crate.name.toString().toLowerCase(Locale.ROOT), crate.version.toString()], crate)
            }
        }

        asset.setText(renderHtml(crates.values().toList()), "UTF-8")
    }

    // the normal (i.e., not dev or build) dependencies of the crate, excluding
    // the workspace's own members
    List<Map> resolveCrates(String target) {
        def metadata = new JsonSlurper().parseText(cargoMetadata(target)) as Map

        def packages = [:]
        (metadata.packages as List).each { packages.put(it.id, it) }

        def nodes = [:]
        ((metadata.resolve as Map).nodes as List).each { nodes.put(it.id, it) }

        def root = (metadata.resolve as Map).root
        if (root == null) {
            throw new GradleException("cargo metadata did not resolve a root package for ${crateDir.get().asFile}")
        }
        def members = new HashSet<>(metadata.workspace_members as List)

        def seen = new HashSet<String>()
        def pending = [root] as LinkedList
        while (!pending.isEmpty()) {
            def id = pending.poll()
            if (!seen.add(id.toString())) {
                continue
            }
            def node = nodes.get(id)
            if (node == null) {
                throw new GradleException("cargo metadata is missing a resolve node for ${id}")
            }
            (node.deps as List).each { dep ->
                def kinds = dep.dep_kinds as List
                if (kinds == null || kinds.isEmpty() || kinds.any { it.kind == null }) {
                    pending.add(dep.pkg)
                }
            }
        }

        return seen.findAll { !members.contains(it) }.collect { describeCrate(packages.get(it) as Map) }
    }

    String cargoMetadata(String target) {
        def out = new ByteArrayOutputStream()
        def cmd = [
            "cargo", "metadata",
            "--locked",
            "--format-version", "1",
            "--filter-platform", target,
            "--manifest-path", new File(crateDir.get().asFile, "Cargo.toml").absolutePath,
        ]
        try {
            execOperations.exec {
                it.commandLine = cmd
                it.standardOutput = out
                it.environment = System.getenv() + [
                    "CARGO_TERM_PROGRESS_WHEN": "never",
                    "CARGO_TERM_COLOR": "never",
                ]
            }
        } catch (Exception ex) {
            throw new GradleException("failed to run ${cmd.join(' ')}: ${ex}", ex)
        }
        return out.toString("UTF-8")
    }

    static Map describeCrate(Map pkg) {
        if (pkg == null) {
            throw new GradleException("cargo metadata is missing a resolved package")
        }
        def dir = new File(pkg.manifest_path.toString()).parentFile

        def files = new TreeMap<String, File>()
        dir.listFiles()?.each { file ->
            if (file.isFile() && LICENSE_FILE.matcher(file.name).matches()) {
                files.put(file.name, file)
            }
        }
        // a crate can point at a license file with a name we don't recognize
        if (pkg.license_file != null) {
            def file = new File(dir, pkg.license_file.toString())
            if (file.isFile()) {
                files.put(file.name, file)
            }
        }

        return [
            name: pkg.name,
            version: pkg.version,
            license: pkg.license,
            files: files.collect { name, file -> [name: name, text: file.getText("UTF-8")] },
        ]
    }

    static String renderHtml(List<Map> crates) {
        def body = new StringBuilder()
        crates.each { crate ->
            body << "<details>\n<summary>${htmlEscape("${crate.name} ${crate.version}".toString())}</summary>\n"
            if (crate.license != null) {
                body << "<div class=\"src\">${htmlEscape(crate.license.toString())}</div>\n"
            }
            def files = crate.files as List
            files.each { file ->
                if (files.size() > 1) {
                    body << "<div class=\"fname\">${htmlEscape(file.name.toString())}</div>\n"
                }
                body << "<pre>${htmlEscape(file.text.toString())}</pre>\n"
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
