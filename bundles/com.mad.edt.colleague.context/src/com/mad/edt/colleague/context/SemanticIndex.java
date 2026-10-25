package com.mad.edt.colleague.context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import org.eclipse.core.resources.ResourcesPlugin;

import com.mad.edt.colleague.context.vector.EmbeddingClient;
import com.mad.edt.colleague.context.vector.QdrantClient;
import com.mad.edt.colleague.context.vector.VectorIndex;
import com.mad.edt.colleague.context.vector.VectorIndex.IndexFailure;
import com.mad.edt.colleague.context.vector.VectorIndex.SourceFile;
import com.mad.edt.colleague.context.vector.VectorIndex.UpdateReport;
import com.mad.edt.colleague.http.HttpTransport;

/**
 * Public entry point of the semantic context layer: builds a {@link VectorIndex}
 * from {@link SemanticSettings}, brings the workspace collection up once
 * ({@link #prepare()}) and refreshes it from the keyword index file list
 * ({@link #refreshFrom(FileSource)}). Until preparation succeeds the index
 * stays unready and {@link #readyIndex()} answers {@code null}, which keeps
 * the hybrid context layer on its silent keyword-only fallback. All calls
 * belong to a background job, never the UI thread.
 */
public final class SemanticIndex {

    /** Timeout of one vector store request. */
    public static final int STORE_TIMEOUT_MS = 10_000;

    /** Timeout of one embedding batch; first model loads can be slow. */
    public static final int EMBED_TIMEOUT_MS = 180_000;

    /** Every workspace collection carries this prefix. */
    public static final String COLLECTION_PREFIX = "colleague_";

    /** Project-name part budget, keeping collection names store safe. */
    public static final int PROJECT_NAME_BUDGET = 200;

    /**
     * Hands every searchable file to the acceptor as the pair
     * (absolute location, workspace-relative display path).
     */
    @FunctionalInterface
    public interface FileSource {

        /**
         * @param theAcceptor receives one pair per searchable file
         */
        void forEach(final BiConsumer<Path, String> theAcceptor);
    }

