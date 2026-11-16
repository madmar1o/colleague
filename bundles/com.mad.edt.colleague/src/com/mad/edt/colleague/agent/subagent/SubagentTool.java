package com.mad.edt.colleague.agent.subagent;

import com.mad.edt.colleague.ColleagueLog;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mad.edt.colleague.agent.AgentAnswer;
import com.mad.edt.colleague.agent.AgentLoop;
import com.mad.edt.colleague.agent.IToolExecutor;
import com.mad.edt.colleague.agents.AgentProfile;
import com.mad.edt.colleague.agents.ToolPolicy;
import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.CancellationFlag;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMException;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * Hands a bounded task to a nested agent loop that runs on its own Eclipse
 * job with its own step budget and the parent's read-only tools. The nested
 * loop never offers write tools, so a delegate cannot change files.
 */
public final class SubagentTool implements IToolExecutor {

    /** How deep a delegate may delegate again; one level is the ceiling. */
    public static final int MAX_DEPTH = 1;

    /** Round-trips a delegate gets unless its profile asks for another number. */
    public static final int SUBAGENT_MAX_STEPS = 4;

    private static final String TOOL_NAME = "subagent";

    /**
     * Hears about one finished delegate turn so the chat page can show a card
     * for the work a delegate did. The listener runs on the thread that asked
     * for the delegate; page delivery stays the listener's problem.
     */
    public interface DelegateListener {

        /**
         * One delegate turn ended.
         *
         * @param theAgent delegate name the turn ran under
         * @param theDescription what the delegate was asked to do
         * @param theResult final answer of the delegate, or its failure text
         */
        void onDelegateFinished(String theAgent, String theDescription, String theResult);
    }

    private final ILLMProvider provider;
    private final String model;
    private final List<IToolExecutor> parentTools;
    private final List<AgentProfile> profiles;
    private final int depth;
    private final CancellationFlag cancellation;
    private final SubagentRegistry registry;
    private final DelegateListener listener;

    /**
     * @param theProvider model endpoint shared with the parent
     * @param theModel model of the parent, used when the profile asks for none
     * @param theParentTools tools offered to the parent; only its read-only
     *            subset reaches the delegate
     * @param theProfiles agent profiles the delegate may be named after,
     *            {@code null} for none
     * @param theHowDeep nesting level of this tool, {@code 0} for the one the
     *            chat offers; a tool at {@link #MAX_DEPTH} refuses to delegate
     * @param theCancellation stop flag shared with the parent turn
     */
    public SubagentTool(final ILLMProvider theProvider, final String theModel,
        final List<IToolExecutor> theParentTools, final List<AgentProfile> theProfiles,
        final int theHowDeep, final CancellationFlag theCancellation) {
        this(theProvider, theModel, theParentTools, theProfiles, theHowDeep, theCancellation,
            new SubagentRegistry());
    }

    /**
     * @param theProvider model endpoint shared with the parent
     * @param theModel model of the parent
     * @param theParentTools tools offered to the parent
     * @param theProfiles agent profiles the delegate may be named after
     * @param theHowDeep nesting level of this tool
     * @param theCancellation stop flag shared with the parent turn
     * @param theRegistry live delegates shared with the parent chat, so the
     *            same agent name continues its own conversation
     */
    public SubagentTool(final ILLMProvider theProvider, final String theModel,
        final List<IToolExecutor> theParentTools, final List<AgentProfile> theProfiles,
        final int theHowDeep, final CancellationFlag theCancellation,
        final SubagentRegistry theRegistry) {
        this(theProvider, theModel, theParentTools, theProfiles, theHowDeep, theCancellation,
            theRegistry, null);
    }

