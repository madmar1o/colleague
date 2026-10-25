package com.mad.edt.colleague.context.vector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.mad.edt.colleague.context.vector.EmbeddingClient.EmbeddingException;
import com.mad.edt.colleague.context.vector.QdrantClient.Point;
import com.mad.edt.colleague.context.vector.QdrantClient.QdrantException;

/**
 * Semantic module index over the vector store: source files are chunked into
 * overlapping windows, embedded and stored under deterministic point ids, so
 * the same chunk always overwrites itself and no bookkeeping outside the
 * server is needed. Updates are incremental per file - the content hash of
 * every indexed file is remembered, and only files whose hash or size moved
 * are re-embedded after their stale points were deleted. Similarity search
 * renders chunks as model-facing blocks with the file path and line range.
 * Call from a background job; the state map is only touched there. The class
 * is subclassable so tests can stand in for a ready index without servers.
 */
public class VectorIndex {

    /** Lines per embedding window. */
    public static final int CHUNK_LINES = 55;

    /** Lines repeated at the window border. */
    public static final int OVERLAP_LINES = 10;

    /** Payload field naming the workspace-relative file of a chunk. */
    public static final String PATH_FIELD = "path";

    /**
     * One source file offered to the index.
     */
    public static final class SourceFile {

        private final Path ourLocation;
        private final String ourDisplayPath;

        /**
         * @param theLocation absolute filesystem path of the file
         * @param theDisplayPath workspace-relative path used in payloads
         */
        public SourceFile(final Path theLocation, final String theDisplayPath) {
            ourLocation = theLocation;
            ourDisplayPath = theDisplayPath;
        }

        /** @return absolute filesystem path */
        public Path location() {
            return ourLocation;
        }

        /** @return workspace-relative path stored in payloads */
        public String displayPath() {
            return ourDisplayPath;
        }
    }

    /**
     * One chunk of one file taken out of the store by a search.
     */
    public static final class ModuleHit {

        private final String ourPath;
        private final int ourStartLine;
        private final int ourEndLine;
        private final String ourText;
        private final double ourScore;

        /**
         * @param thePath workspace-relative file path
         * @param theStartLine one-based first chunk line
         * @param theEndLine one-based last chunk line, inclusive
         * @param theText exact chunk source text
         * @param theScore cosine similarity of the chunk to the question
         */
        public ModuleHit(final String thePath, final int theStartLine, final int theEndLine,
            final String theText, final double theScore) {
            ourPath = thePath;
            ourStartLine = theStartLine;
            ourEndLine = theEndLine;
            ourText = theText;
            ourScore = theScore;
        }

        /** @return workspace-relative file path */
        public String path() {
            return ourPath;
        }

        /** @return one-based first line of the chunk */
        public int startLine() {
            return ourStartLine;
        }

        /** @return one-based last line of the chunk, inclusive */
        public int endLine() {
            return ourEndLine;
        }

        /** @return exact source text of the chunk */
        public String text() {
            return ourText;
        }

        /** @return cosine similarity in {@code [0..1]} */
        public double score() {
            return ourScore;
        }
    }

    /**
     * Failure of one index operation: an unreadable file set, a rejected
     * embedding batch or a store error.
     */
    public static final class IndexFailure extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param theMessage failure description
         */
        public IndexFailure(final String theMessage) {
            this(theMessage, null);
        }

