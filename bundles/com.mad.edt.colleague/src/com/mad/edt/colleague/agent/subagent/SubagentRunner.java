package com.mad.edt.colleague.agent.subagent;

import java.util.List;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.mad.edt.colleague.agent.AgentAnswer;
import com.mad.edt.colleague.agent.AgentLoop;
import com.mad.edt.colleague.llm.ChatMessage;
import com.mad.edt.colleague.llm.GeneratorGate;
import com.mad.edt.colleague.llm.LLMException;

/**
 * Runs one delegate turn on its own Eclipse job under the name
 * {@code colleague-subagent-<agent>}, so the workbench sees the delegate as a
 * separate unit of work that can be watched and cancelled, and so the shared
 * generator gate keeps the local model to a single resident model while the
 * delegate runs. The caller waits for the result: a delegate is asked
 * foreground, its answer is the result of the tool call.
 */
public final class SubagentRunner {

    /** Ceiling one delegate turn may take before it is cut off. */
    public static final long TURN_WAIT_MS = 240_000L;

    private SubagentRunner() {
    }

    /**
     * Runs one delegate turn and waits for it.
     *
     * @param theLabel delegate name, becomes the job name
     * @param theLoop loop configured for this delegate
     * @param theConversation conversation the delegate continues from
     * @return the answer of the delegate
     * @throws LLMException when the model endpoint fails or the wait timed out
     */
    public static AgentAnswer runForeground(final String theLabel, final AgentLoop theLoop,
        final List<ChatMessage> theConversation) throws LLMException {
        final AgentAnswer[] outcome = new AgentAnswer[1];
        final LLMException[] failure = new LLMException[1];
        final Job job = Job.create("colleague-subagent-" + theLabel,
            (final org.eclipse.core.runtime.IProgressMonitor theMonitor) -> {
                GeneratorGate.GLOBAL.enter();
                try {
                    outcome[0] = theLoop.run(theConversation);
                    return Status.OK_STATUS;
                } catch (final LLMException ex) {
                    failure[0] = ex;
                    return Status.OK_STATUS;
                } finally {
                    GeneratorGate.GLOBAL.leave();
                }
            });
        job.setSystem(true);
        job.schedule();
        try {
            if(!job.join(TURN_WAIT_MS, null)) {
                job.cancel();
                throw new LLMException("delegate " + theLabel + " did not finish in "
                    + TURN_WAIT_MS + " ms");
            }
        } catch (final InterruptedException ex) {
            job.cancel();
            Thread.currentThread().interrupt();
            throw new LLMException("delegate " + theLabel + " was interrupted", ex);
        }
        if(failure[0] != null) {
            throw failure[0];
        }
        if(outcome[0] == null) {
            throw new LLMException("delegate " + theLabel + " produced no answer");
        }
        return outcome[0];
    }
}
