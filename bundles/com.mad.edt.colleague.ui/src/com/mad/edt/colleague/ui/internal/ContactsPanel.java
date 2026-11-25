package com.mad.edt.colleague.ui.internal;

import com.mad.edt.colleague.ColleagueLog;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMProviderFactory;
import com.mad.edt.colleague.llm.LLMProviderType;
import com.mad.edt.colleague.llm.LLMSettings;
import com.mad.edt.colleague.llm.ProviderConfig;
import com.mad.edt.colleague.llm.ProviderStore;
import com.mad.edt.colleague.mcp.McpClient;
import com.mad.edt.colleague.mcp.McpServerConfig;
import com.mad.edt.colleague.mcp.McpSettings;
import com.mad.edt.colleague.mcp.McpToolInfo;

/**
 * Provider and model settings page rendered by the bundled
 * {@code web/contacts.html} page inside an embedded browser: one card per
 * configured provider with its chat-capable models listed, the gear opening a
 * popup with the provider list: pick which provider new dialogs use, change
 * or remove one in place, and add a new one through the button under the
 * list. Since the agents page became the way into a chat, this page no longer
 * opens chats from the browser: model chips are read-only information here.
 * The plain SWT fallback used without a browser backend still selects a
 * model on the tree. When no browser backend is available the panel falls
 * back to a plain SWT tree plus a refresh button.
 */
public final class ContactsPanel {

    /** Contact key separator and the bridge payload field names. */
    private static final String ID_SEPARATOR = "\u0000";
    private static final String ERROR = "error";
    private static final String COMMAND = "command";
    private static final String ENABLED = "enabled";

    /** A selectable chat contact: provider, model and the agent speaking them. */
    public static final class Contact {

        private final String ourProviderId;
        private final String ourModel;
        private final String ourAgentName;

        public Contact(final String theProviderId, final String theModel) {
            this(theProviderId, theModel, "");
        }

        /**
         * @param theProviderId provider configuration id
         * @param theModel model name as reported by the provider
         * @param theAgentName profile the chat runs under, empty for the
         *            built-in default colleague
         */
        public Contact(final String theProviderId, final String theModel,
            final String theAgentName) {
            ourProviderId = theProviderId;
            ourModel = theModel;
            ourAgentName = theAgentName == null ? "" : theAgentName;
        }

        /**
         * @return provider configuration id
         */
        public String getProviderId() {
            return ourProviderId;
        }

        /**
         * @return model name as reported by the provider
         */
        public String getModel() {
            return ourModel;
        }

        /**
         * @return profile name the chat runs under, empty for the built-in
         *         default colleague
         */
        public String getAgentName() {
            return ourAgentName;
        }

        /**
         * @return what the tab shows: the agent name when the contact is an
         *         agent, the model otherwise
         */
        public String getDisplayName() {
            return ourAgentName.isEmpty() ? ourModel : ourAgentName;
        }

        /**
         * @return stable key for per-contact session reuse; agents keep their
         *         own conversation even when two of them share one model
         */
        public String getKey() {
            return ourProviderId + ID_SEPARATOR + ourModel
                + (ourAgentName.isEmpty() ? "" : ID_SEPARATOR + ourAgentName);
        }
    }

    private final Consumer<Contact> ourOnContactSelected;
    private final Consumer<List<Contact>> ourOnLoaded;
    private final Runnable ourOnProvidersChanged;
    private final Runnable ourOnGoBack;

    private Composite ourRoot;
    private BridgeBrowser ourBridge;
    private Color ourPageBackground;

    private Tree ourTree;
    private boolean ourLoading;

    /**
     * @param theParent parent composite (caller provides the layout)
     * @param theOnContactSelected invoked on the UI thread for model selection
     * @param theOnLoaded invoked on the UI thread after each reload with the
     *            contacts that answered
     * @param theOnProvidersChanged invoked on the UI thread after the saved
     *            provider list moved (saved, corrected or removed), so the
     *            view can retire conversations built on the old configuration
     * @param theOnGoBack invoked on the UI thread when the page asks to leave
     *            the providers list, the way back to the agents page
     */
    public ContactsPanel(final Composite theParent, final Consumer<Contact> theOnContactSelected,
        final Consumer<List<Contact>> theOnLoaded, final Runnable theOnProvidersChanged,
        final Runnable theOnGoBack) {
        ourOnContactSelected = theOnContactSelected;
        ourOnLoaded = theOnLoaded;
        ourOnProvidersChanged = theOnProvidersChanged;
        ourOnGoBack = theOnGoBack;
        createControls(theParent);
    }

