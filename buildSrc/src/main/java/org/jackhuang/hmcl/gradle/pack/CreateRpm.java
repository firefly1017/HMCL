/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.gradle.pack;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/// Creates a RPM package for the current HMCL channel.
///
/// ## Package layout
///
/// The generated RPM installs four artifacts:
///
/// - the bundled HMCL shell launcher under `/usr/share/java/hmcl/`
/// - a channel-specific command under `/usr/bin/`
/// - a desktop entry under `/usr/share/applications/`
/// - the HMCL icon under `/usr/share/icons/hicolor/256x256/apps/`
///
/// ## Channel commands and alternatives
///
/// Every package installs a channel-specific executable such as `hmcl-stable`
/// or `hmcl-beta`. The generic `hmcl` command is registered via
/// `update-alternatives` in the `%post` script so multiple channel packages
/// can coexist without file conflicts.
///
/// @author Glavo
public abstract class CreateRpm extends DefaultTask {
    public static final Logger LOGGER = Logging.getLogger(CreateRpm.class);

    /// RPM version string written into the spec file.
    @Input
    public abstract Property<String> getVersion();

    /// Release type metadata that controls package name, launcher name, and alternative priority.
    @Input
    public abstract Property<ReleaseType> getReleaseType();

    /// Launcher class name for the Linux StartupWMClass property in the .desktop file.
    @Input
    public abstract Property<String> getLauncherClassName();

    /// Executable `.sh` artifact produced by `makeExecutables`.
    @InputFile
    public abstract RegularFileProperty getAppShFile();

    /// Desktop icon installed into the hicolor icon theme.
    @InputFile
    public abstract RegularFileProperty getIconFile();

    /// Final `.rpm` archive written by this task.
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    private ReleaseType getCurrentType() {
        return getReleaseType().get();
    }

    private String getCurrentTypeName() {
        return getCurrentType().getName();
    }

    private String getLauncherPath() {
        return "/usr/bin/hmcl-" + getCurrentTypeName();
    }

    private String getTargetPath() {
        return "/usr/share/java/hmcl/" + getAppShFile().getAsFile().get().getName();
    }

    private String getDesktopFilePath() {
        return "/usr/share/applications/hmcl-%s.desktop".formatted(getCurrentTypeName());
    }

    private String getIconTargetPath() {
        return "/usr/share/icons/hicolor/256x256/apps/hmcl-%s.png".formatted(getCurrentTypeName());
    }

    /// Creates a tiny shell wrapper that launches the bundled script from the user's home directory.
    private String getLauncherScript() {
        return """
                #!/usr/bin/env bash
                cd "$HOME"
                if [ -z "${HMCL_USER_HOME:-}" ]; then
                    if [ -z "${XDG_DATA_HOME:-}" ]; then
                        export HMCL_USER_HOME="$HOME/.local/share/hmcl"
                    else
                        export HMCL_USER_HOME="$XDG_DATA_HOME/hmcl"
                    fi
                fi
                if [ -z "${HMCL_LOCAL_HOME:-}" ]; then
                    export HMCL_LOCAL_HOME="$HMCL_USER_HOME/local-%s"
                fi
                if [ -z "${HMCL_DEPENDENCIES_DIR:-}" ]; then
                    export HMCL_DEPENDENCIES_DIR="$HMCL_USER_HOME/dependencies"
                fi
                exec %s "$@"
                """.formatted(getCurrentTypeName(), getTargetPath());
    }

    /// Generates the desktop entry that points to the channel-specific launcher command.
    private String getDesktopInfo() {
        return """
                [Desktop Entry]
                Type=Application
                Name=%s
                Comment=Hello Minecraft! Launcher
                Exec=%s
                Icon=%s
                Terminal=false
                StartupNotify=false
                Categories=Game;
                Keywords=mc;minecraft;
                StartupWMClass=%s
                """.formatted(getCurrentType().getDisplayName(), getLauncherPath(), getIconTargetPath(), getLauncherClassName().get());
    }

    private static final String COMMON_LAUNCHER_PATH = "/usr/bin/hmcl";

    /// Source file names used inside the SOURCES directory.
    private static final String SOURCE_APP_SH = "HMCL.sh";
    private static final String SOURCE_LAUNCHER = "launcher.sh";
    private static final String SOURCE_DESKTOP = "hmcl.desktop";
    private static final String SOURCE_ICON = "hmcl.png";

    /// Sanitize a version string for RPM.
    /// RPM's `Version` field forbids `-` (it separates Version from Release).
    private static String sanitizeRpmVersion(String version) {
        return version.replace('-', '_');
    }

