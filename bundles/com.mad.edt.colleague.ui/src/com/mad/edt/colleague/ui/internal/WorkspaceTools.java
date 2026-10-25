package com.mad.edt.colleague.ui.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.agent.AgentMode;
import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.context.CodeSearch;
import com.mad.edt.colleague.context.ConfigurationOverview;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * Workspace tools offered to the agent loop. The read-only trio (search,
 * list, read) is always available once a project is open; the write tool is
 * handed out only while the edit mode is on and each write asks for explicit
 * confirmation in a modal dialog. All paths are workspace-relative so the
 * model cannot touch files outside the workspace tree.
 */
public final class WorkspaceTools {

    private WorkspaceTools() {
        // utility
    }

    /**
     * Read-only tools, always safe.
     *
     * @param theSearch shared workspace code search
     * @param theOverview cached configuration picture of the workspace
     * @return tool executors for the agent loop
     */
    public static List<IToolExecutor> readOnly(final CodeSearch theSearch,
        final ConfigurationOverview theOverview) {
        return List.of(new SearchCodeTool(theSearch), new ListFilesTool(),
            new ReadFileTool(), new DescribeConfigurationTool(theOverview));
    }

    /**
     * Same as {@link #readOnly(CodeSearch, ConfigurationOverview)} plus the
     * guarded write tool.
     *
     * @param theSearch shared workspace code search
     * @param theOverview cached configuration picture of the workspace
     * @return tool executors including code writing
     */
    public static List<IToolExecutor> withWrite(final CodeSearch theSearch,
        final ConfigurationOverview theOverview) {
        final List<IToolExecutor> tools = new ArrayList<>(readOnly(theSearch, theOverview));
        tools.add(new WriteFileTool());
        return tools;
    }

    /** Finds matching sources in the workspace and returns snippets. */
    public static final class SearchCodeTool implements IToolExecutor {

        private final CodeSearch ourSearch;

        SearchCodeTool(final CodeSearch theSearch) {
            ourSearch = theSearch;
        }

        @Override
        public String getName() {
            return "search_code";
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec("search_code",
                "Search workspace source files (.bsl module texts and .mdo metadata) by"
                    + " keywords. Returns the strongest matching snippets with file paths"
                    + " and line numbers. Call this first for any project question.",
                schema("query", "words or identifier fragments to find"));
        }

        @Override
        public String execute(final String theArgumentsJson) {
            final String query = stringArg(theArgumentsJson, "query");
            if(query == null || query.isBlank()) {
                return "ERROR: pass a non-empty query";
            }
            final String found = ourSearch.search(query);
            if(found.isEmpty()) {
                return "No matches for: " + query
                    + (ourSearch.isIndexing() ? " (index is still building)" : "");
            }
            return found;
        }
    }

    /** Lists indexed workspace file paths matching a substring. */
    public static final class ListFilesTool implements IToolExecutor {

        @Override
        public String getName() {
            return "list_files";
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec("list_files",
                "List workspace file paths (workspace-relative, e.g. MyProject/src/Catalogs/X/x.bsl)"
                    + " whose path contains the given substring. Case-insensitive.",
                schema("pattern", "path substring, may be empty to list the first entries"));
        }

        @Override
        public String execute(final String theArgumentsJson) {
            final String pattern = stringArg(theArgumentsJson, "pattern");
            final String needle = pattern == null ? "" : pattern.toLowerCase(Locale.ROOT);
            final StringBuilder listing = new StringBuilder();
            int shown = 0;
            int total = 0;
            for(final IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if(!project.isOpen()) {
                    continue;
                }
                try {
                    final List<IResource> matches = new ArrayList<>();
                    project.accept(resource -> {
                        if(resource.getType() == IResource.FILE
                            && resource.getProjectRelativePath().toPortableString()
                                .toLowerCase(Locale.ROOT).contains(needle)) {
                            matches.add(resource);
                        }
                        return matches.size() < 5_000;
                    });
                    for(final IResource match : matches) {
                        total++;
                        if(shown < 80) {
                            listing.append(project.getName()).append('/')
                                .append(match.getProjectRelativePath().toPortableString())
                                .append('\n');
                            shown++;
                        }
                    }
                } catch (final CoreException ex) {
                    listing.append("ERROR reading project ").append(project.getName())
                        .append(": ").append(ex.getMessage()).append('\n');
                }
            }
            if(total == 0) {
                return "No files match: " + needle;
            }
            listing.append("... total matches: ").append(total);
            return listing.toString();
        }
    }