    /**
     * @param theProvider model endpoint shared with the parent
     * @param theModel model of the parent
     * @param theParentTools tools offered to the parent
     * @param theProfiles agent profiles the delegate may be named after
     * @param theHowDeep nesting level of this tool
     * @param theCancellation stop flag shared with the parent turn
     * @param theRegistry live delegates shared with the parent chat
     * @param theListener hears every finished delegate turn to show it on the
     *            page, {@code null} for silence
     */
    public SubagentTool(final ILLMProvider theProvider, final String theModel,
        final List<IToolExecutor> theParentTools, final List<AgentProfile> theProfiles,
        final int theHowDeep, final CancellationFlag theCancellation,
        final SubagentRegistry theRegistry, final DelegateListener theListener) {
        provider = theProvider;
        model = theModel;
        parentTools = List.copyOf(theParentTools);
        profiles = theProfiles == null ? List.of() : List.copyOf(theProfiles);
        depth = theHowDeep;
        cancellation = theCancellation == null ? new CancellationFlag() : theCancellation;
        registry = theRegistry == null ? new SubagentRegistry() : theRegistry;
        listener = theListener;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolSpec getSpec() {
        final JsonObject properties = new JsonObject();
        properties.add("agent", stringProperty("profile name of the delegate, optional"));
        properties.add("description", stringProperty("what the delegate is asked to do"));
        properties.add("prompt", stringProperty("task text the delegate works on"));
        final JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        final JsonArray required = new JsonArray();
        required.add("prompt");
        schema.add("required", required);
        return new ToolSpec(TOOL_NAME,
            "Hand a bounded research task to a nested read-only agent. The delegate"
                + " searches and reads on its own and returns one final answer; it"
                + " cannot change files and cannot delegate further.", schema);
    }

    @Override
    public String execute(final String theArgumentsJson) {
        final JsonObject arguments = parse(theArgumentsJson);
        return run(textOf(arguments, "agent"), textOf(arguments, "description"),
            textOf(arguments, "prompt"));
    }

    /**
     * Runs one delegate turn.
     *
     * @param theAgent profile name, null for the default profile
     * @param thePrompt task text for the delegate
     * @return final answer of the delegate, or its failure description
     */
    public String run(final String theAgent, final String thePrompt) {
        return run(theAgent, "", thePrompt);
    }

    /**
     * Runs one delegate turn, naming the delegate for the workbench job and
     * for the card the page shows.
     *
     * @param theAgent profile name, {@code null} or empty for the default profile
     * @param theDescription what the delegate is asked to do, shown on the page
     * @param thePrompt task text for the delegate
     * @return final answer of the delegate, or its failure description
     */
    public String run(final String theAgent, final String theDescription,
        final String thePrompt) {
        if(depth >= MAX_DEPTH) {
            return "ERROR: subagent nesting limit reached (depth " + depth
                + "): a delegate cannot delegate again";
        }
        if(thePrompt == null || thePrompt.isBlank()) {
            return "ERROR: the subagent needs a prompt";
        }
        final String name = theAgent == null || theAgent.isBlank()
            ? "delegate" : theAgent.trim();
        final AgentProfile profile = profileNamed(name);
        final String delegateModel = profile != null && profile.getModelName() != null
            && !profile.getModelName().isEmpty() ? profile.getModelName() : model;
        final int steps = profile != null && profile.getMaxSteps() > 0
            ? profile.getMaxSteps() : SUBAGENT_MAX_STEPS;

        final List<IToolExecutor> delegateTools = new ArrayList<>();
        final ToolPolicy policy = new ToolPolicy();
        for(final IToolExecutor tool : parentTools) {
            // the read-only subset of the parent: a writer is refused by name,
            // and anything that mutates other state (the plan board, lesson
            // memory) is not parallel-safe and stays with the parent
            if(tool.isParallelSafe() && !policy.isWriteTool(tool.getName())
                && policy.allows(profile, tool.getName())) {
                delegateTools.add(tool);
            }
        }
        // the nested level still sees the tool so the model gets a clear
        // refusal about the depth instead of an unknown-tool error
        delegateTools.add(new SubagentTool(provider, delegateModel, parentTools, profiles,
            depth + 1, cancellation, registry));

        final SubagentRegistry.Session live = registry.get(name);
        final List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system(systemFor(profile)));
        if(live != null) {
            conversation.addAll(live.conversation());
        }
        conversation.add(ChatMessage.user(thePrompt));

        final AgentLoop loop = new AgentLoop(provider, delegateTools, delegateModel,
            Integer.valueOf(steps), cancellation);
        try {
            final AgentAnswer answer = SubagentRunner.runForeground(name, loop, conversation);
            remember(name, theDescription, delegateModel, conversation, answer.getText());
            announce(name, theDescription, answer.getText());
            return answer.getText();
        } catch (final LLMException ex) {
            final String failure = "ERROR: " + (ex.getMessage() == null
                ? ex.getClass().getSimpleName() : ex.getMessage());
            announce(name, theDescription, failure);
            return failure;
        }
    }