    /// Generates the RPM spec file that copies files from `%{_sourcedir}` into `$RPM_BUILD_ROOT`.
    private String getSpecFile() {
        String packageName = getCurrentType().getPackageName();
        String version = sanitizeRpmVersion(getVersion().get());

        return """
                %%global source_date_epoch_from_changelog 0

                Name:           %s
                Version:        %s
                Release:        1
                Summary:        Hello Minecraft! Launcher
                License:        GPL-3.0
                URL:            https://github.com/HMCL-dev/HMCL
                BuildArch:      noarch

                %%description
                Hello Minecraft! Launcher (HMCL) is a Minecraft launcher which supports
                Mod management, game customization, auto-installing (Forge, LiteLoader,
                OptiFine, Fabric, Quilt and NeoForge), Modpack creating, UI customization,
                and more.

                %%install
                mkdir -p "%%{buildroot}%s"
                mkdir -p "%%{buildroot}%s"
                mkdir -p "%%{buildroot}%s"
                mkdir -p "%%{buildroot}%s"
                install -m 755 %%{_sourcedir}/%s "%%{buildroot}%s"
                install -m 755 %%{_sourcedir}/%s "%%{buildroot}%s"
                install -m 644 %%{_sourcedir}/%s "%%{buildroot}%s"
                install -m 644 %%{_sourcedir}/%s "%%{buildroot}%s"

                %%files
                %s
                %s
                %s
                %s

                %%post
                update-alternatives --install %s hmcl %s %d

                %%preun
                if [ "$1" = 0 ]; then
                    update-alternatives --remove hmcl %s
                fi
                """.formatted(
                        packageName, version,
                        // mkdir -p for each target directory
                        parentOf(getLauncherPath()),
                        parentOf(getTargetPath()),
                        parentOf(getDesktopFilePath()),
                        parentOf(getIconTargetPath()),
                        // install commands: source -> dest
                        SOURCE_APP_SH, getTargetPath(),
                        SOURCE_LAUNCHER, getLauncherPath(),
                        SOURCE_DESKTOP, getDesktopFilePath(),
                        SOURCE_ICON, getIconTargetPath(),
                        // %files
                        getLauncherPath(),
                        getTargetPath(),
                        getDesktopFilePath(),
                        getIconTargetPath(),
                        // %post / %preun
                        COMMON_LAUNCHER_PATH, getLauncherPath(), getCurrentType().getAlternativesPriority(),
                        getLauncherPath()
                ) + "\n";
    }

    /// Returns the parent directory of an absolute path (e.g., `/usr/bin/foo` → `/usr/bin`).
    private static String parentOf(String path) {
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? "/" : path.substring(0, idx);
    }

    /// Ensures the `rpmbuild` command is available on the system.
    private static void checkRpmbuildAvailable() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("rpmbuild", "--version");
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new GradleException("rpmbuild is not available. Install the rpm-build package to build RPMs.");
        }
    }

    /// Builds a valid `.rpm` package by invoking `rpmbuild` with a generated spec file.
    @TaskAction
    public void run() throws IOException, InterruptedException {
        checkRpmbuildAvailable();

        Path appShFile = getAppShFile().getAsFile().get().toPath();
        if (!Files.isRegularFile(appShFile))
            throw new IOException("Invalid app script file: " + appShFile);

        Path iconFile = getIconFile().getAsFile().get().toPath();
        if (!Files.isRegularFile(iconFile))
            throw new IOException("Invalid icon file: " + iconFile);

        byte[] appShBytes = Files.readAllBytes(appShFile);
        if (appShBytes.length == 0)
            throw new IOException("Empty app script file: " + appShFile);

        byte[] iconBytes = Files.readAllBytes(iconFile);
        if (iconBytes.length == 0)
            throw new IOException("Empty icon file: " + iconFile);

        byte[] launcherScriptBytes = getLauncherScript().getBytes(StandardCharsets.UTF_8);
        byte[] desktopInfoBytes = getDesktopInfo().getBytes(StandardCharsets.UTF_8);

        String packageName = getCurrentType().getPackageName();

        Path tmpDir = Files.createTempDirectory("hmcl-rpm-");
        try {
            // Create rpmbuild directory layout
            Path sourcesDir = tmpDir.resolve("SOURCES");
            Path specsDir = tmpDir.resolve("SPECS");
            Path buildDir = tmpDir.resolve("BUILD");
            Path buildrootDir = tmpDir.resolve("BUILDROOT");
            Path rpmsDir = tmpDir.resolve("RPMS");
            Path srpmsDir = tmpDir.resolve("SRPMS");
            Files.createDirectories(sourcesDir);
            Files.createDirectories(specsDir);
            Files.createDirectories(buildDir);
            Files.createDirectories(buildrootDir);
            Files.createDirectories(rpmsDir);
            Files.createDirectories(srpmsDir);

            // Place all files in SOURCES
            Files.copy(appShFile, sourcesDir.resolve(SOURCE_APP_SH));
            Files.write(sourcesDir.resolve(SOURCE_LAUNCHER), launcherScriptBytes);
            Files.write(sourcesDir.resolve(SOURCE_DESKTOP), desktopInfoBytes);
            Files.copy(iconFile, sourcesDir.resolve(SOURCE_ICON));

            // Generate spec file
            Path specFile = specsDir.resolve(packageName + ".spec");
            Files.writeString(specFile, getSpecFile(), StandardCharsets.UTF_8);

            // Run rpmbuild
            LOGGER.lifecycle("Building RPM package...");
            ProcessBuilder pb = new ProcessBuilder(
                    "rpmbuild", "-bb",
                    "--define", "_topdir " + tmpDir.toAbsolutePath(),
                    specFile.toAbsolutePath().toString()
            );
            pb.environment().put("SOURCE_DATE_EPOCH", "0");
            pb.redirectErrorStream(true);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.lifecycle(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new GradleException("rpmbuild failed with exit code " + exitCode);
            }

            // Traverse RPMS directory to find the generated .rpm file
            Path outputFile = getOutputFile().get().getAsFile().toPath();
            Files.createDirectories(outputFile.getParent());

            try (Stream<Path> files = Files.find(rpmsDir, Integer.MAX_VALUE,
                    (p, a) -> a.isRegularFile() && p.getFileName().toString().endsWith(".rpm"))) {
                Path rpmFile = files
                        .findFirst()
                        .orElseThrow(() -> new GradleException("No .rpm file found in RPMS directory"));

                Files.copy(rpmFile, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOGGER.lifecycle("RPM package created: {}", outputFile);
            }
        } finally {
            // Clean up temp directory
            try (Stream<Path> walk = Files.walk(tmpDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
    }
}