    /** Reads one workspace file, optionally a line window. */
    public static final class ReadFileTool implements IToolExecutor {

        private static final int MAX_LINES = 400;

        @Override
        public String getName() {
            return "read_file";
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec("read_file",
                "Read a workspace file by workspace-relative path. Returns at most"
                    + " 400 lines starting from fromLine (1-based).",
                schema("path", "workspace-relative file path", "fromLine",
                    "optional 1-based start line"));
        }

        @Override
        public String execute(final String theArgumentsJson) throws Exception {
            final Path file = resolve(stringArg(theArgumentsJson, "path"));
            final int fromLine = intArg(theArgumentsJson, "fromLine", 1);
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            final int from = Math.max(1, fromLine);
            final int to = Math.min(lines.size(), from + MAX_LINES - 1);
            if(from > lines.size()) {
                return "File has only " + lines.size() + " lines";
            }
            final StringBuilder content = new StringBuilder();
            for(int index = from; index <= to; index++) {
                content.append(index).append(": ").append(lines.get(index - 1)).append('\n');
            }
            if(to < lines.size()) {
                content.append("... ").append(lines.size()).append(" lines total, pass fromLine="
                    + (to + 1) + " to continue");
            }
            return content.toString();
        }
    }

    /** Writes a workspace file after a modal confirmation; edit mode only. */
    public static final class WriteFileTool implements IToolExecutor {

        @Override
        public String getName() {
            return "write_file";
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec("write_file",
                "Replace the full content of a workspace file. Only available in edit mode;"
                    + " the user confirms every write in a dialog. Use read_file first and"
                    + " submit the complete new content, not a patch.",
                schema("path", "workspace-relative file path", "content",
                    "complete new file content"));
        }

        @Override
        public String execute(final String theArgumentsJson) throws Exception {
            if(!AgentMode.isOn()) {
                return "ERROR: edit mode is off, write_file is disabled";
            }
            final String path = stringArg(theArgumentsJson, "path");
            final String content = stringArg(theArgumentsJson, "content");
            if(path == null || content == null) {
                return "ERROR: path and content are required";
            }
            final IPath workspacePath = new org.eclipse.core.runtime.Path(path);
            final IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(workspacePath);
            final byte[] newBytes = content.getBytes(StandardCharsets.UTF_8);
            final boolean exists = file.exists() && file.getLocation() != null;
            final String oldDigest = describeOld(file);

            final Boolean confirmed = confirmOnUiThread(file, workspacePath, exists,
                newBytes.length, oldDigest);
            if(confirmed == null || !confirmed.booleanValue()) {
                return "The user declined the change, nothing was written";
            }
            if(exists) {
                Files.write(file.getLocation().toFile().toPath(), newBytes);
            } else {
                final Path location = file.getLocation().toFile().toPath();
                Files.createDirectories(location.getParent());
                Files.write(location, newBytes);
            }
            file.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
            return "Written " + newBytes.length + " bytes to " + path;
        }

        private static String describeOld(final IFile theFile) {
            try {
                if(!theFile.exists() || theFile.getLocation() == null) {
                    return "new file";
                }
                final Path location = theFile.getLocation().toFile().toPath();
                return Files.size(location) + " bytes, "
                    + Files.readAllLines(location, StandardCharsets.UTF_8).size() + " lines";
            } catch (final IOException ex) {
                return "unreadable";
            }
        }