    /**
     * @return the top-level control of the panel
     */
    public Control getControl() {
        return ourRoot;
    }

    /** Reloads provider model lists in the background. */
    public void reload() {
        if(ourLoading || ourRoot == null || ourRoot.isDisposed()) {
            return;
        }
        ourLoading = true;
        Job.create("Colleague: contacts", monitor -> {
            final List<Object[]> providers = probeProviders();
            Display.getDefault().asyncExec(() -> {
                if(ourRoot == null || ourRoot.isDisposed()) {
                    return;
                }
                ourLoading = false;
                if(ourBridge != null) {
                    pushContacts(providers);
                } else {
                    fillTree(providers);
                }
            });
            return Status.OK_STATUS;
        }).schedule();
    }

    /** Releases the SWT controls. */
    public void dispose() {
        if(ourBridge != null && !ourBridge.getControl().isDisposed()) {
            ourBridge.getControl().dispose();
        }
        if(ourTree != null && !ourTree.isDisposed()) {
            ourTree.dispose();
        }
        if(ourRoot != null && !ourRoot.isDisposed()) {
            ourRoot.dispose();
        }
        if(ourPageBackground != null && !ourPageBackground.isDisposed()) {
            ourPageBackground.dispose();
        }
    }

    /**
     * Queries every registered provider for its chat-capable models.
     *
     * @return one entry per provider: {@code [ok, id, baseUrlOrError, models]}
     */
    private static List<Object[]> probeProviders() {
        final List<Object[]> providers = new ArrayList<>();
        for(final String id : LLMSettings.getProviderIds()) {
            try {
                final ILLMProvider provider =
                    LLMProviderFactory.create(LLMSettings.getConfig(id));
                final List<String> models = provider.listModels().stream()
                    .filter(provider::supportsChatCapability)
                    .toList();
                providers.add(new Object[] {Boolean.TRUE, id,
                    provider.getConfig().getBaseUrl(), models});
            } catch (final Exception ex) {
                final String reason = ex.getMessage() == null ? ex.getClass().getSimpleName()
                    : ex.getMessage();
                providers.add(new Object[] {Boolean.FALSE, id, reason, List.of()});
            }
        }
        return providers;
    }

    private void createControls(final Composite theParent) {
        ourRoot = new Composite(theParent, SWT.NONE);
        ourRoot.setLayout(new GridLayout(1, false));
        createBrowser();
        if(ourBridge == null) {
            createFallback();
        }
    }

    /**
     * Tries the embedded browser with the bundled contacts page.
     *
     * @return {@code true} when the browser path is active
     */
    private void createBrowser() {
        try {
            ViewBridge.ensureUserDataDir();
            ourBridge = new BridgeBrowser(ourRoot, "web/contacts.html", this::handleBridge);
            final JsonObject ready = ThemeSupport.pagePalette(ourRoot);
            applyNativePalette(ready);
            ourBridge.push("ready", ready);
            watchTheme();
            return;
        } catch (final RuntimeException | LinkageError | org.eclipse.swt.SWTError ex) {
            ColleagueLog.error("colleague: contacts browser unavailable, plain fallback: "
                + ex.getMessage());
            ourBridge = null;
        }
        if(ourRoot.getChildren().length != 1) {
            for(final Control child : ourRoot.getChildren()) {
                child.dispose();
            }
        }
    }

    /** Paints the panel container with the page background color. */
    private void applyNativePalette(final JsonObject thePalette) {
        if(ourRoot.isDisposed()) {
            return;
        }
        final Color fresh = ThemeSupport.pageColor(ourRoot.getDisplay(), thePalette, "bg");
        if(ourPageBackground != null && !ourPageBackground.isDisposed()) {
            ourPageBackground.dispose();
        }
        ourPageBackground = fresh;
        ourRoot.setBackground(fresh);
    }

