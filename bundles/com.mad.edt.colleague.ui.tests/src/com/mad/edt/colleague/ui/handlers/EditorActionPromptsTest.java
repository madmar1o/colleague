package com.mad.edt.colleague.ui.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mad.edt.colleague.ui.view.ColleagueView;

/**
 * Tests that editor actions "Explain selection" and "Generate method" build
 * prompts with the selected code and attach a read-only tool profile so the
 * model cannot write files.
 */
class EditorActionPromptsTest {

    private ExplainSelectionHandler explainHandler;
    private GenerateMethodHandler generateHandler;

    @BeforeEach
    void setUp() {
        explainHandler = new ExplainSelectionHandler();
        generateHandler = new GenerateMethodHandler();
    }

    @Test
    void explainSelectionBuildsPromptWithSelectedCode() {
        final String selectedCode = "Функция ТолькоЦифры(Строка)\n"
                + "    Возврат Ложь;\n"
                + "КонецФункции";

        final String prompt = explainHandler.buildPrompt(selectedCode);

        assertNotNull(prompt);
        assertTrue(prompt.contains("Объясни"));
        assertTrue(prompt.contains(selectedCode));
        assertTrue(prompt.contains("read-only") || prompt.contains("read only"));
    }

    @Test
    void generateMethodBuildsPromptWithSelectedCode() {
        final String selectedCode = "Строка = \"123\";\n"
                + "Если ТолькоЦифры(Строка) Тогда\n"
                + "    Сообщить(\"Только цифры\");\n"
                + "КонецЕсли;";

        final String prompt = generateHandler.buildPrompt(selectedCode);

        assertNotNull(prompt);
        assertTrue(prompt.contains("Сгенерируй") || prompt.contains("Generate"));
        assertTrue(prompt.contains("метод") || prompt.contains("method"));
        assertTrue(prompt.contains(selectedCode));
        assertTrue(prompt.contains("read-only") || prompt.contains("read only"));
    }

    @Test
    void explainSelectionHandlerDefaultPrompt() {
        final String prompt = explainHandler.buildPrompt("");

        assertNotNull(prompt);
        assertTrue(prompt.contains("Объясни"));
    }

    @Test
    void generateMethodHandlerDefaultPrompt() {
        final String prompt = generateHandler.buildPrompt("");

        assertNotNull(prompt);
        assertTrue(prompt.contains("Сгенерируй") || prompt.contains("Generate"));
    }
}