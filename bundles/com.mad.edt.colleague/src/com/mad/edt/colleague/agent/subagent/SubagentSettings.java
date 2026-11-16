package com.mad.edt.colleague.agent.subagent;

import java.util.List;

import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.agents.AgentProfile;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ILLMProvider;

/**
 * One bundle of everything a {@link SubagentTool} runs on: the model endpoint
 * it shares with the parent, the parent's tool list it filters down to a
 * read-only subset, the profiles a delegate may be named after, the nesting
 * level, and the optional stop flag, live registry and finished-turn listener.
 * Built piece by piece so the optional facets stay named at the call site.
 */
public final class SubagentSettings {

    private final ILLMProvider provider;
    private final String model;
    private final List<IToolExecutor> parentTools;
    private final List<AgentProfile> profiles;
    private final int depth;
    private CancellationFlag cancellation;
    private SubagentRegistry registry;
    private SubagentTool.DelegateListener listener;

    /**
     * @param theProvider model endpoint shared with the parent
     * @param theModel model of the parent, used when the profile asks for none
     * @param theParentTools tools offered to the parent; only its read-only
     *            subset reaches the delegate
     * @param theProfiles agent profiles the delegate may be named after,
     *            {@code null} for none
     * @param theDepth nesting level of this tool, {@code 0} for the one the
     *            chat offers; a tool at {@link SubagentTool#MAX_DEPTH} refuses
     *            to delegate
     * @return a settings object with no stop flag, no shared registry and no
     *         listener; chain the {@code with...} calls to add them
     */
    public static SubagentSettings of(final ILLMProvider theProvider, final String theModel,
        final List<IToolExecutor> theParentTools, final List<AgentProfile> theProfiles,
        final int theDepth) {
        return new SubagentSettings(theProvider, theModel, theParentTools, theProfiles,
            theDepth);
    }

    private SubagentSettings(final ILLMProvider theProvider, final String theModel,
        final List<IToolExecutor> theParentTools, final List<AgentProfile> theProfiles,
        final int theDepth) {
        provider = theProvider;
        model = theModel;
        parentTools = theParentTools;
        profiles = theProfiles;
        depth = theDepth;
    }

    /**
     * @param theCancellation stop flag shared with the parent turn
     * @return a copy of these settings carrying the stop flag
     */
    public SubagentSettings withCancellation(final CancellationFlag theCancellation) {
        return copy(theCancellation, registry, listener);
    }

    /**
     * @param theRegistry live delegates shared with the parent chat, so the
     *            same agent name continues its own conversation
     * @return a copy of these settings carrying the shared registry
     */
    public SubagentSettings withRegistry(final SubagentRegistry theRegistry) {
        return copy(cancellation, theRegistry, listener);
    }

    /**
     * @param theListener hears every finished delegate turn to show it on the
     *            page, {@code null} for silence
     * @return a copy of these settings carrying the listener
     */
    public SubagentSettings withListener(final SubagentTool.DelegateListener theListener) {
        return copy(cancellation, registry, theListener);
    }

    private SubagentSettings copy(final CancellationFlag theCancellation,
        final SubagentRegistry theRegistry, final SubagentTool.DelegateListener theListener) {
        final SubagentSettings copy = new SubagentSettings(provider, model, parentTools,
            profiles, depth);
        copy.cancellation = theCancellation;
        copy.registry = theRegistry;
        copy.listener = theListener;
        return copy;
    }

    /** @return model endpoint shared with the parent */
    public ILLMProvider provider() {
        return provider;
    }

    /** @return model of the parent, used when the profile asks for none */
    public String model() {
        return model;
    }

    /** @return tools offered to the parent */
    public List<IToolExecutor> parentTools() {
        return parentTools;
    }

    /** @return profiles a delegate may be named after, {@code null} for none */
    public List<AgentProfile> profiles() {
        return profiles;
    }

    /** @return nesting level of the tool these settings build */
    public int depth() {
        return depth;
    }

    /** @return stop flag shared with the parent turn, {@code null} for a fresh one */
    public CancellationFlag cancellation() {
        return cancellation;
    }

    /** @return live delegates shared with the parent chat, {@code null} for a fresh one */
    public SubagentRegistry registry() {
        return registry;
    }

    /** @return listener for finished delegate turns, {@code null} for silence */
    public SubagentTool.DelegateListener listener() {
        return listener;
    }
}
