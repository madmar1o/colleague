package com.mad.edt.colleague.agent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * Answers configuration-tree questions from metadata files: locate an
 * object by name, read its attributes and tab sections. Read-only, always
 * offered, bounded by the shared tool result budget.
 */
public final class MetadataTools {

    /** Argument field name of the metadata search tool. */
    private static final String QUERY = "query";

    /**
     * One EDT project whose metadata tree the tools may read: the project
     * name is what the model sees in paths, the folder is the {@code src}
     * directory that holds the configuration tree.
     *
     * @param project name of the EDT project, first path segment in answers
     * @param srcDirectory the {@code src} folder of that project, never null
     */
    public record Root(String project, Path srcDirectory) {

        /**
         * Rejects a root that could not produce a usable path.
         */
        public Root {
            if(project == null || project.isBlank() || srcDirectory == null) {
                throw new IllegalArgumentException("a metadata root needs a project name and a src folder");
            }
        }
    }

    /**
     * Ceiling of one answer, a little under the loop's own result budget so
     * the answer is cut here, where the cut is announced, and not there.
     */
    private static final int MAX_RESULT_CHARS = 3_800;

    /** Room kept for the closing "more items" line of a trimmed answer. */
    private static final int RESERVE_CHARS = 140;

    /** Characters read from a metadata file when only its head matters. */
    private static final int HEAD_CHARS = 60_000;

    /** Characters parsed from one object file; bigger ones are cut honestly. */
    private static final int PARSED_CHARS = 4_000_000;

    /** Candidate files whose head is read before the search gives up. */
    private static final int MAX_HEAD_READS = 60;

    /** Objects one search reports. */
    private static final int MAX_HITS = 20;

    /** Attribute names listed for one tabular section. */
    private static final int FIELDS_PER_SECTION = 8;

    private final Supplier<List<Root>> roots;

    /**
     * @param theRoots supplier of the metadata trees to read, consulted on
     *            every call so opened and closed projects are seen; may
     *            return an empty list when no configuration is open
     */
    public MetadataTools(final Supplier<List<Root>> theRoots) {
        roots = theRoots;
    }

    /**
     * @return the two read-only tools, in the order they are advertised
     */
    public List<IToolExecutor> tools() {
        return List.of(new FindMetadataTool(), new ReadMetadataTool());
    }

    /**
     * @return the metadata trees supplied for this call, empty when the
     *         supplier is missing or returns nothing
     */
    private List<Root> configuredRoots() {
        final List<Root> supplied = roots == null ? null : roots.get();
        return supplied == null ? List.of() : supplied;
    }

    /**
     * Locates objects whose technical name or russian presentation contains
     * the query, reading only the head of the candidate files: the folder
     * names narrow the tree first, so a 27 000 file configuration costs a few
     * dozen small reads instead of a full scan.
     *
     * @param theQuery words the user used, matched case-insensitively
     * @param theKind optional metadata kind filter, null or blank for any
     * @return matching objects, exact names first
     */
    private List<Hit> find(final String theQuery, final String theKind) {
        final String needle = theQuery == null ? "" : theQuery.trim().toLowerCase(Locale.ROOT);
        if(needle.isEmpty()) {
            return List.of();
        }
        final String kindNeedle = theKind == null ? "" : normalizeKind(theKind);
        final List<Path> candidates = new ArrayList<>();
        for(final Root root : configuredRoots()) {
            collect(root, needle, kindNeedle, candidates);
        }
        candidates.sort(Comparator.comparingInt((Path candidate) -> rank(candidate, needle)));
        final List<Hit> hits = new ArrayList<>();
        int reads = 0;
        boolean budgetOpen = true;
        // single loop exit: hit and read budgets close the walk through the
        // while condition, non-matching heads are filtered in place
        for(int step = 0; budgetOpen && step < candidates.size(); step++) {
            final Path candidate = candidates.get(step);
            if(hits.size() >= MAX_HITS || reads >= MAX_HEAD_READS) {
                budgetOpen = false;
            } else {
                reads++;
                final Head head = readHead(candidate);
                if(head.name != null && headMatches(head, needle, kindNeedle)) {
                    hits.add(new Hit(head.kind, head.name, head.synonymRu, candidate));
                }
            }
        }
        return hits;
    }

