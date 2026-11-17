package com.mad.edt.colleague.context;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Cached one-screen picture of the EDT workspace for the model: which projects
 * are open, which 1C configuration each EDT project holds, how many metadata
 * objects of every kind it contains and a few sample names per kind. This is
 * what lets a language model answer questions about the configuration itself,
 * not only about source code lines. The snapshot is built once by a plain file
 * scan and is rebuilt only when the user refreshes the contacts panel.
 */
public final class ConfigurationOverview {

    /** Metadata folder names shown with examples even when numerous. */
    private static final List<String> EXAMPLE_FOLDERS = List.of("Catalogs", "Documents",
        "CommonModules", "InformationRegisters", "AccumulationRegisters", "Roles",
        "DataProcessors", "Reports", "Subsystems");

    /** Sample object names listed per example folder. */
    private static final int EXAMPLES_PER_FOLDER = 12;
    /** Metadata files read for their names inside one example folder. */
    private static final int MAX_FOOTPRINT_FILES_PER_FOLDER = 400;

    private volatile String ourText = "";

    /**
     * Rebuilds the snapshot. Call from a background job; the previous text
     * stays usable until the new one is ready.
     */
    public void rebuild() {
        final StringBuilder out = new StringBuilder();
        for(final IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if(!project.isOpen()) {
                continue;
            }
            appendProject(out, project);
        }
        if(out.length() == 0) {
            out.append("(no EDT configuration projects are open in this workspace)");
        }
        ourText = out.toString();
    }

    /** Requests a rebuild on the next call of {@link #get()}. */
    public void invalidate() {
        ourText = "";
    }

    /**
     * @return the last built snapshot, empty while the first build is running
     */
    public String get() {
        return ourText;
    }

    private void appendProject(final StringBuilder out, final IProject theProject) {
        final java.io.File root = theProject.getLocation() == null
            ? null : theProject.getLocation().toFile();
        if(root == null) {
            return;
        }
        final Path src = root.toPath().resolve("src");
        final Path configuration = src.resolve("Configuration");
        if(!Files.isDirectory(configuration)) {
            return;
        }
        out.append("EDT project \"").append(theProject.getName()).append("\".\n");
        appendIdentityLine(out, readConfigurationIdentity(configuration.resolve("Configuration.mdo")));
        final Path[] folders;
        try {
            folders = listDirectories(src);
        } catch (final IOException ex) {
            return;
        }
        out.append("Metadata objects by kind (src folder of the EDT export):\n");
        for(final Path folder : folders) {
            appendKindFolder(out, folder);
        }
        out.append("BSL module sources live inside the same kind folders next to ")
            .append("the .mdo metadata files.\n");
    }

    /**
     * @param out answer being built
     * @param theIdentity name, ru presentation, version, vendor of the configuration
     */
    private static void appendIdentityLine(final StringBuilder out, final String[] theIdentity) {
        out.append("Configuration name in the metadata: ")
            .append(theIdentity[0] == null ? "(unknown)" : theIdentity[0]);
        if(theIdentity[1] != null) {
            out.append(" (presented to users as \"").append(theIdentity[1]).append("\"");
            if(theIdentity[2] != null) {
                out.append(", version ").append(theIdentity[2]);
            }
            if(theIdentity[3] != null) {
                out.append(", vendor ").append(theIdentity[3]);
            }
            out.append(')');
        }
        out.append(".\n");
    }

    /**
     * @param out answer being built
     * @param theFolder one kind folder of the EDT export
     */
    private static void appendKindFolder(final StringBuilder out, final Path theFolder) {
        final String folderName = theFolder.getFileName().toString();
        if("Configuration".equals(folderName)) {
            return;
        }
        final List<Path> mdoFiles = collectMetadataFiles(theFolder);
        if(mdoFiles.isEmpty()) {
            return;
        }
        out.append("  ").append(folderName).append(": ").append(mdoFiles.size());
        if(EXAMPLE_FOLDERS.contains(folderName)) {
            final String names = exampleNames(mdoFiles);
            if(!names.isEmpty()) {
                out.append(", examples: ").append(names);
            }
        }
        out.append('\n');
    }

