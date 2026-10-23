package com.mad.edt.colleague.agent;

import com.mad.edt.colleague.llm.ToolSpec;

/**
 * A tool the agent loop can execute. Implementations must be safe to call
 * from the loop thread and should bound their own output size.
 */
public interface IToolExecutor {

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