    /**
     * @param theHead parsed head of a candidate file
     * @param theNeedle lower-cased query
     * @param theKindNeedle normalized kind filter, empty for any kind
     * @return whether the head passes the name and kind filters
     */
    private static boolean headMatches(final Head theHead, final String theNeedle,
        final String theKindNeedle) {
        final String name = theHead.name.toLowerCase(Locale.ROOT);
        final String presented = theHead.synonymRu == null
            ? "" : theHead.synonymRu.toLowerCase(Locale.ROOT);
        if(!name.contains(theNeedle) && !presented.contains(theNeedle)) {
            return false;
        }
        return theKindNeedle.isEmpty() || normalizeKind(theHead.kind).equals(theKindNeedle);
    }

    /**
     * Appends the candidate object files of one project whose folder name
     * matches the query.
     *
     * @param theRoot project to walk
     * @param theNeedle lower-cased query
     * @param theKindNeedle normalized kind filter, empty for any kind
     * @param theCandidates list the candidate files are added to
     */
    private static void collect(final Root theRoot, final String theNeedle, final String theKindNeedle,
        final List<Path> theCandidates) {
        final Path src = theRoot.srcDirectory();
        if(src == null || !Files.isDirectory(src)) {
            return;
        }
        for(final Path kindFolder : children(src)) {
            collectInKindFolder(kindFolder, theNeedle, theKindNeedle, theCandidates);
        }
    }

    /**
     * Adds the object files of one kind folder, the configuration file being
     * a single special case.
     *
     * @param theKindFolder folder named after a metadata kind
     * @param theNeedle lower-cased query
     * @param theKindNeedle normalized kind filter, empty for any kind
     * @param theCandidates list the candidate files are added to
     */
    private static void collectInKindFolder(final Path theKindFolder, final String theNeedle,
        final String theKindNeedle, final List<Path> theCandidates) {
        final String folderName = theKindFolder.getFileName().toString();
        if(!theKindNeedle.isEmpty() && !normalizeKind(folderName).equals(theKindNeedle)) {
            return;
        }
        if("Configuration".equals(folderName)) {
            final Path file = theKindFolder.resolve("Configuration.mdo");
            if(Files.isRegularFile(file)) {
                theCandidates.add(file);
            }
            return;
        }
        for(final Path objectFolder : children(theKindFolder)) {
            final String objectName = objectFolder.getFileName().toString();
            if(!objectName.toLowerCase(Locale.ROOT).contains(theNeedle)) {
                continue;
            }
            // form and report descriptors live beside the object file and
            // are not objects themselves, so the exact file name is asked
            final Path file = objectFolder.resolve(objectName + ".mdo");
            if(Files.isRegularFile(file)) {
                theCandidates.add(file);
            }
        }
    }

    /**
     * @param theFile candidate metadata file
     * @return the head of the file parsed into kind, name and presentation
     */
    private static Head readHead(final Path theFile) {
        final String head = readText(theFile, HEAD_CHARS);
        if(head == null) {
            return Head.EMPTY;
        }
        return new Head(rootKind(head), firstTagValue(head, "<name>", "</name>"),
            ruPresentation(head));
    }

    /**
     * @param theFile metadata file to locate
     * @return its workspace-relative path, or the bare file name when the
     *         file sits outside every supplied root
     */
    // shared by FindMetadataTool and ReadMetadataTool, so it stays package
    // visible: S3398 only suggests moving methods with a single inner caller
    String pathOf(final Path theFile) {
        final Path absolute = theFile.toAbsolutePath().normalize();
        for(final Root root : configuredRoots()) {
            final Path src = root.srcDirectory().toAbsolutePath().normalize();
            if(absolute.startsWith(src)) {
                return root.project() + "/src/" + src.relativize(absolute).toString()
                    .replace('\\', '/');
            }
        }
        return theFile.getFileName().toString();
    }

    /**
     * Parses one metadata file into the picture the model asks for: kind,
     * name, presentation, attributes, tabular sections with their own
     * attributes, register dimensions and resources. The scan tracks element
     * depth, so the many nested name elements never overwrite the object name
     * and an unclosed file is reported instead of throwing.
     *
     * @param theText file content, possibly cut at its end
     * @return parsed snapshot, never null
     */
    private static Snapshot parse(final String theText) {
        final Snapshot snapshot = new Snapshot();
        final Deque<String> open = new ArrayDeque<>();
        final StringBuilder buffer = new StringBuilder();
        final int length = theText.length();
        int index = 0;
        while(index < length) {
            if(theText.charAt(index) != '<') {
                buffer.append(theText.charAt(index));
                index++;
                continue;
            }
            index = skipMarkup(snapshot, open, buffer, theText, index, length);
        }
        flush(snapshot, open, buffer);
        if(!open.isEmpty()) {
            snapshot.incomplete = true;
        }
        return snapshot;
    }

