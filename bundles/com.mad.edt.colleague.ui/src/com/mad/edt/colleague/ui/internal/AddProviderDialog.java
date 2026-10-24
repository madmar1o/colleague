package com.mad.edt.colleague.ui.internal;

import java.util.List;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMProviderFactory;
import com.mad.edt.colleague.llm.LLMProviderType;
import com.mad.edt.colleague.llm.ProviderConfig;

/**
 * Modal dialog to register a new LLM provider: protocol, endpoint, optional
 * API key and preferred model, with a connection test before accepting.
 */
public class AddProviderDialog extends TitleAreaDialog {

    private static final String[] TYPE_NAMES = { "Локальный (ollama)", "OpenAI-совместимый" };

    private Combo ourTypeCombo;
    private Text ourIdText;
    private Text ourUrlText;
    private Text ourKeyText;
    private Combo ourModelCombo;

    private ProviderConfig ourResult;

    /**
     * @param theParent parent shell
     */
    public AddProviderDialog(final Shell theParent) {
        super(theParent);
    }

    /**
     * @return the configuration to register after {@link #open()} returned OK,
     *         {@code null} before that
     */
    public ProviderConfig getResult() {
        return ourResult;
    }

    @Override
    protected Control createDialogArea(final Composite theParent) {
        setTitle("Подключить провайдер LLM");
        setMessage("Укажите адрес эндпоинта и нажмите «Проверить».");
        final Composite area = (Composite)super.createDialogArea(theParent);
        final Composite grid = new Composite(area, SWT.NONE);
        grid.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        final GridLayout layout = new GridLayout(2, false);
        grid.setLayout(layout);

        new Label(grid, SWT.NONE).setText("Протокол:");
        ourTypeCombo = new Combo(grid, SWT.READ_ONLY | SWT.DROP_DOWN);
        ourTypeCombo.setItems(TYPE_NAMES);
        ourTypeCombo.select(0);
        ourTypeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(grid, SWT.NONE).setText("Имя (id):");
        ourIdText = new Text(grid, SWT.BORDER);
        ourIdText.setText("provider-1");
        ourIdText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(grid, SWT.NONE).setText("URL эндпоинта:");
        ourUrlText = new Text(grid, SWT.BORDER);
        ourUrlText.setText("http://");
        ourUrlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(grid, SWT.NONE).setText("API-ключ:");
        ourKeyText = new Text(grid, SWT.BORDER | SWT.PASSWORD);
        ourKeyText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(grid, SWT.NONE).setText("Модель по умолчанию:");
        ourModelCombo = new Combo(grid, SWT.READ_ONLY | SWT.DROP_DOWN);
        ourModelCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        final Button testButton = new Button(grid, SWT.PUSH);
        testButton.setText("Проверить соединение");
        testButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        testButton.addListener(SWT.Selection, event -> testConnection());

        return area;
    }

    private void testConnection() {
        try {
            final ILLMProvider probe = LLMProviderFactory.create(buildConfig());
            final List<String> models = probe.listModels();
            ourModelCombo.removeAll();
            int chatModels = 0;
            for(final String model : models) {
                if(!model.contains("embed") && !model.startsWith("bge")) {
                    ourModelCombo.add(model);
                    chatModels++;
                }
            }
            if(chatModels == 1) {
                ourModelCombo.select(0);
            }
            setErrorMessage(null);
            setMessage("Доступно, моделей в списке: " + models.size() + " (для чата: "
                + chatModels + "). Выберите модель в поле «Модель по умолчанию».");
        } catch (final Exception ex) {
            final String reason = ex.getMessage() == null ? ex.getClass().getSimpleName()
                : ex.getMessage();
            setErrorMessage("Недоступно: " + reason);
        }
    }

    private ProviderConfig buildConfig() {
        final LLMProviderType type = ourTypeCombo.getSelectionIndex() == 0
            ? LLMProviderType.OLLAMA : LLMProviderType.OPENAI_COMPATIBLE;
        return ProviderConfig.builder(ourIdText.getText().trim(), type)
            .baseUrl(ourUrlText.getText().trim())
            .apiKey(ourKeyText.getText().trim())
            .defaultModel(ourModelCombo.getText().trim().isEmpty() ? null
                : ourModelCombo.getText().trim())
            .build();
    }

    @Override
    protected void okPressed() {
        if(ourIdText.getText().trim().isEmpty() || ourUrlText.getText().trim().isEmpty()) {
            setErrorMessage("Заполните имя и URL.");
            return;
        }
        ourResult = buildConfig();
        super.okPressed();
    }

    @Override
    protected void configureShell(final Shell theShell) {
        super.configureShell(theShell);
        theShell.setText("Новый провайдер");
    }

    @Override
    protected Point getInitialSize() {
        return new Point(460, 340);
    }
}
