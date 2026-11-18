package com.mad.edt.colleague.ui.handlers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that the editor actions "Explain selection" and "Generate method"
 * build their prompts from the selected code alone: the read-only guarantee
 * comes from the agent profile the handler opens the chat on, never from a
 * text marker inside the prompt itself.
 */
class EditorActionPromptsTest {

    private static final String READ_ONLY_MARK = "[read-only";

    private ExplainSelectionHandler explainHandler;
    private GenerateMethodHandler generateHandler;

    @BeforeEach
    void setUp() {
        explainHandler = new ExplainSelectionHandler();
        generateHandler = new GenerateMethodHandler();
    }

    // java:S6126 kept suppressed: the selected-code fixture has no trailing
    // newline, a text block would append one and change the byte-exact value.
    @SuppressWarnings("java:S6126")
    @Test
    void explainSelectionBuildsPromptWithSelectedCode() {
        final String selectedCode = "Функция ТолькоЦифры(Строка)\n"
                + "    Возврат Ложь;\n"
                + "КонецФункции";

        final String prompt = explainHandler.buildPrompt(selectedCode);

        assertNotNull(prompt);
        assertTrue(prompt.contains("Объясни"));
        assertTrue(prompt.contains(selectedCode.trim()));
    }

    // java:S6126 kept suppressed: the fixture carries escaped quotes and has no
    // trailing newline; a text block would change the byte-exact value.
    @SuppressWarnings("java:S6126")
    @Test
    void generateMethodBuildsPromptWithSelectedCode() {
        final String selectedCode = "Строка = \"123\";\n"
                + "Если ТолькоЦифры(Строка) Тогда\n"
                + "    Сообщить(\"Только цифры\");\n"
                + "КонецЕсли;";

        final String prompt = generateHandler.buildPrompt(selectedCode);

        assertNotNull(prompt);
        assertTrue(prompt.contains("Сгенерируй"));
        assertTrue(prompt.contains("метод"));
        assertTrue(prompt.contains(selectedCode.trim()));
    }

    @Test
    void promptsCarryNoFakeProfileMarker() {
        // the read-only right comes from the picked agent profile; a marker
        // inside the prompt would only decorate the message without binding
        // the model to anything
        assertFalse(explainHandler.buildPrompt("Возврат 1;").contains(READ_ONLY_MARK));
        assertFalse(generateHandler.buildPrompt("Возврат 1;").contains(READ_ONLY_MARK));
    }

    @Test
    void emptySelectionStillBuildsAnHonestPrompt() {
        assertNotNull(explainHandler.buildPrompt(""));
        assertNotNull(generateHandler.buildPrompt(""));
        assertFalse(explainHandler.buildPrompt("").contains(READ_ONLY_MARK));
        assertFalse(generateHandler.buildPrompt("").contains(READ_ONLY_MARK));
    }
}