    /**
     * Handles one markup construct at the given position and returns the
     * position right after it: processing instructions, comments and CDATA
     * sections are skipped or appended, an opening or closing tag updates the
     * element stack, an unclosed tag marks the snapshot incomplete.
     *
     * @param theSnapshot snapshot being built
     * @param theOpen stack of open element names
     * @param theBuffer collected text
     * @param theText whole document
     * @param theIndex position of the opening angle bracket
     * @param theLength document length
     * @return position right after the construct
     */
    private static int skipMarkup(final Snapshot theSnapshot, final Deque<String> theOpen,
        final StringBuilder theBuffer, final String theText, final int theIndex,
        final int theLength) {
        if(theText.startsWith("<?", theIndex)) {
            return skipPast(theText, theIndex + 2, "?>");
        }
        if(theText.startsWith("<!--", theIndex)) {
            return skipPast(theText, theIndex + 4, "-->");
        }
        if(theText.startsWith("<![CDATA[", theIndex)) {
            return appendCdata(theText, theIndex, theBuffer, theLength);
        }
        final int close = theText.indexOf('>', theIndex);
        if(close < 0) {
            theSnapshot.incomplete = true;
            return theLength;
        }
        flush(theSnapshot, theOpen, theBuffer);
        final String raw = theText.substring(theIndex + 1, close).trim();
        if(!raw.isEmpty() && raw.charAt(0) == '/') {
            pop(theOpen, raw.substring(1));
        } else {
            applyOpenTag(theSnapshot, theOpen, raw);
        }
        return close + 1;
    }

    /**
     * Appends the text inside a CDATA section to the buffer.
     *
     * @param theText whole document
     * @param theIndex position of the section opening
     * @param theBuffer collected text
     * @param theLength document length, used when the section is unclosed
     * @return position right after the section
     */
    private static int appendCdata(final String theText, final int theIndex,
        final StringBuilder theBuffer, final int theLength) {
        final int end = theText.indexOf("]]>", theIndex + 9);
        if(end < 0) {
            theBuffer.append(theText, theIndex + 9, theLength);
            return theLength;
        }
        theBuffer.append(theText, theIndex + 9, end);
        return end + 3;
    }

    /**
     * Handles an opening tag: the root element names the kind, and a tag that
     * is not self-closing pushes its name on the open path.
     *
     * @param theSnapshot snapshot being filled
     * @param theOpen currently open elements, innermost last
     * @param theRaw tag text between the angle brackets
     */
    private static void applyOpenTag(final Snapshot theSnapshot, final Deque<String> theOpen,
        final String theRaw) {
        if(theRaw.isEmpty()) {
            return;
        }
        final String name = localName(theRaw);
        if(name.isEmpty()) {
            return;
        }
        if(theOpen.isEmpty() && theSnapshot.kind == null) {
            theSnapshot.kind = name;
        }
        if(!theRaw.endsWith("/")) {
            theOpen.addLast(name);
        }
    }

    /**
     * Folds the text collected since the previous tag into the snapshot; the
     * open element path tells which value the text belongs to.
     *
     * @param theSnapshot snapshot being filled
     * @param theOpen currently open elements, innermost last
     * @param theBuffer collected text, cleared afterwards
     */
    private static void flush(final Snapshot theSnapshot, final Deque<String> theOpen,
        final StringBuilder theBuffer) {
        final String value = unescape(theBuffer.toString()).trim();
        theBuffer.setLength(0);
        if(value.isEmpty() || theOpen.isEmpty()) {
            return;
        }
        final List<String> path = new ArrayList<>(theOpen);
        applyValue(theSnapshot, path, path.size(), value);
    }

