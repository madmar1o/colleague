package com.mad.edt.colleague.agents;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.mad.edt.colleague.llm.ToolSpec;

/**
 * A profile decides which tools the model is offered. Because the filter works
 * on the list the workspace currently offers, a profile can only narrow that
 * set: read-only tools need no special permission, write tools appear only when
 * the edit mode already offered them, and a profile naming a tool nobody
 * offered cannot smuggle it in.
 */
class ProfileToolPolicyTest {

    // the tool ids the workspace really offers: same names the production
    // write-set and the profile headers speak
    private static final String SEARCH_CODE = "search_code";
    private static final String READ_FILE = "read_file";
    private static final String WRITE_FILE = "write_file";
    private static final String EDIT_FILE = "edit_file";

    private static ToolSpec spec(final String theName) {
        return new ToolSpec(theName, "tool " + theName, new JsonObject());
    }

    private static List<String> names(final List<ToolSpec> theSpecs) {
        return theSpecs.stream().map(ToolSpec::getName).toList();
    }

    private static AgentProfile profileWithTools(final String... theNames) {
        return AgentProfile.builder("reviewer").description("d").system("s")
            .maxSteps(0).mode(AgentProfile.Mode.ALL).tools(Set.of(theNames)).build();
    }

    @Test
    @DisplayName("a read-only profile never sees write_file in the advertised tools")
    void readOnlyProfileHidesWriteTools() {
        final List<ToolSpec> offered = Arrays.asList(
            spec(SEARCH_CODE), spec(READ_FILE), spec(WRITE_FILE), spec(EDIT_FILE));

        final List<ToolSpec> allowed = new ToolPolicy().allowed(
            profileWithTools(SEARCH_CODE, READ_FILE), offered);

        Assertions.assertEquals(List.of(SEARCH_CODE, READ_FILE), names(allowed));
        Assertions.assertFalse(names(allowed).contains(WRITE_FILE),
            "the model must not be offered a writer it has no business calling");
    }

    @Test
    @DisplayName("a profile naming a tool nobody offered adds nothing")
    void profileNeverWidensTheOfferedSet() {
        // the edit mode is off, so the workspace offered readers only
        final List<ToolSpec> offered = List.of(spec(SEARCH_CODE), spec(READ_FILE));

        final List<ToolSpec> allowed = new ToolPolicy().allowed(
            profileWithTools(READ_FILE, WRITE_FILE), offered);

        Assertions.assertEquals(List.of(READ_FILE), names(allowed));
    }

    @Test
    @DisplayName("no profile and an empty tool list keep the offered set as it is")
    void unsetProfileKeepsEverythingInOrder() {
        final List<ToolSpec> offered = Arrays.asList(
            spec(READ_FILE), spec(SEARCH_CODE), spec(EDIT_FILE));
        final ToolPolicy policy = new ToolPolicy();

        Assertions.assertEquals(List.of(READ_FILE, SEARCH_CODE, EDIT_FILE),
            names(policy.allowed(null, offered)));
        Assertions.assertEquals(List.of(READ_FILE, SEARCH_CODE, EDIT_FILE),
            names(policy.allowed(profileWithTools(), offered)));
    }

    @Test
    @DisplayName("the write set is the one the workspace actually uses")
    void writeToolsAreRecognised() {
        final ToolPolicy policy = new ToolPolicy();

        Assertions.assertTrue(policy.isWriteTool(WRITE_FILE));
        Assertions.assertTrue(policy.isWriteTool(EDIT_FILE));
        Assertions.assertFalse(policy.isWriteTool(READ_FILE));
        Assertions.assertFalse(policy.isWriteTool(SEARCH_CODE));
    }
}
