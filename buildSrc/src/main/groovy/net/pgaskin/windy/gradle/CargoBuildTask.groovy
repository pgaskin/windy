// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

package net.pgaskin.windy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
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

abstract class CargoBuildTask extends DefaultTask {
    private static final List<String> ENV_IGNORED = [
        "AR", "CC", "CFLAGS", "CXX", "CXXFLAGS", "LDFLAGS", "RANLIB",
        "CARGO_ENCODED_RUSTFLAGS", "CARGO_INCREMENTAL",
        "RUSTC", "RUSTC_BOOTSTRAP", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER",
        "RUSTDOCFLAGS", "RUSTFLAGS", "RUSTUP_TOOLCHAIN",
        "SOURCE_DATE_EPOCH",
    ]
    private static final List<String> ENV_IGNORED_PREFIX = [
        "AR_", "CC_", "CFLAGS_", "CXX_", "CXXFLAGS_", "LDFLAGS_", "RANLIB_",
        "CARGO_BUILD_", "CARGO_PROFILE_", "CARGO_TARGET_", "CARGO_UNSTABLE_",
        "TARGET_",
    ]

    // cargo splits CARGO_ENCODED_RUSTFLAGS on ASCII unit separators
    private static final String RUSTFLAGS_SEPARATOR = "\u001f"

    private static final Pattern TOOLCHAIN_CHANNEL = ~/(?m)^\s*channel\s*=\s*"([^"]+)"/
    private static final Pattern RUSTC_VERSION = ~/^rustc (\S+)/
    private static final Pattern CARGO_NDK_VERSION = ~/^cargo-ndk (\S+)/

    CargoBuildTask() {
        // cargo does its own incremental builds
        outputs.upToDateWhen { false }
    }

    @Input
    abstract SetProperty<String> getAbiFilters()

    @Input
    abstract Property<Integer> getMinSdkVersion()

    @Input
    abstract Property<String> getCargoPackage()

    @Input
    abstract Property<String> getCargoNdkVersion()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getRustToolchainFile()

    @Internal
    abstract DirectoryProperty getNdkDirectory()

    @Internal
    abstract DirectoryProperty getWorkspaceDir()

    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    @Inject
    abstract ExecOperations getExecOperations()

    @TaskAction
    void build() {
        def abis = abiFilters.get()
        if (abis.isEmpty()) {
            throw new GradleException("NDK abiFilters must be set")
        }

        def outDir = outputDir.get().asFile
        outDir.deleteDir()
        outDir.mkdirs()

        def env = new LinkedHashMap<String, String>(System.getenv())
        env.keySet().removeIf { name ->
            ENV_IGNORED.contains(name) || ENV_IGNORED_PREFIX.any { name.startsWith(it) }
        }
        env.put("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
        env.put("CARGO_TERM_PROGRESS_WHEN", "never")
        env.put("CARGO_TERM_COLOR", "never")

        checkVersions(env)

        // reproducible builds
        env.put("CARGO_ENCODED_RUSTFLAGS", [
            "--remap-path-prefix=${cargoHome(env)}=/cargo",
            "--remap-path-prefix=${capture(env, ["rustc", "--print", "sysroot"])}=/rust",
            "--remap-path-prefix=${workspaceDir.get().asFile.absolutePath}=/windy",
        ].join(RUSTFLAGS_SEPARATOR))

        def cmd = ["cargo", "ndk"]
        abis.each { cmd += ["-t", it] }
        cmd += [
            "--platform", minSdkVersion.get().toString(),
            "--output-dir", outputDir.get().asFile.absolutePath,
            "build",
            "--locked",
            "--release",
            "--package", cargoPackage.get(),
        ]

        execOperations.exec {
            it.workingDir = workspaceDir.get().asFile
            it.commandLine = cmd
            it.environment = env
        }
    }

    // see cargo home::cargo_home
    private static String cargoHome(Map<String, String> env) {
        def home = env.get("CARGO_HOME")
        if (home != null && !home.isEmpty()) {
            return new File(home).absolutePath
        }
        return new File(System.getProperty("user.home"), ".cargo").absolutePath
    }

    // ensure the tools producing the native libraries are the pinned ones,
    // since the output isn't reproducible across versions
    private void checkVersions(Map<String, String> env) {
        def toolchain = rustToolchainFile.get().asFile
        def channel = TOOLCHAIN_CHANNEL.matcher(toolchain.getText("UTF-8"))
        if (!channel.find()) {
            throw new GradleException("could not parse the toolchain channel from ${toolchain}")
        }
        checkVersion(env, ["rustc", "--version"], RUSTC_VERSION, channel.group(1),
            "rustc (is rustup on the PATH so ${toolchain.name} is applied?)")
        checkVersion(env, ["cargo", "ndk", "--version"], CARGO_NDK_VERSION, cargoNdkVersion.get(),
            "cargo-ndk (cargo install cargo-ndk@${cargoNdkVersion.get()})")
    }

    private void checkVersion(Map<String, String> env, List<String> cmd, Pattern pattern, String expected, String what) {
        def matcher = pattern.matcher(capture(env, cmd))
        if (!matcher.find()) {
            throw new GradleException("could not parse the version from ${cmd.join(' ')}")
        }
        if (matcher.group(1) != expected) {
            throw new GradleException("expected ${what} version ${expected}, got ${matcher.group(1)}")
        }
    }

    private String capture(Map<String, String> env, List<String> cmd) {
        def out = new ByteArrayOutputStream()
        try {
            execOperations.exec {
                it.workingDir = workspaceDir.get().asFile
                it.commandLine = cmd
                it.standardOutput = out
                it.errorOutput = new ByteArrayOutputStream()
                it.environment = env
            }
        } catch (Exception ex) {
            throw new GradleException("failed to run ${cmd.join(' ')}: ${ex}", ex)
        }
        return out.toString("UTF-8").trim()
    }
}
