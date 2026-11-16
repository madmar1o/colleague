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
            spec("search_code"), spec("read_file"), spec("write_file"), spec("edit_file"));

        final List<ToolSpec> allowed = new ToolPolicy().allowed(
            profileWithTools("search_code", "read_file"), offered);

        Assertions.assertEquals(List.of("search_code", "read_file"), names(allowed));
        Assertions.assertFalse(names(allowed).contains("write_file"),
            "the model must not be offered a writer it has no business calling");
    }

    @Test
    @DisplayName("a profile naming a tool nobody offered adds nothing")
    void profileNeverWidensTheOfferedSet() {
        // the edit mode is off, so the workspace offered readers only
        final List<ToolSpec> offered = List.of(spec("search_code"), spec("read_file"));

        final List<ToolSpec> allowed = new ToolPolicy().allowed(
            profileWithTools("read_file", "write_file"), offered);

        Assertions.assertEquals(List.of("read_file"), names(allowed));
    }

    @Test
    @DisplayName("no profile and an empty tool list keep the offered set as it is")
    void unsetProfileKeepsEverythingInOrder() {
        final List<ToolSpec> offered = Arrays.asList(
            spec("read_file"), spec("search_code"), spec("edit_file"));
        final ToolPolicy policy = new ToolPolicy();

        Assertions.assertEquals(List.of("read_file", "search_code", "edit_file"),
            names(policy.allowed(null, offered)));
        Assertions.assertEquals(List.of("read_file", "search_code", "edit_file"),
            names(policy.allowed(profileWithTools(), offered)));
    }

    @Test
    @DisplayName("the write set is the one the workspace actually uses")
    void writeToolsAreRecognised() {
        final ToolPolicy policy = new ToolPolicy();

        Assertions.assertTrue(policy.isWriteTool("write_file"));
        Assertions.assertTrue(policy.isWriteTool("edit_file"));
        Assertions.assertFalse(policy.isWriteTool("read_file"));
        Assertions.assertFalse(policy.isWriteTool("search_code"));
    }
}