        private static Boolean confirmOnUiThread(final IFile theFile, final IPath thePath,
            final boolean theExists, final int theNewSize, final String theOldDigest) {
            final Shell shell = Display.getDefault().getActiveShell();
            final String message = (theExists ? "Replace file (was " : "Create new file ")
                + theOldDigest + ")\n\u2192 new size: " + theNewSize + " bytes\n"
                + thePath.toPortableString() + "\n\nAllow this change?";
            final org.eclipse.swt.widgets.Display display = Display.getDefault();
            final Boolean[] answer = new Boolean[1];
            display.syncExec(() -> answer[0] = Boolean.valueOf(MessageDialog.openConfirm(
                shell == null ? new Shell(display) : shell,
                "Edit mode: change confirmation", message)));
            return answer[0] == null ? Boolean.FALSE : answer[0];
        }
    }

    /** Describes the open configurations: name, version, object counts. */
    public static final class DescribeConfigurationTool implements IToolExecutor {

        private final ConfigurationOverview ourOverview;

        DescribeConfigurationTool(final ConfigurationOverview theOverview) {
            ourOverview = theOverview;
        }

        @Override
        public String getName() {
            return "describe_configuration";
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec("describe_configuration",
                "Describe the 1C configuration of the open EDT projects: configuration name,"
                    + " version, and how many metadata objects of each kind the configuration"
                    + " has with example names. Call this for any question about the shape of"
                    + " the configuration itself.",
                schema());
        }

        @Override
        public String execute(final String theArgumentsJson) {
            final String text = ourOverview.get();
            return text.isEmpty() ? "(the configuration picture is still building, ask again"
                + " shortly)" : text;
        }
    }

    private static Path resolve(final String thePathText) throws IOException {
        if(thePathText == null || thePathText.isBlank()) {
            throw new IOException("path is required");
        }
        final IPath path = new org.eclipse.core.runtime.Path(thePathText).makeAbsolute();
        if(path.segmentCount() < 2) {
            throw new IOException("workspace-relative path (project/...) is required");
        }
        final IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
        if(file.getLocation() == null) {
            throw new IOException("not a local file: " + thePathText);
        }
        final Path location = file.getLocation().toFile().toPath();
        if(!Files.isReadable(location)) {
            throw new IOException("file not found: " + thePathText);
        }
        return location;
    }

    private static String stringArg(final String theArgumentsJson, final String theName) {
        final JsonObject json = parse(theArgumentsJson);
        if(json == null || !json.has(theName) || json.get(theName).isJsonNull()) {
            return null;
        }
        return json.get(theName).getAsString();
    }

    private static int intArg(final String theArgumentsJson, final String theName,
        final int theDefault) {
        final JsonObject json = parse(theArgumentsJson);
        if(json == null || !json.has(theName) || json.get(theName).isJsonNull()) {
            return theDefault;
        }
        try {
            return json.get(theName).getAsInt();
        } catch (final NumberFormatException ex) {
            return theDefault;
        }
    }

    private static JsonObject parse(final String theArgumentsJson) {
        try {
            return JsonParser.parseString(theArgumentsJson).getAsJsonObject();
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    private static JsonObject schema(final String... theNameDescriptionPairs) {
        final JsonObject properties = new JsonObject();
        final List<String> required = new ArrayList<>();
        for(int index = 0; index + 1 < theNameDescriptionPairs.length; index += 2) {
            final JsonObject property = new JsonObject();
            property.addProperty("type",
                theNameDescriptionPairs[index].equals("fromLine") ? "integer" : "string");
            property.addProperty("description", theNameDescriptionPairs[index + 1]);
            properties.add(theNameDescriptionPairs[index], property);
            if(!theNameDescriptionPairs[index].equals("fromLine")) {
                required.add(theNameDescriptionPairs[index]);
            }
        }
        final JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        return schema;
    }
}