    private void handleBridge(final String theMethod, final JsonObject theArguments) {
        switch(theMethod) {
            case "bridgeReady", "refresh":
                reload();
                return;
            case "openContact": {
                // a model chip on the providers page: tapping it opens a chat on
                // that provider and model, the same entry the SWT fallback tree
                // gives with its selection
                final Contact picked = contactFromArgs(theArguments);
                if(picked != null) {
                    ourOnContactSelected.accept(picked);
                }
                return;
            }
            case "goBack":
                ourOnGoBack.run();
                return;
            case "loadSettings":
                pushSettings();
                return;
            case "setDefault":
                LLMSettings.setDefaultProviderId(BridgeBrowser.optString(theArguments, "id"));
                ProviderStore.saveFromSettings();
                reload();
                return;
            case "removeProvider":
                removeProvider(BridgeBrowser.optString(theArguments, "id"));
                return;
            case "testProvider":
                testProvider(theArguments);
                return;
            case "saveProvider":
                saveProvider(theArguments);
                return;
            case "listMcp":
                pushMcpServers();
                return;
            case "saveMcp":
                saveMcp(theArguments);
                return;
            case "removeMcp":
                removeMcp(BridgeBrowser.optString(theArguments, "name"));
                return;
            case "testMcp":
                testMcp(theArguments);
                return;
            default:
                return;
        }
    }

    /**
     * Reads a model-chip tap of the providers page into a contact. Both halves
     * of the pair must be named: a chat on an empty provider or model name
     * would only fail at the endpoint and leave a tab named after nothing.
     *
     * @param theArguments page call arguments, the provider under {@code id}
     *            and the model under {@code model}
     * @return the contact to open, {@code null} when the call is incomplete
     */
    static Contact contactFromArgs(final JsonObject theArguments) {
        final String providerId = BridgeBrowser.optString(theArguments, "id");
        final String model = BridgeBrowser.optString(theArguments, "model");
        if(providerId.isEmpty() || model.isEmpty()) {
            return null;
        }
        return new Contact(providerId, model, "");
    }

    private void removeProvider(final String theId) {
        if(theId.isEmpty() || LLMSettings.LOCAL_PROVIDER_ID.equals(theId)) {
            pushSaved(false, "встроенный локальный провайдер удалить нельзя");
            return;
        }
        LLMSettings.unregister(theId);
        if(theId.equals(LLMSettings.getDefaultProviderId())) {
            LLMSettings.setDefaultProviderId(LLMSettings.LOCAL_PROVIDER_ID);
        }
        ProviderStore.saveFromSettings();
        ourOnProvidersChanged.run();
        pushSaved(true, "");
        reload();
    }

    /**
     * Probes an unsaved endpoint draft for its chat model list without
     * registering anything: the page shows the answer right away and offers
     * the models for the default-model pick.
     *
     * @param theArguments draft fields: type, url, key
     */
    private void testProvider(final JsonObject theArguments) {
        final String type = BridgeBrowser.optString(theArguments, "type");
        final String url = BridgeBrowser.optString(theArguments, "url");
        if(url.isEmpty()) {
            final JsonObject result = new JsonObject();
            result.addProperty("ok", Boolean.FALSE);
            result.addProperty(ERROR, "не задан адрес сервиса");
            ourBridge.push("testResult", result);
            return;
        }
        Job.create("Colleague: provider test", monitor -> {
            final JsonObject result = new JsonObject();
            try {
                final ProviderConfig config = ProviderConfig
                    .builder("_probe", toType(type))
                    .baseUrl(url)
                    .apiKey(BridgeBrowser.optString(theArguments, "key"))
                    .build();
                final ILLMProvider probed = LLMProviderFactory.create(config);
                final List<String> models = probed.listModels()
                    .stream()
                    .filter(name -> probed.supportsChatCapability(name))
                    .collect(Collectors.toList());
                final JsonArray array = new JsonArray();
                models.forEach(array::add);
                result.add("models", array);
                result.addProperty("ok", Boolean.TRUE);
            } catch (final Exception ex) {
                result.addProperty("ok", Boolean.FALSE);
                result.addProperty(ERROR, ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage());
            }
            Display.getDefault().asyncExec(() -> {
                if(ourBridge != null && !ourBridge.getControl().isDisposed()) {
                    ourBridge.push("testResult", result);
                }
            });
            return Status.OK_STATUS;
        }).schedule();
    }

