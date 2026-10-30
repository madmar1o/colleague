package com.mad.edt.colleague.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mad.edt.colleague.http.HttpCallResult;
import com.mad.edt.colleague.http.HttpCallSpec;
import com.mad.edt.colleague.http.HttpTransport;
import com.mad.edt.colleague.http.TransportFailure;

/**
 * In-memory stand-in for the network: records every call the provider makes
 * and answers with one canned result. A streaming answer tracks whether the
 * provider closed the line stream, which is how a stopped turn releases the
 * connection.
 */
final class RecordingHttpTransport implements HttpTransport {

    private HttpCallResult answer;
    private final List<HttpCallResult> answers = new ArrayList<>();
    private final TransportFailure failure;
    private final List<HttpCallSpec> calls = new ArrayList<>();
    private final AtomicBoolean streamClosed = new AtomicBoolean();

    private RecordingHttpTransport(final HttpCallResult theAnswer,
        final TransportFailure theFailure) {
        answer = theAnswer;
        failure = theFailure;
    }

    /**
     * @param theAnswer canned answer for every call
     * @return a transport answering every call with that buffered result
     */
    static RecordingHttpTransport answering(final HttpCallResult theAnswer) {
        return new RecordingHttpTransport(theAnswer, null);
    }

    /**
     * @param theFailure thrown by every call instead of an answer
     * @return a transport failing every call
     */
    static RecordingHttpTransport failing(final TransportFailure theFailure) {
        return new RecordingHttpTransport(null, theFailure);
    }

    /**
     * @param theStatus status code of the streaming answer
     * @param theLines body lines of the streaming answer
     * @return a transport answering every call with that line stream
     */
    static RecordingHttpTransport streaming(final int theStatus, final String... theLines) {
        final RecordingHttpTransport transport = new RecordingHttpTransport(null, null);
        transport.answer = HttpCallResult.ofLines(theStatus,
            Arrays.stream(theLines).onClose(() -> transport.streamClosed.set(true)));
        return transport;
    }

    /**
     * @param theAnswers answers handed out in order; the last one keeps
     *            answering every further call, so a test that asserts the
     *            call count never runs out of prepared results
     * @return a transport replaying that script
     */
    static RecordingHttpTransport sequence(final HttpCallResult... theAnswers) {
        final RecordingHttpTransport transport = new RecordingHttpTransport(null, null);
        transport.answers.addAll(Arrays.asList(theAnswers));
        return transport;
    }

    @Override
    public HttpCallResult send(final HttpCallSpec theCall) throws TransportFailure {
        calls.add(theCall);
        if(failure != null) {
            throw failure;
        }
        if(!answers.isEmpty()) {
            return answers.size() == 1 ? answers.get(0) : answers.remove(0);
        }
        if(answer == null) {
            throw new IllegalStateException("no canned answer prepared");
        }
        return answer;
    }

    /**
     * @return how many calls the provider made so far
     */
    int getCallCount() {
        return calls.size();
    }

    /**
     * @return every call seen, in order
     */
    List<HttpCallSpec> getCalls() {
        return calls;
    }

    /**
     * @return the single call made so far
     */
    HttpCallSpec getOnlyCall() {
        if(calls.size() != 1) {
            throw new IllegalStateException("expected one call, got " + calls.size());
        }
        return calls.get(0);
    }

    /**
     * @return whether the streaming answer's lines were closed by the provider
     */
    boolean isStreamClosed() {
        return streamClosed.get();
    }
}
