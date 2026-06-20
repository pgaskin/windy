// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later

package net.pgaskin.windy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

abstract class CargoBuildTask extends DefaultTask {
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

        def env = new LinkedHashMap<String, String>(System.getenv())
        env.put("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
        env.put("CARGO_TERM_PROGRESS_WHEN", "never")
        env.put("CARGO_TERM_COLOR", "never")

        execOperations.exec {
            it.workingDir = workspaceDir.get().asFile
            it.commandLine = cmd
            it.environment = env
        }
    }
}