    /**
     * Routes one collected text to the snapshot slot its element path names.
     *
     * @param theSnapshot snapshot being filled
     * @param thePath open element path, innermost last
     * @param theDepth number of elements in the path
     * @param theValue trimmed text of the innermost element
     */
    private static void applyValue(final Snapshot theSnapshot, final List<String> thePath,
        final int theDepth, final String theValue) {
        final String tag = thePath.get(theDepth - 1);
        final String parent = theDepth >= 2 ? thePath.get(theDepth - 2) : "";
        if("name".equals(tag)) {
            applyName(theSnapshot, thePath, theDepth, parent, theValue);
        } else if(theDepth == 3 && "synonym".equals(thePath.get(1))) {
            applySynonym(theSnapshot, tag, theValue);
        } else if("types".equals(tag) && theSnapshot.currentField != null) {
            theSnapshot.currentField.types.add(theValue);
        } else if(isQualifierTag(tag) && theSnapshot.currentField != null && theDepth >= 4) {
            theSnapshot.currentField.qualify(tag, theValue);
        }
    }

    /**
     * @param theSnapshot snapshot being filled
     * @param thePath open element path
     * @param theDepth number of elements in the path
     * @param theContainer element one level above the name
     * @param theValue name text
     */
    private static void applyName(final Snapshot theSnapshot, final List<String> thePath,
        final int theDepth, final String theContainer, final String theValue) {
        if(theDepth == 2) {
            if(theSnapshot.name == null) {
                theSnapshot.name = theValue;
            }
        } else if(theDepth == 3 && isFieldContainer(theContainer)) {
            theSnapshot.beginField(theContainer, theValue);
        } else if(theDepth == 3 && "tabularSections".equals(theContainer)) {
            theSnapshot.beginSection(theValue);
        } else if(theDepth == 4 && "attributes".equals(theContainer)
            && "tabularSections".equals(thePath.get(1))) {
            theSnapshot.beginSectionField(theValue);
        }
    }

    /**
     * @param theSnapshot snapshot being filled
     * @param theTag key or value element inside a synonym pair
     * @param theValue pair text
     */
    private static void applySynonym(final Snapshot theSnapshot, final String theTag,
        final String theValue) {
        if("key".equals(theTag)) {
            theSnapshot.synonymKey = theValue;
        } else if("value".equals(theTag) && "ru".equals(theSnapshot.synonymKey)) {
            theSnapshot.synonymRu = theValue;
        }
    }

    /**
     * @param theTag element name
     * @return whether the element qualifies the current field
     */
    private static boolean isQualifierTag(final String theTag) {
        return "length".equals(theTag) || "precision".equals(theTag) || "scale".equals(theTag);
    }

    /**
     * @param theName element name of a depth-1 container
     * @return whether that container holds plain fields
     */
    private static boolean isFieldContainer(final String theName) {
        return "attributes".equals(theName) || "dimensions".equals(theName)
            || "resources".equals(theName);
    }

    /**
     * @param theOpen open elements
     * @param theName closing element name
     */
    private static void pop(final Deque<String> theOpen, final String theName) {
        final String wanted = localName(theName);
        while(!theOpen.isEmpty()) {
            if(theOpen.removeLast().equals(wanted)) {
                return;
            }
        }
    }

    /**
     * @param theText text to scan
     * @param theFrom index to start at
     * @param theMarker marker that ends the skipped region
     * @return index just past the marker, or the end of the text
     */
    private static int skipPast(final String theText, final int theFrom, final String theMarker) {
        final int at = theText.indexOf(theMarker, theFrom);
        return at < 0 ? theText.length() : at + theMarker.length();
    }