    /**
     * Registers (or updates) one provider from the page form. An empty key
     * field keeps the key an existing provider already has: the page never
     * reads stored secrets back.
     *
     * @param theArguments form fields: id, type, url, key, model, isDefault
     */
    private void saveProvider(final JsonObject theArguments) {
        final String id = BridgeBrowser.optString(theArguments, "id");
        final String url = BridgeBrowser.optString(theArguments, "url");
        if(id.isEmpty() || url.isEmpty()) {
            pushSaved(false, "нужны идентификатор и адрес сервиса");
            return;
        }
        if(id.contains(ID_SEPARATOR) || id.contains("/")) {
            pushSaved(false, "некорректный идентификатор");
            return;
        }
        String key = BridgeBrowser.optString(theArguments, "key");
        final ProviderConfig previous = LLMSettings.getConfig(id);
        if(key.isEmpty() && previous != null && previous.getApiKey() != null) {
            key = previous.getApiKey();
        }
        final String model = BridgeBrowser.optString(theArguments, "model");
        final int window;
        try {
            window = windowFromArgs(theArguments);
        } catch (final IllegalArgumentException ex) {
            pushSaved(false, "окно контекста — целое число токенов от "
                + ProviderConfig.MIN_USER_CONTEXT_TOKENS + " до "
                + ProviderConfig.MAX_USER_CONTEXT_TOKENS + " (пусто — по умолчанию)");
            return;
        }
        final ProviderConfig.Builder builder = ProviderConfig
            .builder(id, toType(BridgeBrowser.optString(theArguments, "type")))
            .baseUrl(url)
            .apiKey(key)
            .defaultModel(model.isEmpty() ? null : model);
        if(window > 0) {
            builder.contextWindowTokens(window);
        }
        LLMSettings.register(builder.build());
        if(theArguments.has("isDefault") && theArguments.get("isDefault").getAsBoolean()) {
            LLMSettings.setDefaultProviderId(id);
        }
        ProviderStore.saveFromSettings();
        ourOnProvidersChanged.run();
        pushSaved(true, "");
        reload();
    }

    /**
     * Reads the context-window field of the provider form. The page keeps
     * the field as free text so an invalid number can be shown back to the
     * user instead of silently vanishing into a zero.
     *
     * @param theArguments form fields; {@code window} may be absent or blank
     * @return tokens to configure, {@code 0} for "keep the type default"
     * @throws IllegalArgumentException when the text is not a whole number
     *             inside the range the core accepts
     */
    static int windowFromArgs(final JsonObject theArguments) {
        return ProviderConfig.parseUserContextWindowTokens(
            BridgeBrowser.optString(theArguments, "window"));
    }

    /**
     * @param thePageValue protocol as the page names it
     * @return the matching endpoint dialect
     */
    private static LLMProviderType toType(final String thePageValue) {
        return "openai".equals(thePageValue)
            ? LLMProviderType.OPENAI_COMPATIBLE : LLMProviderType.OLLAMA;
    }

    /**
     * Registers (or replaces) one MCP server from the page form. The name is
     * the stable key of the {@code mcp_<server>_<tool>} prefix, so it has to
     * be plain lower-case letters, digits and underscores.
     *
     * @param theArguments form fields: name, command, url, enabled
     */
    private void saveMcp(final JsonObject theArguments) {
        final String name = BridgeBrowser.optString(theArguments, "name");
        final String command = BridgeBrowser.optString(theArguments, COMMAND);
        final String url = BridgeBrowser.optString(theArguments, "url");
        if(!name.matches("[a-z0-9_]{1,40}")) {
            pushSaved(false, "имя сервера — только строчные латинские буквы,"
                + " цифры и подчёркивание");
            return;
        }
        if(url.isEmpty() && command.trim().isEmpty()) {
            pushSaved(false, "нужен адрес HTTP-сервера или команда запуска процесса");
            return;
        }
        if(!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            pushSaved(false, "адрес должен начинаться с http:// или https://");
            return;
        }
        final List<String> parts = new ArrayList<>();
        for(final String part : command.trim().split("\\s+")) {
            if(!part.isEmpty()) {
                parts.add(part);
            }
        }
        final boolean enabled = !theArguments.has(ENABLED)
            || theArguments.get(ENABLED).getAsBoolean();
        McpSettings.upsert(new McpServerConfig(name,
            url.isEmpty() ? parts : null, url.isEmpty() ? null : url, enabled));
        pushSaved(true, "");
        pushMcpServers();
    }

    /**
     * @param theName stored MCP server to drop from the settings
     */
    private void removeMcp(final String theName) {
        if(theName.isEmpty()) {
            return;
        }
        McpSettings.remove(theName);
        pushSaved(true, "");
        pushMcpServers();
    }