    /**
     * Hands one finished delegate turn to the page listener. A listener that
     * throws only costs the card, never the delegate's answer.
     *
     * @param theAgent delegate name
     * @param theDescription what it was asked to do
     * @param theResult final answer or failure text shown on the card
     */
    private void announce(final String theAgent, final String theDescription,
        final String theResult) {
        if(listener == null) {
            return;
        }
        try {
            listener.onDelegateFinished(theAgent, theDescription == null ? "" : theDescription,
                theResult);
        } catch (final RuntimeException ex) {
            ColleagueLog.warn("[colleague] subagent card listener failed: " + ex);
        }
    }

    /**
     * @param theName delegate name
     * @return profile asking for a sub-agent role or the all-roles default,
     *         {@code null} when the name matches no profile
     */
    private AgentProfile profileNamed(final String theName) {
        for(final AgentProfile profile : profiles) {
            if(profile.getName().equals(theName)
                && profile.getMode() != AgentProfile.Mode.PRIMARY) {
                return profile;
            }
        }
        return null;
    }

    /**
     * @param theProfile profile of the delegate, {@code null} for the default
     * @return instructions of the nested loop
     */
    private String systemFor(final AgentProfile theProfile) {
        final StringBuilder system = new StringBuilder(
            "You are a delegate agent inside one bounded task. Work only with the")
            .append(" read-only tools you are given: search and read, never change anything.")
            .append(" When you have the answer, state it as one final message without")
            .append(" calling another tool. Answer in the language of the task.");
        if(theProfile != null && !theProfile.getSystem().isEmpty()) {
            system.append(" Profile \"").append(theProfile.getName()).append("\": ")
                .append(theProfile.getSystem());
        }
        return system.toString();
    }

    /**
     * Keeps the delegate alive under its name so a later call continues from
     * this conversation.
     *
     * @param theName delegate name
     * @param theDescription what it was asked to do
     * @param theModel model that served it
     * @param theConversation conversation including the task
     * @param theAnswer final answer to remember as the delegate's last word
     */
    private void remember(final String theName, final String theDescription,
        final String theModel, final List<ChatMessage> theConversation,
        final String theAnswer) {
        final List<ChatMessage> kept = new ArrayList<>(theConversation);
        if(theAnswer != null && !theAnswer.isEmpty()) {
            kept.add(ChatMessage.assistant(theAnswer));
        }
        registry.put(theName, SubagentRegistry.Session.of(
            theDescription == null ? "" : theDescription, theModel, kept));
    }

    /**
     * @param theArgumentsJson raw JSON object of the call, may be malformed
     * @return parsed arguments, empty object when the text is not JSON
     */
    private static JsonObject parse(final String theArgumentsJson) {
        if(theArgumentsJson == null || theArgumentsJson.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(theArgumentsJson).getAsJsonObject();
        } catch (final RuntimeException ex) {
            return new JsonObject();
        }
    }

    /**
     * @param theArguments parsed arguments
     * @param theName property name
     * @return text value, empty when absent or not a string
     */
    private static String textOf(final JsonObject theArguments, final String theName) {
        return theArguments.has(theName) && theArguments.get(theName).isJsonPrimitive()
            ? theArguments.get(theName).getAsString() : "";
    }

    /**
     * @param theDescription what the property is for
     * @return JSON-Schema fragment of one string property
     */
    private static JsonObject stringProperty(final String theDescription) {
        final JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", theDescription);
        return property;
    }
}
