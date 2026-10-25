package com.mad.edt.colleague.context;

/**
 * Supplier of workspace context text for a model question. Implementations
 * range from plain streaming keyword search to hybrid keyword-plus-semantic
 * lookups; callers never depend on which one produced the text.
 */
public interface ContextSource {

    /**
     * Collects context for one question.
     *
     * @param theQuestion question text typed by the user
     * @return context blocks for the model, empty string when nothing matched
     */
    String gather(String theQuestion);
}