    /**
     * Probes an unsaved MCP draft by running the handshake and listing its
     * tools, without storing anything: the page shows the tool names right
     * away, exactly like the provider test shows the model list.
     *
     * @param theArguments draft fields: name, command, url
     */
    private void testMcp(final JsonObject theArguments) {
        final McpServerConfig probe = buildProbeConfig(theArguments);
        Job.create("Colleague: MCP server test", monitor -> {
            pushProbeResult(probeResult(probe));
            return Status.OK_STATUS;
        }).schedule();
    }

    /**
     * Turns the draft fields of the settings form into a throwaway server
     * configuration for the probe: an empty name becomes "probe", a url wins
     * over the command line, and the command is split into executable plus
     * arguments.
     *
     * @param theArguments draft fields: name, command, url
     * @return configuration the probe will connect with
     */
    private static McpServerConfig buildProbeConfig(final JsonObject theArguments) {
        final String name = BridgeBrowser.optString(theArguments, "name");
        final String command = BridgeBrowser.optString(theArguments, COMMAND);
        final String url = BridgeBrowser.optString(theArguments, "url");
        final List<String> parts = new ArrayList<>();
        for(final String part : command.trim().split("\\s+")) {
            if(!part.isEmpty()) {
                parts.add(part);
            }
        }
        return new McpServerConfig(
            name.isEmpty() ? "probe" : name,
            url.isEmpty() ? parts : null, url.isEmpty() ? null : url, true);
    }