    /**
     * Failure keeping the semantic layer unusable: a refused collection,
     * an unreachable endpoint or a rejected batch.
     */
    public static final class SemanticFailure extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param theMessage failure description
         * @param theCause underlying index failure, may be {@code null}
         */
        public SemanticFailure(final String theMessage, final Throwable theCause) {
            super(theMessage, theCause);
        }
    }

    private final VectorIndex index;
    private volatile boolean ready;
    private volatile String lastError = "";

    private SemanticIndex(final VectorIndex theIndex) {
        index = theIndex;
    }

    /**
     * @return a new session index when the settings name a store and a model,
     *         {@code null} while the semantic layer is off
     */
    public static SemanticIndex tryCreate() {
        SemanticSettings.loadOnce();
        if(!SemanticSettings.isConfigured()) {
            return null;
        }
        return new SemanticIndex(new VectorIndex(collectionFor(workspaceProjectName()),
            new QdrantClient(SemanticSettings.getStoreUrl(), STORE_TIMEOUT_MS),
            new EmbeddingClient(SemanticSettings.resolveEmbedUrl(),
                SemanticSettings.getModel(), EMBED_TIMEOUT_MS)));
    }

    /**
     * Session stand-in driven by scripted endpoints instead of live URLs.
     *
     * @param theCollection collection name
     * @param theStoreTransport answers every store call
     * @param theEmbedTransport answers every embedding call
     * @return index usable in tests
     */
    static SemanticIndex forTesting(final String theCollection,
        final HttpTransport theStoreTransport, final HttpTransport theEmbedTransport) {
        return new SemanticIndex(new VectorIndex(theCollection,
            new QdrantClient("http://store.test:6333", 1_000, theStoreTransport),
            new EmbeddingClient("http://embed.test:11435", "test-model", 5_000,
                theEmbedTransport)));
    }

    /**
     * Store-safe collection name for one workspace project: characters the
     * store refuses in names become underscores, the name part is capped and
     * an empty project name still yields a usable name.
     *
     * @param theProjectName workspace project name
     * @return prefixed, sanitized collection name
     */
    static String collectionFor(final String theProjectName) {
        final String base = theProjectName == null ? "" : theProjectName.trim();
        final StringBuilder name = new StringBuilder(COLLECTION_PREFIX);
        final String letters = base.isEmpty() ? "workspace" : base;
        for(final char symbol : letters.toCharArray()) {
            if(name.length() >= COLLECTION_PREFIX.length() + PROJECT_NAME_BUDGET) {
                break;
            }
            if(symbol >= 'a' && symbol <= 'z' || symbol >= 'A' && symbol <= 'Z'
                || symbol >= '0' && symbol <= '9' || symbol == '-' || symbol == '_') {
                name.append(symbol);
            } else {
                name.append('_');
            }
        }
        return name.toString();
    }

    private static String workspaceProjectName() {
        final var projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for(final var project : projects) {
            if(project.isOpen()) {
                return project.getName();
            }
        }
        return projects.length == 0 ? "" : projects[0].getName();
    }

    /**
     * @return collection backing this session
     */
    public String getCollection() {
        return index.getCollection();
    }

    /**
     * Brings the collection in line with the embedding model once; failures
     * are remembered in {@link #getStatus()} and retried on the next call,
     * so a late-starting endpoint recovers without a restart.
     */
    public void prepare() {
        if(ready) {
            return;
        }
        try {
            index.ensureReady();
            ready = true;
            lastError = "";
        } catch (final IndexFailure ex) {
            lastError = ex.getMessage();
        }
    }

    /**
     * Aligns the collection with the current keyword index file list.
     *
     * @param theSources searchable files as known to the keyword index
     * @return counts of re-embedded, skipped and purged files
     * @throws SemanticFailure when the layer never became ready or the update
     *             failed; the failure text also stays in {@link #getStatus()}
     */
    public UpdateReport refreshFrom(final FileSource theSources) throws SemanticFailure {
        prepare();
        if(!ready) {
            throw new SemanticFailure("semantic layer unavailable: " + lastError, null);
        }
        final List<SourceFile> files = new ArrayList<>();
        theSources.forEach((location, displayPath) -> files.add(new SourceFile(location,
            displayPath)));
        try {
            final UpdateReport report = index.update(files);
            lastError = "";
            return report;
        } catch (final IndexFailure ex) {
            lastError = ex.getMessage();
            throw new SemanticFailure(ex.getMessage(), ex);
        }
    }

    /**
     * @return the index once preparation succeeded, {@code null} while the
     *         semantic layer is still unavailable
     */
    public VectorIndex readyIndex() {
        return ready ? index : null;
    }

    /**
     * @return whether preparation succeeded and the hybrid layer can use
     *         semantic chunks
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * Wraps a keyword context source into the hybrid one on top of this
     * layer: keyword text first, semantic chunks only while the layer is
     * ready. The caller never sees the internal index types.
     *
     * @param theKeyword keyword search supplying the first section
     * @return hybrid source for chat context gathering
     */
    public ContextSource withKeyword(final ContextSource theKeyword) {
        return new HybridContextSource(theKeyword, this::readyIndex);
    }

    /**
     * Refresh pass that never raises: the result (or the failure) comes back
     * as one line for the job log, and a failure stays remembered in
     * {@link #getStatus()} for the next retry.
     *
     * @param theSources searchable files as known to the keyword index
     * @return one-line pass description
     */
    public String refreshAndLog(final FileSource theSources) {
        try {
            final UpdateReport report = refreshFrom(theSources);
            return "collection " + getCollection() + ": re-embedded "
                + report.reembedded() + ", skipped " + report.skipped()
                + ", purged " + report.purged();
        } catch (final SemanticFailure ex) {
            return "refresh failed: " + ex.getMessage();
        }
    }

    /**
     * @return human-readable layer state for logs and diagnostics
     */
    public String getStatus() {
        if(ready) {
            return "ready, collection " + index.getCollection() + ", tracked files "
                + index.trackedFileCount();
        }
        return "not ready: " + lastError;
    }
}
