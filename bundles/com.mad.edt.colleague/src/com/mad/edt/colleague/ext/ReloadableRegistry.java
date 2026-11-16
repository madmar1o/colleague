package com.mad.edt.colleague.ext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mad.edt.colleague.agent.ToolExecutionException;

/**
 * A name-keyed catalog that can be rebuilt from a source without losing
 * registration order, and can restore the previous snapshot when a rebuild
 * fails. One mechanism behind skills, commands and agent profiles.
 */
public final class ReloadableRegistry<V> {

    /**
     * Builds a fresh catalog snapshot from the registry's current contents.
     * A rebuild that throws leaves the previous snapshot in place, which is
     * how callers keep serving the last good catalog when the reload source
     * breaks half-way.
     *
     * @param <V> value type the catalog holds
     */
    public interface SnapshotBuilder<V> {

        /**
         * @param thePrevious current catalog, never {@code null}, usable as
         *            the starting point of an incremental rebuild
         * @return the complete new catalog
         * @throws ToolExecutionException any load failure: the previous catalog stays live
         */
        Map<String, V> build(Map<String, V> thePrevious) throws ToolExecutionException;
    }

    private Map<String, V> current = new LinkedHashMap<>();

    /**
     * Replaces the whole catalog with a freshly loaded snapshot.
     *
     * @param theSnapshot new contents; on any load failure the caller simply
     *            does not call this method and the previous snapshot stays
     */
    public synchronized void reload(final Map<String, V> theSnapshot) {
        current = theSnapshot == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(theSnapshot);
    }

    /**
     * Rebuilds the catalog through a builder and publishes it only when the
     * builder returns: any exception the builder throws is rethrown wrapped,
     * and until then the live catalog is untouched.
     *
     * @param theBuilder rebuild step seeing the current catalog first
     * @throws IllegalStateException wrapping the builder's own failure; the
     *             previous snapshot stays live in that case
     */
    public synchronized void reload(final SnapshotBuilder<V> theBuilder) {
        final Map<String, V> fresh;
        try {
            fresh = theBuilder.build(Collections.unmodifiableMap(current));
        } catch (final Exception ex) {
            throw new IllegalStateException("registry rebuild failed: " + ex.getMessage(), ex);
        }
        reload(fresh);
    }

    /**
     * @param theName catalog key
     * @return the value registered under the name, {@code null} when absent
     */
    public synchronized V get(final String theName) {
        return current.get(theName);
    }

    /**
     * @return unmodifiable view of the live catalog in registration order
     */
    public synchronized Map<String, V> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(current));
    }
}
