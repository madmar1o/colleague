package com.mad.edt.colleague.agent;

import com.mad.edt.colleague.llm.ToolSpec;

/**
 * A tool the agent loop can execute. Implementations must be safe to call
 * from the loop thread and should bound their own output size.
 */
public interface IToolExecutor {

    /**
     * @return whether one run of this tool only observes state and can run
     *         side by side with other calls of the same round: several reads
     *         and searches then cost their slowest member instead of their
     *         sum. Anything that may write must stay serial ({@code false}).
     */
    default boolean isParallelSafe() {
        return false;
    }

    /**
     * @return tool name, matches the name advertised to the model
     */
    String getName();

    /**
     * @return declaration shown to the model
     */
    ToolSpec getSpec();

    /**
     * Executes the tool.
     *
     * @param theArgumentsJson JSON object with arguments as emitted by the model
     * @return textual result fed back to the model
     * @throws Exception any failure is converted to an error result message
     */
    String execute(String theArgumentsJson) throws Exception;
}