    /**
     * Runs the handshake against the probe configuration and lists its tools
     * off the UI thread.
     *
     * @param theProbe server configuration to connect to
     * @return page payload: ok plus tools, or ok=false plus error
     */
    private static JsonObject probeResult(final McpServerConfig theProbe) {
        final JsonObject result = new JsonObject();
        try (McpClient client = new McpClient(theProbe)) {
            final JsonArray tools = new JsonArray();
            for(final McpToolInfo tool : client.discover()) {
                final JsonObject item = new JsonObject();
                item.addProperty("name", tool.name());
                item.addProperty("readOnly", Boolean.valueOf(tool.readOnly()));
                tools.add(item);
            }
            result.add("tools", tools);
            result.addProperty("ok", Boolean.TRUE);
        } catch (final Exception ex) {
            result.addProperty("ok", Boolean.FALSE);
            result.addProperty(ERROR, ex.getMessage() == null
                ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        return result;
    }

    /**
     * Hands the probe result back to the settings page.
     *
     * @param theResult payload produced by the probe
     */
    private void pushProbeResult(final JsonObject theResult) {
        Display.getDefault().asyncExec(() -> {
            if(ourBridge != null && !ourBridge.getControl().isDisposed()) {
                ourBridge.push("mcpTestResult", theResult);
            }
        });
    }

    /** Pushes the stored MCP servers so the settings table can redraw itself. */
    private void pushMcpServers() {
        final JsonObject payload = new JsonObject();
        payload.add("servers", mcpJson());
        ourBridge.push("mcpData", payload);
    }

    /**
     * @return the stored MCP servers in the shape the page renders
     */
    private static JsonArray mcpJson() {
        final JsonArray servers = new JsonArray();
        for(final McpServerConfig server : McpSettings.loadServers()) {
            final JsonObject item = new JsonObject();
            item.addProperty("name", server.getName());
            item.addProperty(ENABLED, Boolean.valueOf(server.isEnabled()));
            item.addProperty("url", server.getUrl() == null ? "" : server.getUrl());
            item.addProperty(COMMAND, server.getCommand() == null
                ? "" : String.join(" ", server.getCommand()));
            servers.add(item);
        }
        return servers;
    }

    private void pushSaved(final boolean theOk, final String theError) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("ok", Boolean.valueOf(theOk));
        payload.addProperty(ERROR, theError);
        ourBridge.push("saved", payload);
    }

    private void pushSettings() {
        final JsonArray providers = new JsonArray();
        for(final String id : LLMSettings.getProviderIds()) {
            final ProviderConfig config = LLMSettings.getConfig(id);
            if(config == null) {
                continue;
            }
            final JsonObject item = new JsonObject();
            item.addProperty("id", config.getId());
            item.addProperty("type", config.getType().name());
            item.addProperty("baseUrl", config.getBaseUrl());
            item.addProperty("hasKey", Boolean.valueOf(config.getApiKey() != null
                && !config.getApiKey().isEmpty()));
            item.addProperty("defaultModel", config.getDefaultModel() == null
                ? "" : config.getDefaultModel());
            item.addProperty("contextWindowTokens", Integer.valueOf(config.getContextWindowTokens()));
            item.addProperty("default", Boolean.valueOf(
                id.equals(LLMSettings.getDefaultProviderId())));
            providers.add(item);
        }
        final JsonObject payload = new JsonObject();
        payload.add("providers", providers);
        payload.add("mcpServers", mcpJson());
        ourBridge.push("settingsData", payload);
    }

    private void pushContacts(final List<Object[]> theProviders) {
        final String defaultId = LLMSettings.getDefaultProviderId();
        final JsonArray providers = new JsonArray();
        final List<Contact> available = new ArrayList<>();
        for(final Object[] entry : theProviders) {
            final boolean ok = ((Boolean)entry[0]).booleanValue();
            final String id = String.valueOf(entry[1]);
            final JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("baseUrl", ok ? String.valueOf(entry[2]) : "");
            item.addProperty("ok", Boolean.valueOf(ok));
            if(!ok) {
                item.addProperty(ERROR, String.valueOf(entry[2]));
            }
            item.addProperty("default", Boolean.valueOf(id.equals(defaultId)));
            final JsonArray models = new JsonArray();
            for(final Object modelName : (List<?>)entry[3]) {
                final String model = String.valueOf(modelName);
                models.add(model);
                available.add(new Contact(id, model));
            }
            item.add("models", models);
            providers.add(item);
        }
        final JsonObject payload = new JsonObject();
        payload.add("providers", providers);
        ourBridge.push("contactsData", payload);
        ourOnLoaded.accept(available);
    }

    /** Re-sends the palette whenever the workbench theme colors change. */
    private void watchTheme() {
        ThemeSupport.watchPalette(ourRoot, palette -> {
            if(ourBridge != null && !ourBridge.getControl().isDisposed()) {
                applyNativePalette(palette);
                ourBridge.push("readyPalette", palette);
            }
        });
    }

    /** Plain tree layout used when no browser backend is available. */
    private void createFallback() {
        final Composite header = new Composite(ourRoot, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        final GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 2;
        header.setLayout(headerLayout);
        new Label(header, SWT.NONE).setText("Провайдеры");
        final Button refreshButton = new Button(header, SWT.PUSH);
        refreshButton.setText("Обновить");
        refreshButton.addListener(SWT.Selection, event -> reload());

        ourTree = new Tree(ourRoot, SWT.SINGLE | SWT.BORDER | SWT.HIDE_SELECTION);
        final GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true);
        treeData.widthHint = 240;
        ourTree.setLayoutData(treeData);
        ourTree.addMouseListener(new org.eclipse.swt.events.MouseAdapter() {
            @Override
            public void mouseDoubleClick(final org.eclipse.swt.events.MouseEvent theEvent) {
                selectContact();
            }

            @Override
            public void mouseUp(final org.eclipse.swt.events.MouseEvent theEvent) {
                final TreeItem underPointer = ourTree
                    .getItem(new org.eclipse.swt.graphics.Point(theEvent.x, theEvent.y));
                if(underPointer != null) {
                    ourTree.setSelection(underPointer);
                }
                selectContact();
            }
        });
        ourTree.addTraverseListener(event -> {
            if(event.detail == SWT.TRAVERSE_RETURN) {
                selectContact();
            }
        });
    }

    private void selectContact() {
        final TreeItem[] selection = ourTree.getSelection();
        if(selection.length == 1 && selection[0].getData() instanceof Contact) {
            ourOnContactSelected.accept((Contact)selection[0].getData());
        }
    }

    private void fillTree(final List<Object[]> theProviders) {
        if(ourTree == null || ourTree.isDisposed()) {
            return;
        }
        ourTree.removeAll();
        final List<Contact> available = new ArrayList<>();
        for(final Object[] entry : theProviders) {
            final boolean ok = (Boolean)entry[0];
            final TreeItem providerNode = new TreeItem(ourTree, SWT.NONE);
            providerNode.setText((ok ? "\u25CF " : "\u25CB ") + entry[1] + " \u2014 " + entry[2]
                + (ok ? " (" + ((List<?>)entry[3]).size() + ")" : ""));
            if(ok) {
                for(final Object modelName : (List<?>)entry[3]) {
                    final String model = String.valueOf(modelName);
                    final Contact contact = new Contact(String.valueOf(entry[1]), model);
                    final TreeItem child = new TreeItem(providerNode, SWT.NONE);
                    child.setText(model);
                    child.setData(contact);
                    available.add(contact);
                }
                providerNode.setExpanded(true);
            }
        }
        ourOnLoaded.accept(available);
    }
}
