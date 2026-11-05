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
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.agent.AgentMode;
import com.mad.edt.colleague.agent.ChatMode;
import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.agent.MetadataTools;
import com.mad.edt.colleague.context.CodeSearch;
import com.mad.edt.colleague.context.ConfigurationOverview;
import com.mad.edt.colleague.edit.EditScript;
import com.mad.edt.colleague.edit.FileDiff;
import com.mad.edt.colleague.edit.TextEditEngine;
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
     * @param theMetadata configuration metadata tools of the open projects
     * @return tool executors for the agent loop
     */
    public static List<IToolExecutor> readOnly(final CodeSearch theSearch,
        final ConfigurationOverview theOverview, final MetadataTools theMetadata) {
        final List<IToolExecutor> tools = new ArrayList<>();
        tools.add(new SearchCodeTool(theSearch));
        tools.add(new ListFilesTool());
        tools.add(new ReadFileTool());
        tools.add(new DescribeConfigurationTool(theOverview));
        tools.addAll(theMetadata.tools());
        return List.copyOf(tools);
    }

    /**
     * Same as {@link #readOnly(CodeSearch, ConfigurationOverview, MetadataTools)}
     * plus the guarded write and edit tools.
     *
     * @param theSearch shared workspace code search
     * @param theOverview cached configuration picture of the workspace
     * @param theMetadata configuration metadata tools of the open projects
     * @return tool executors including code writing and diff-based editing
     */
    public static List<IToolExecutor> withWrite(final CodeSearch theSearch,
        final ConfigurationOverview theOverview, final MetadataTools theMetadata) {
        final List<IToolExecutor> tools = new ArrayList<>(readOnly(theSearch, theOverview,
            theMetadata));
        tools.add(new WriteFileTool());
        tools.add(new EditFileTool());
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
        public boolean isParallelSafe() {
            // search only reads the index snapshot, several searches at once
            // never observe each other
            return true;
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
        public boolean isParallelSafe() {
            return true;
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
        public boolean isParallelSafe() {
            return true;
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

    /** Creates a new workspace file after a modal confirmation; edit mode only. */
    public static final class WriteFileTool implements IToolExecutor {

        @Override
        public String getName() {
            return "write_file";
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec("write_file",
                "Create a new workspace file with the given content. Only available in"
                    + " execute mode; the user confirms every creation in a dialog. For"
                    + " changing an existing file use edit_file instead.",
                schema("path", "workspace-relative file path", "content",
                    "complete new file content"));
        }

        @Override
        public String execute(final String theArgumentsJson) throws Exception {
            if(AgentMode.current() != ChatMode.EXECUTE) {
                return "ERROR: write_file is disabled outside the execute mode";
            }
            final String path = stringArg(theArgumentsJson, "path");
            final String content = stringArg(theArgumentsJson, "content");
            if(path == null || content == null) {
                return "ERROR: path and content are required";
            }
            final IPath workspacePath = new org.eclipse.core.runtime.Path(path);
            final IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(workspacePath);
            if(file.exists()) {
                return "ERROR: the file already exists, use edit_file to change it instead";
            }
            final byte[] newBytes = content.getBytes(StandardCharsets.UTF_8);

            final Boolean confirmed = confirmOnUiThread(workspacePath, newBytes.length);
            if(confirmed == null || !confirmed.booleanValue()) {
                return "The user declined the change, nothing was written";
            }
            final Path location = file.getLocation().toFile().toPath();
            Files.createDirectories(location.getParent());
            Files.write(location, newBytes);
            file.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
            return "Created " + path + " (" + newBytes.length + " bytes)";
        }

        private static Boolean confirmOnUiThread(final IPath thePath, final int theNewSize) {
            final Shell shell = Display.getDefault().getActiveShell();
            final String message = "Create new file (" + theNewSize
                + " bytes)\n" + thePath.toPortableString() + "\n\nAllow this change?";
            final org.eclipse.swt.widgets.Display display = Display.getDefault();
            final Boolean[] answer = new Boolean[1];
            display.syncExec(() -> answer[0] = Boolean.valueOf(MessageDialog.openConfirm(
                shell == null ? new Shell(display) : shell,
                "Edit mode: change confirmation", message)));
            return answer[0] == null ? Boolean.FALSE : answer[0];
        }
    }

    /**
     * Applies search/replace edit blocks to an existing workspace file and
     * shows the resulting diff in a modal dialog before anything is written;
     * edit mode only. A refused or no-op script is reported back to the model
     * so it can repair it, and the file on disk is never left half-edited.
     */
    public static final class EditFileTool implements IToolExecutor {

        @Override
        public String getName() {
            return "edit_file";
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec("edit_file",
                "Change an existing workspace file with exact search/replace blocks. Only"
                    + " available in execute mode; the user confirms the diff in a dialog."
                    + " Every search fragment must occur EXACTLY ONCE in the current file"
                    + " content, copied verbatim from read_file output; blocks apply in"
                    + " order. Use an empty search to replace the whole file. Line endings"
                    + " of the file are preserved automatically.",
                editFileSchema());
        }

        @Override
        public String execute(final String theArgumentsJson) throws Exception {
            if(AgentMode.current() != ChatMode.EXECUTE) {
                return "ERROR: edit_file is disabled outside the execute mode";
            }
            final EditScript.ParseResult parsed = EditScript.parse(theArgumentsJson);
            if(!parsed.isOk()) {
                return "ERROR: " + parsed.getError();
            }
            final EditScript script = parsed.getScript();
            final Path file = resolve(script.getPath());
            final String original = Files.readString(file, StandardCharsets.UTF_8);
            final TextEditEngine.Result result =
                new TextEditEngine().apply(original, script.getBlocks());
            if(!result.isOk()) {
                return "ERROR: " + result.getError();
            }
            if(!result.isChanged()) {
                return "ERROR: the edit script changes nothing in this file."
                    + " Use read_file and copy the fragment exactly as it is now.";
            }
            final String diff = FileDiff.render(script.getPath(), original, result.getText());
            final Boolean confirmed = confirmDiffOnUiThread(script.getPath(), diff);
            if(confirmed == null || !confirmed.booleanValue()) {
                return "The user declined the change, nothing was written";
            }
            Files.writeString(file, result.getText(), StandardCharsets.UTF_8);
            final IPath workspacePath = new org.eclipse.core.runtime.Path(script.getPath());
            ResourcesPlugin.getWorkspace().getRoot().getFile(workspacePath).getProject()
                .refreshLocal(IResource.DEPTH_INFINITE, null);
            return "Applied " + result.getAppliedBlocks() + " edit blocks to "
                + script.getPath();
        }

        private static JsonObject editFileSchema() {
            final JsonObject blockProperties = new JsonObject();
            final JsonObject search = new JsonObject();
            search.addProperty("type", "string");
            search.addProperty("description",
                "exact fragment of the current file to find, must be unique");
            blockProperties.add("search", search);
            final JsonObject replace = new JsonObject();
            replace.addProperty("type", "string");
            replace.addProperty("description", "text that takes the fragment's place");
            blockProperties.add("replace", replace);
            final JsonObject block = new JsonObject();
            block.addProperty("type", "object");
            block.add("properties", blockProperties);
            final JsonArray blockRequired = new JsonArray();
            blockRequired.add("search");
            blockRequired.add("replace");
            block.add("required", blockRequired);
            final JsonObject properties = new JsonObject();
            final JsonObject path = new JsonObject();
            path.addProperty("type", "string");
            path.addProperty("description", "workspace-relative file path");
            properties.add("path", path);
            final JsonObject edits = new JsonObject();
            edits.addProperty("type", "array");
            edits.add("items", block);
            edits.addProperty("description", "search/replace blocks, applied in order");
            properties.add("edits", edits);
            final JsonArray required = new JsonArray();
            required.add("path");
            required.add("edits");
            final JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", properties);
            schema.add("required", required);
            return schema;
        }

        private static Boolean confirmDiffOnUiThread(final String thePath, final String theDiff) {
            final Shell shell = Display.getDefault().getActiveShell();
            final Boolean[] answer = new Boolean[1];
            Display.getDefault().syncExec(() -> {
                final DiffConfirmationDialog dialog = new DiffConfirmationDialog(
                    shell == null ? new Shell(Display.getDefault()) : shell, thePath, theDiff);
                answer[0] = Boolean.valueOf(dialog.open() == Dialog.OK);
            });
            return answer[0] == null ? Boolean.FALSE : answer[0];
        }
    }

    /** Modal OK/Cancel dialog showing the full diff in a monospace text area. */
    private static final class DiffConfirmationDialog extends Dialog {

        private final String pathText;
        private final String diffText;

        DiffConfirmationDialog(final Shell theParent, final String thePath, final String theDiff) {
            super(theParent);
            pathText = thePath;
            diffText = theDiff;
        }

        @Override
        protected org.eclipse.swt.widgets.Control createDialogArea(final Composite theParent) {
            final Composite area = (Composite)super.createDialogArea(theParent);
            final Label title = new Label(area, SWT.WRAP);
            title.setText("Edit mode: apply these changes to " + pathText + "?");
            GridDataFactory.fillDefaults().grab(true, false).hint(700, SWT.DEFAULT).applyTo(title);
            final Text diff = new Text(area, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            diff.setFont(org.eclipse.jface.resource.JFaceResources.getFontRegistry()
                .get(org.eclipse.jface.resource.JFaceResources.TEXT_FONT));
            diff.setText(diffText);
            GridDataFactory.fillDefaults().grab(true, true).hint(700, 400).applyTo(diff);
            return area;
        }

        @Override
        protected void configureShell(final Shell theShell) {
            super.configureShell(theShell);
            theShell.setText("Edit mode: change confirmation");
        }

        @Override
        protected void createButtonsForButtonBar(final Composite theParent) {
            createButton(theParent, Dialog.OK, "Apply", true);
            createButton(theParent, Dialog.CANCEL, "Cancel", false);
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
        public boolean isParallelSafe() {
            // the configuration picture is a prebuilt cache read as text
            return true;
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