        /**
         * @param theMessage failure description
         * @param theCause store, embedding or I/O failure, may be {@code null}
         */
        public IndexFailure(final String theMessage, final Throwable theCause) {
            super(theMessage, theCause);
        }
    }

    /** remembered fingerprint of one indexed file in this session */
    private static final class FileState {

        private final String ourHash;
        private final long ourSize;

        FileState(final String theHash, final long theSize) {
            ourHash = theHash;
            ourSize = theSize;
        }
    }

    private final String collection;
    private final QdrantClient store;
    private final EmbeddingClient embedder;
    private final ModuleChunker chunker = new ModuleChunker(CHUNK_LINES, OVERLAP_LINES);
    private final Map<String, FileState> states = new HashMap<>();

    /**
     * @param theCollection collection holding this workspace's chunks
     * @param theStore vector store client
     * @param theEmbedder embedding client whose model defines the vector size
     */
    public VectorIndex(final String theCollection, final QdrantClient theStore,
        final EmbeddingClient theEmbedder) {
        collection = theCollection;
        store = theStore;
        embedder = theEmbedder;
    }

    /**
     * @return collection name backing this index
     */
    public String getCollection() {
        return collection;
    }

    /**
     * Ensures the collection matches the embedding model: after an existing
     * collection is probed, one sample text is embedded and its vector length
     * must equal the stored vector size, otherwise a later upsert would fail
     * deep inside the batch.
     *
     * @throws IndexFailure when the store rejects the collection or the model
     *             dimensionality does not fit it
     */
    public void ensureReady() throws IndexFailure {
        final int dimension = probeModelDimension();
        try {
            store.ensureCollection(collection, dimension);
        } catch (final QdrantException ex) {
            throw new IndexFailure("vector store refused the collection: " + ex.getMessage(),
                ex);
        }
    }

    /**
     * Embeds one sample text and reports the vector length the model produces.
     */
    private int probeModelDimension() throws IndexFailure {
        final List<float[]> sample;
        try {
            sample = embedder.embed(List.of("dimension probe"));
        } catch (final EmbeddingException ex) {
            throw new IndexFailure("embedding model is unavailable: " + ex.getMessage(), ex);
        }
        if(sample.isEmpty() || sample.get(0).length == 0) {
            throw new IndexFailure("embedding model returned no usable vector");
        }
        return sample.get(0).length;
    }

    /**
     * Brings the collection in line with the given files: unchanged files are
     * skipped without a single store call, changed files have their stale
     * points deleted first and only then the fresh windows are embedded and
     * written, and files that vanished from the list are purged the same way.
     *
     * @param theFiles current source files of the workspace
     * @return counts of re-embedded, skipped and purged files
     * @throws IndexFailure when reading, embedding or storing fails; files
     *             already updated keep their new state
     */
    public UpdateReport update(final List<SourceFile> theFiles) throws IndexFailure {
        int refreshed = 0;
        int skipped = 0;
        final List<String> present = new ArrayList<>(theFiles.size());
        for(final SourceFile file : theFiles) {
            present.add(file.displayPath());
            final byte[] content;
            try {
                content = Files.readAllBytes(file.location());
            } catch (final IOException ex) {
                throw new IndexFailure("cannot read " + file.displayPath(), ex);
            }
            final String hash = sha256(content);
            final FileState known = states.get(file.displayPath());
            if(known != null && known.ourHash.equals(hash)
                && known.ourSize == content.length) {
                skipped++;
                continue;
            }
            reembed(file, content, hash);
            refreshed++;
        }
        int purged = 0;
        for(final String path : new ArrayList<>(states.keySet())) {
            if(!present.contains(path)) {
                purge(path);
                states.remove(path);
                purged++;
            }
        }
        return new UpdateReport(refreshed, skipped, purged);
    }

    /**
     * Result of one {@link #update(List)} pass.
     *
     * @param reembedded files whose chunks were rewritten
     * @param skipped files whose fingerprint did not move
     * @param purged files that vanished and had their points deleted
     */
    public record UpdateReport(int reembedded, int skipped, int purged) {
    }

    /**
     * Deletes the stale points of one file, embeds its windows and writes
     * them, then remembers the new fingerprint. The delete always precedes
     * the write so a failed embedding cannot leave two generations of chunks
     * side by side.
     */
    private void reembed(final SourceFile theFile, final byte[] theContent, final String theHash)
        throws IndexFailure {
        try {
            store.deleteByField(collection, PATH_FIELD, theFile.displayPath());
        } catch (final QdrantException ex) {
            throw new IndexFailure("cannot clear stale chunks of " + theFile.displayPath()
                + ": " + ex.getMessage(), ex);
        }
        final List<ModuleChunker.Chunk> chunks =
            chunker.split(new String(theContent, StandardCharsets.UTF_8));
        if(chunks.isEmpty()) {
            // the file went blank: deleting its points was all it needs
            states.put(theFile.displayPath(), new FileState(theHash, theContent.length));
            return;
        }
        final List<String> texts = new ArrayList<>(chunks.size());
        for(final ModuleChunker.Chunk chunk : chunks) {
            texts.add(chunk.getText());
        }
        final List<float[]> vectors;
        try {
            vectors = embedder.embed(texts);
        } catch (final EmbeddingException ex) {
            throw new IndexFailure("embedding failed for " + theFile.displayPath() + ": "
                + ex.getMessage(), ex);
        }
        if(vectors.size() != chunks.size()) {
            throw new IndexFailure("embedding returned " + vectors.size()
                + " vectors for " + chunks.size() + " chunks of " + theFile.displayPath());
        }
        final long modified = lastModifiedMs(theFile.location());
        final List<Point> points = new ArrayList<>(chunks.size());
        for(int index = 0; index < chunks.size(); index++) {
            final ModuleChunker.Chunk chunk = chunks.get(index);
            final JsonObject payload = new JsonObject();
            payload.addProperty(PATH_FIELD, theFile.displayPath());
            payload.addProperty("startLine", Integer.valueOf(chunk.getStartLine()));
            payload.addProperty("endLine", Integer.valueOf(chunk.getEndLine()));
            payload.addProperty("chunk", Integer.valueOf(index));
            payload.addProperty("modified", Long.valueOf(modified));
            payload.addProperty("hash", theHash);
            payload.addProperty("text", chunk.getText());
            points.add(new Point(QdrantClient.pointId(theFile.displayPath(), index),
                vectors.get(index), payload));
        }
        try {
            store.upsert(collection, points);
        } catch (final QdrantException ex) {
            throw new IndexFailure("cannot store chunks of " + theFile.displayPath() + ": "
                + ex.getMessage(), ex);
        }
        states.put(theFile.displayPath(), new FileState(theHash, theContent.length));
    }

    /**
     * Runs one similarity search over the stored chunks.
     *
     * @param theQuery question text
     * @param theLimit maximum number of chunks
     * @param theMinScore lowest cosine similarity to keep, {@code null} for
     *            the store default
     * @return chunks ordered by decreasing similarity
     * @throws IndexFailure when embedding the query or searching fails
     */
    public List<ModuleHit> searchModules(final String theQuery, final int theLimit,
        final Double theMinScore) throws IndexFailure {
        final float[] vector;
        try {
            final List<float[]> vectors = embedder.embed(List.of(theQuery));
            if(vectors.isEmpty()) {
                throw new IndexFailure("embedding returned no vector for the query", null);
            }
            vector = vectors.get(0);
        } catch (final EmbeddingException ex) {
            throw new IndexFailure("cannot embed the query: " + ex.getMessage(), ex);
        }
        final List<QdrantClient.Hit> found;
        try {
            found = store.search(collection, vector, theLimit, theMinScore);
        } catch (final QdrantException ex) {
            throw new IndexFailure("vector search failed: " + ex.getMessage(), ex);
        }
        final List<ModuleHit> hits = new ArrayList<>(found.size());
        for(final QdrantClient.Hit hit : found) {
            final JsonObject payload = hit.getPayload();
            hits.add(new ModuleHit(text(payload, PATH_FIELD), number(payload, "startLine"),
                number(payload, "endLine"), text(payload, "text"), hit.getScore()));
        }
        return hits;
    }

    /**
     * Removes every chunk of one file and forgets its fingerprint.
     *
     * @param theDisplayPath workspace-relative file path
     * @throws IndexFailure when the store rejects the delete
     */
    public void removeFile(final String theDisplayPath) throws IndexFailure {
        purge(theDisplayPath);
        states.remove(theDisplayPath);
    }

    private void purge(final String theDisplayPath) throws IndexFailure {
        try {
            store.deleteByField(collection, PATH_FIELD, theDisplayPath);
        } catch (final QdrantException ex) {
            throw new IndexFailure("cannot delete chunks of " + theDisplayPath + ": "
                + ex.getMessage(), ex);
        }
    }

    /**
     * Drops the in-memory fingerprints, so the next {@link #update(List)}
     * re-embeds every file even when the content did not move.
     */
    public void forgetFingerprints() {
        states.clear();
    }

    /** @return number of files with a remembered fingerprint */
    public int trackedFileCount() {
        return states.size();
    }

    /** @return fingerprint of one file, {@code null} when untracked */
    String fingerprintOf(final String theDisplayPath) {
        final FileState state = states.get(theDisplayPath);
        return state == null ? null : state.ourHash;
    }

    private static long lastModifiedMs(final Path theLocation) {
        try {
            final FileTime time = Files.getLastModifiedTime(theLocation);
            return time.toMillis();
        } catch (final IOException ex) {
            return -1L;
        }
    }

    private static String sha256(final byte[] theContent) throws IndexFailure {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final StringBuilder hex = new StringBuilder();
            for(final byte part : digest.digest(theContent)) {
                hex.append(String.format("%02x", Integer.valueOf(part & 0xff)));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException ex) {
            throw new IndexFailure("message digest unavailable", ex);
        }
    }

    private static String text(final JsonObject thePayload, final String theName) {
        return thePayload.has(theName) && !thePayload.get(theName).isJsonNull()
            ? thePayload.get(theName).getAsString() : "";
    }

    private static int number(final JsonObject thePayload, final String theName) {
        return thePayload.has(theName) && !thePayload.get(theName).isJsonNull()
            ? thePayload.get(theName).getAsInt() : -1;
    }
}