    /**
     * @param theMdoFiles metadata files of one kind folder
     * @return up to {@code EXAMPLES_PER_FOLDER} object names, comma joined
     */
    private static String exampleNames(final List<Path> theMdoFiles) {
        final List<String> names = new ArrayList<>();
        for(final Path mdo : theMdoFiles) {
            final String name = readNameOf(mdo);
            if(name != null && !name.isEmpty()) {
                names.add(name);
            }
            if(names.size() >= EXAMPLES_PER_FOLDER) {
                break;
            }
        }
        return String.join(", ", names);
    }

    private static Path[] listDirectories(final Path theSrc) throws IOException {
        final List<Path> folders = new ArrayList<>();
        try (java.util.stream.Stream<Path> children = Files.list(theSrc)) {
            children.filter(Files::isDirectory).forEach(folders::add);
        }
        folders.sort(null);
        return folders.toArray(Path[]::new);
    }

    private static List<Path> collectMetadataFiles(final Path theFolder) {
        final List<Path> found = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(theFolder)) {
            walk.forEach(entry -> {
                final String name = entry.getFileName().toString();
                if(name.endsWith(".mdo") && !name.endsWith(".form.mdo")
                    && !name.endsWith(".fcd.mdo") && found.size()
                        < MAX_FOOTPRINT_FILES_PER_FOLDER) {
                    found.add(entry);
                }
            });
        } catch (final IOException ex) {
            // return what was collected before the failure
        }
        return found;
    }

    /**
     * Reads the identity of the configuration from the head of its huge XML:
     * technical name, Russian presentation, version and vendor.
     *
     * @param theConfigurationFile the Configuration.mdo of the EDT project
     * @return four nullable entries: name, ru presentation, version, vendor
     */
    private static String[] readConfigurationIdentity(final Path theConfigurationFile) {
        final String head = readHead(theConfigurationFile, 60_000);
        if(head == null) {
            return new String[4];
        }
        final String name = firstTagValue(head, "<name>", "</name>");
        String presented = null;
        final int ruKey = head.indexOf("<key>ru</key>");
        if(ruKey >= 0) {
            presented = firstTagValue(head.substring(ruKey), "<value>", "</value>");
        }
        return new String[] {name, presented,
            firstTagValue(head, "<version>", "</version>"),
            firstTagValue(head, "<vendor>", "</vendor>")};
    }

    private static String readHead(final Path theFile, final int theCharBudget) {
        if(!Files.isReadable(theFile)) {
            return null;
        }
        final StringBuilder head = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(
            Files.newInputStream(theFile), StandardCharsets.UTF_8)) {
            final char[] buffer = new char[4096];
            int read;
            while(head.length() < theCharBudget && (read = reader.read(buffer)) > 0) {
                head.append(buffer, 0, read);
            }
        } catch (final IOException ex) {
            return null;
        }
        return head.toString();
    }

    private static String firstTagValue(final String theHead, final String theOpen,
        final String theClose) {
        final int at = theHead.indexOf(theOpen);
        if(at < 0) {
            return null;
        }
        final int end = theHead.indexOf(theClose, at);
        if(end <= at) {
            return null;
        }
        return theHead.substring(at + theOpen.length(), end)
            .replace("&quot;", "\"").trim();
    }

    /**
     * Reads the first <name> element of a metadata XML without loading the
     * whole file; large configuration descriptors reach megabytes.
     *
     * @param theMetadataFile the .mdo file to inspect
     * @return the object name or {@code null} when it cannot be read
     */
    private static String readNameOf(final Path theMetadataFile) {
        final String head = readHead(theMetadataFile, 60_000);
        return head == null ? null : firstTagValue(head, "<name>", "</name>");
    }
}