    /**
     * @param theRaw tag text without angle brackets, e.g. {@code mdclass:Catalog uuid="1"}
     * @return element name without its namespace prefix
     */
    private static String localName(final String theRaw) {
        String text = theRaw.trim();
        if(text.endsWith("/")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        final int space = text.indexOf(' ');
        if(space >= 0) {
            text = text.substring(0, space);
        }
        final int colon = text.indexOf(':');
        return colon < 0 ? text : text.substring(colon + 1);
    }

    /**
     * @param theText file head
     * @return name of the root element, null when the head holds no element
     */
    private static String rootKind(final String theText) {
        int index = 0;
        while(index < theText.length()) {
            final int at = theText.indexOf('<', index);
            if(at < 0) {
                return null;
            }
            if(theText.startsWith("<?", at)) {
                index = skipPast(theText, at + 2, "?>");
            } else if(theText.startsWith("<!", at)) {
                index = skipPast(theText, at + 2, ">");
            } else {
                final int close = theText.indexOf('>', at);
                if(close < 0) {
                    return null;
                }
                final String name = localName(theText.substring(at + 1, close));
                return name.isEmpty() ? null : name;
            }
        }
        return null;
    }

    /**
     * @param theText file head
     * @return the russian presentation of the object, null when it has none
     */
    private static String ruPresentation(final String theText) {
        final int at = theText.indexOf("<key>ru</key>");
        return at < 0 ? null : firstTagValue(theText.substring(at), "<value>", "</value>");
    }

    /**
     * @param theText text to search
     * @param theOpen opening tag
     * @param theClose closing tag
     * @return text between the first opening and the following closing tag,
     *         unescaped, or null when the pair is absent
     */
    private static String firstTagValue(final String theText, final String theOpen,
        final String theClose) {
        final int at = theText.indexOf(theOpen);
        if(at < 0) {
            return null;
        }
        final int end = theText.indexOf(theClose, at + theOpen.length());
        if(end <= at) {
            return null;
        }
        return unescape(theText.substring(at + theOpen.length(), end)).trim();
    }

    /**
     * @param theText text read from a metadata file
     * @return the same text with the entity escapes XML uses in values
     */
    private static String unescape(final String theText) {
        return theText.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&amp;", "&");
    }

    /**
     * @param theKind kind as the user or the model named it
     * @return lower-cased kind without the plural ending, so Catalog and
     *         Catalogs compare equal
     */
    private static String normalizeKind(final String theKind) {
        String text = theKind == null ? "" : theKind.trim().toLowerCase(Locale.ROOT);
        if(text.endsWith("es")) {
            text = text.substring(0, text.length() - 2);
        } else if(text.endsWith("s")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * @param theFolder folder to list
     * @return its children, empty when the folder cannot be read
     */
    private static List<Path> children(final Path theFolder) {
        final List<Path> found = new ArrayList<>();
        try (Stream<Path> stream = Files.list(theFolder)) {
            stream.forEach(found::add);
        } catch (final IOException | RuntimeException ex) {
            return List.of();
        }
        found.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return found;
    }

    /**
     * @param theFile file to read
     * @param theMaxChars characters to read at most
     * @return the text, or null when the file cannot be opened
     */
    private static String readText(final Path theFile, final int theMaxChars) {
        final StringBuilder text = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(theFile),
            StandardCharsets.UTF_8)) {
            final char[] buffer = new char[8192];
            int read;
            while(text.length() < theMaxChars && (read = reader.read(buffer)) > 0) {
                text.append(buffer, 0, read);
            }
        } catch (final IOException | RuntimeException ex) {
            return null;
        }
        return text.toString();
    }

    /**
     * @param theCandidate candidate file
     * @param theNeedle lower-cased query
     * @return 0 for an exact folder name, 1 for a prefix, 2 for a substring,
     *         3 for everything else
     */
    private static int rank(final Path theCandidate, final String theNeedle) {
        final String name = theCandidate.getParent().getFileName().toString()
            .toLowerCase(Locale.ROOT);
        if(name.equals(theNeedle)) {
            return 0;
        }
        if(name.startsWith(theNeedle)) {
            return 1;
        }
        return name.contains(theNeedle) ? 2 : 3;
    }

    /**
     * @param theDescription argument description
     * @return one JSON-Schema property of type string
     */
    private static JsonObject stringProperty(final String theDescription) {
        final JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", theDescription);
        return property;
    }

    /**
     * @param theProperties properties of the arguments object
     * @param theRequired names the model must pass
     * @return arguments schema for one tool
     */
    private static JsonObject schema(final JsonObject theProperties, final String... theRequired) {
        final JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", theProperties);
        if(theRequired.length > 0) {
            final JsonArray required = new JsonArray();
            for(final String name : theRequired) {
                required.add(name);
            }
            schema.add("required", required);
        }
        return schema;
    }

    /**
     * @param theArgumentsJson arguments as the model emitted them
     * @return parsed object, null when the text is not a JSON object
     */
    private static JsonObject parseArguments(final String theArgumentsJson) {
        try {
            return JsonParser.parseString(theArgumentsJson).getAsJsonObject();
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /**
     * @param theArguments parsed arguments, may be null
     * @param theName argument name
     * @return the string value, null when absent or JSON null
     */
    private static String stringArgument(final JsonObject theArguments, final String theName) {
        if(theArguments == null || !theArguments.has(theName)
            || theArguments.get(theName).isJsonNull()) {
            return null;
        }
        return theArguments.get(theName).getAsString();
    }

    /** Answers where a metadata object lives in the configuration tree. */
    private final class FindMetadataTool implements IToolExecutor {

        @Override
        public String getName() {
            return "find_metadata";
        }

        @Override
        public boolean isParallelSafe() {
            // only folder names and file heads are read, never written
            return true;
        }

        @Override
        public ToolSpec getSpec() {
            final JsonObject properties = new JsonObject();
            properties.add(QUERY, stringProperty(
                "object name or its part as the user says it, e.g. Валюты or Заказ"));
            properties.add("kind", stringProperty(
                "optional metadata kind: Catalog, Document, InformationRegister, ..."));
            return new ToolSpec("find_metadata",
                "Find configuration metadata objects (catalogs, documents, registers, ...) by"
                    + " name and optional kind. Returns each match as kind, name, russian"
                    + " presentation and the workspace-relative .mdo path, exact names first."
                    + " Call this before read_file whenever the question names a metadata"
                    + " object instead of a source line.",
                schema(properties, QUERY));
        }

        @Override
        public String execute(final String theArgumentsJson) {
            final JsonObject arguments = parseArguments(theArgumentsJson);
            final String query = stringArgument(arguments, QUERY);
            if(query == null || query.isBlank()) {
                return "ERROR: pass a non-empty query";
            }
            final List<Hit> hits = find(query, stringArgument(arguments, "kind"));
            if(hits.isEmpty()) {
                return "No metadata objects match \"" + query + "\". Try list_files or"
                    + " search_code for the name, or pass a kind to narrow the search.";
            }
            final Budget out = new Budget();
            for(int index = 0; index < hits.size(); index++) {
                final Hit hit = hits.get(index);
                final StringBuilder line = new StringBuilder(hit.kind()).append(' ')
                    .append(hit.name());
                if(hit.synonymRu() != null && !hit.synonymRu().equals(hit.name())) {
                    line.append(" (").append(hit.synonymRu()).append(')');
                }
                line.append(" | ").append(pathOf(hit.file()));
                if(!out.line(line.toString())) {
                    out.skip(hits.size() - index);
                    break;
                }
            }
            return out.finish();
        }
    }

    /** Reads one metadata object as a compact picture of its fields. */
    private final class ReadMetadataTool implements IToolExecutor {

        @Override
        public String getName() {
            return "read_metadata";
        }

        @Override
        public boolean isParallelSafe() {
            return true;
        }

        @Override
        public ToolSpec getSpec() {
            final JsonObject properties = new JsonObject();
            properties.add("path", stringProperty(
                "workspace-relative .mdo path find_metadata returned"));
            properties.add("name", stringProperty(
                "object name, used when no path is passed"));
            properties.add("kind", stringProperty(
                "optional metadata kind that goes with the name"));
            return new ToolSpec("read_metadata",
                "Read one configuration metadata object: kind, names, attributes, tabular"
                    + " sections with their own attributes, register dimensions and resources."
                    + " Pass the path find_metadata returned; a name works when it matches one"
                    + " object. The answer stays under the shared tool result budget.",
                schema(properties));
        }

        @Override
        public String execute(final String theArgumentsJson) {
            final JsonObject arguments = parseArguments(theArgumentsJson);
            final String path = stringArgument(arguments, "path");
            if(path != null && !path.isBlank()) {
                try {
                    return describe(resolve(path));
                } catch (final IOException ex) {
                    return "ERROR: " + ex.getMessage();
                }
            }
            final String name = stringArgument(arguments, "name");
            if(name == null || name.isBlank()) {
                return "ERROR: pass the path find_metadata returned, or an object name";
            }
            final List<Hit> hits = find(name, stringArgument(arguments, "kind"));
            if(hits.isEmpty()) {
                return "No metadata objects match \"" + name
                    + "\". Call find_metadata first to get the exact path.";
            }
            if(hits.size() > 1) {
                final StringBuilder answer = new StringBuilder("Several objects match \"")
                    .append(name).append("\", pass the path instead:\n");
                for(int index = 0; index < hits.size() && index < 5; index++) {
                    answer.append("  ").append(pathOf(hits.get(index).file()))
                        .append('\n');
                }
                return answer.toString();
            }
            return describe(hits.get(0).file());
        }

        /**
         * @param theFile metadata file to read
         * @return the whole object summary, or an explanation of why it was not read
         */
        private String describe(final Path theFile) {
            final String content = readText(theFile, PARSED_CHARS);
            if(content == null) {
                return "ERROR: cannot read " + pathOf(theFile);
            }
            final Snapshot snapshot = parse(content);
            if(snapshot.kind == null || snapshot.name == null) {
                return "ERROR: " + pathOf(theFile)
                    + " is not a metadata object file (no root element or name)";
            }
            final Budget out = new Budget();
            final StringBuilder title = new StringBuilder(snapshot.kind).append(' ')
                .append(snapshot.name);
            if(snapshot.synonymRu != null && !snapshot.synonymRu.equals(snapshot.name)) {
                title.append(" (").append(snapshot.synonymRu).append(')');
            }
            out.line(title.toString());
            out.line("Path: " + pathOf(theFile));
            fields(out, "Attributes", snapshot.attributes);
            sections(out, snapshot.sections);
            fields(out, "Dimensions", snapshot.dimensions);
            fields(out, "Resources", snapshot.resources);
            if(snapshot.incomplete) {
                out.line("(the file ends inside unclosed elements, the summary is incomplete)");
            }
            if(snapshot.sections.isEmpty() && snapshot.attributes.isEmpty()
                && snapshot.dimensions.isEmpty() && snapshot.resources.isEmpty()) {
                out.line("(no attributes, tabular sections, dimensions or resources)");
            }
            return out.finish();
        }

        /**
         * @param theText workspace-relative path of a metadata file
         * @return the file inside one of the supplied roots
         * @throws IOException when the path is empty, escapes the tree or names a
         *             project that is not open
         */
        private Path resolve(final String theText) throws IOException {
            final String text = theText == null ? "" : theText.trim().replace('\\', '/');
            if(text.isEmpty()) {
                throw new IOException("pass the path find_metadata returned");
            }
            final int slash = text.indexOf('/');
            final String project = slash < 0 ? "" : text.substring(0, slash);
            String rest = slash < 0 ? text : text.substring(slash + 1);
            // answers carry the "src/" segment of the EDT export, the root is
            // already that folder, so the prefix is dropped instead of doubled
            if("src".equals(rest) || rest.startsWith("src/")) {
                rest = rest.length() <= 3 ? "" : rest.substring(4);
            }
            for(final Root root : configuredRoots()) {
                if(!root.project().equals(project)) {
                    continue;
                }
                final Path src = root.srcDirectory().toAbsolutePath().normalize();
                final Path file = src.resolve(rest).normalize();
                if(!file.startsWith(src)) {
                    throw new IOException("path escapes the configuration tree: " + theText);
                }
                return file;
            }
            throw new IOException("unknown project \"" + project
                + "\" in the path, pass the path find_metadata returned");
        }

        /**
         * @param theOut answer being built
         * @param theLabel heading of the block
         * @param theFields fields to list, skipped entirely when the block does
         *            not fit
         */
        private static void fields(final Budget theOut, final String theLabel,
            final List<Field> theFields) {
            if(theFields.isEmpty()) {
                return;
            }
            if(!theOut.line(theLabel + " (" + theFields.size() + "):")) {
                theOut.skip(theFields.size());
                return;
            }
            for(int index = 0; index < theFields.size(); index++) {
                if(!theOut.line("  " + theFields.get(index).render())) {
                    theOut.skip(theFields.size() - index);
                    return;
                }
            }
        }

        /**
         * @param theOut answer being built
         * @param theSections tabular sections to list with the first attribute
         *            names of each
         */
        private static void sections(final Budget theOut, final List<Section> theSections) {
            if(theSections.isEmpty()) {
                return;
            }
            if(!theOut.line("Tabular sections (" + theSections.size() + "):")) {
                theOut.skip(theSections.size());
                return;
            }
            for(int index = 0; index < theSections.size(); index++) {
                if(!theOut.line(sectionLine(theSections.get(index)))) {
                    theOut.skip(theSections.size() - index);
                    return;
                }
            }
        }

        /**
         * @param theSection section to render
         * @return its line with the first attribute names of the section
         */
        private static String sectionLine(final Section theSection) {
            final StringBuilder line = new StringBuilder("  ").append(theSection.name);
            if(!theSection.fields.isEmpty()) {
                appendFirstFields(line, theSection.fields);
                if(theSection.fields.size() > FIELDS_PER_SECTION) {
                    line.append(", ...");
                }
            }
            return line.toString();
        }

        /**
         * @param theLine line being built
         * @param theFields section fields, only the first ones are listed
         */
        private static void appendFirstFields(final StringBuilder theLine, final List<Field> theFields) {
            theLine.append(" (").append(theFields.size()).append("): ");
            for(int field = 0; field < theFields.size() && field < FIELDS_PER_SECTION; field++) {
                if(field > 0) {
                    theLine.append(", ");
                }
                theLine.append(theFields.get(field).render());
            }
        }
    }

    /** Head of a metadata file: kind, technical name and presentation. */
    private record Head(String kind, String name, String synonymRu) {

        /** Empty head of an unreadable file. */
        static final Head EMPTY = new Head(null, null, null);
    }

    /** One matching object. */
    private record Hit(String kind, String name, String synonymRu, Path file) {
    }

    /** One attribute, dimension or resource of an object. */
    private static final class Field {

        private final String name;

        private final List<String> types = new ArrayList<>();

        private String length;

        private String precision;

        private String scale;

        /**
         * @param theName field name
         */
        Field(final String theName) {
            name = theName;
        }

        /**
         * @param theTag qualifier element name: length, precision or scale
         * @param theValue its value
         */
        void qualify(final String theTag, final String theValue) {
            if("length".equals(theTag)) {
                length = theValue;
            } else if("precision".equals(theTag)) {
                precision = theValue;
            } else {
                scale = theValue;
            }
        }

        /**
         * @return the field as {@code Name: Type(qualifiers)}, name only when
         *         the type is not declared
         */
        String render() {
            final StringBuilder out = new StringBuilder(name);
            if(!types.isEmpty()) {
                out.append(": ").append(String.join(", ", types));
                if(length != null) {
                    out.append('(').append(length).append(')');
                } else if(precision != null) {
                    out.append('(').append(precision);
                    if(scale != null) {
                        out.append(',').append(scale);
                    }
                    out.append(')');
                }
            }
            return out.toString();
        }
    }

    /** One tabular section with its own attributes. */
    private static final class Section {

        private final String name;

        private final List<Field> fields = new ArrayList<>();

        /**
         * @param theName section name
         */
        Section(final String theName) {
            name = theName;
        }
    }

    /** Everything one metadata file says about its object. */
    private static final class Snapshot {

        private String kind;

        private String name;

        private String synonymRu;

        private String synonymKey;

        private final List<Field> attributes = new ArrayList<>();

        private final List<Field> dimensions = new ArrayList<>();

        private final List<Field> resources = new ArrayList<>();

        private final List<Section> sections = new ArrayList<>();

        private Field currentField;

        private Section currentSection;

        private boolean incomplete;

        /**
         * @param theContainer depth-1 container name
         * @param theName field name
         */
        void beginField(final String theContainer, final String theName) {
            final Field field = new Field(theName);
            if("dimensions".equals(theContainer)) {
                dimensions.add(field);
            } else if("resources".equals(theContainer)) {
                resources.add(field);
            } else {
                attributes.add(field);
            }
            currentField = field;
        }

        /**
         * @param theName tabular section name
         */
        void beginSection(final String theName) {
            currentSection = new Section(theName);
            sections.add(currentSection);
        }

        /**
         * @param theName attribute of the current tabular section
         */
        void beginSectionField(final String theName) {
            final Field field = new Field(theName);
            if(currentSection != null) {
                currentSection.fields.add(field);
            }
            currentField = field;
        }
    }

    /** Answer buffer that stops at the budget and counts what did not fit. */
    private static final class Budget {

        private final StringBuilder text = new StringBuilder();

        private int skipped;

        /**
         * @param theLine line to append
         * @return whether the line fit
         */
        boolean line(final String theLine) {
            if(text.length() + theLine.length() + 1 > MAX_RESULT_CHARS - RESERVE_CHARS) {
                return false;
            }
            text.append(theLine).append('\n');
            return true;
        }

        /**
         * @param theItems how many further items were left out
         */
        void skip(final int theItems) {
            skipped += theItems;
        }

        /**
         * @return the answer with the closing note about the cut, when any
         */
        String finish() {
            if(skipped > 0) {
                text.append("... (").append(skipped)
                    .append(" more items; ask for one object at a time or read the .mdo"
                        + " with read_file)\n");
            }
            return text.toString();
        }
    }
}
