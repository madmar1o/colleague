package com.mad.edt.colleague.ui.internal;

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
import com.mad.edt.colleague.llm.LLMSettings;
import com.mad.edt.colleague.llm.ProviderConfig;
import com.mad.edt.colleague.llm.ProviderStore;

/**
 * Messenger-style contact list rendered by the bundled {@code web/contacts.html}
 * page inside an embedded browser: one card per configured provider with its
 * chat-capable models as chips; clicking a chip opens a chat tab. The gear on
 * the page header opens a popup with the read-only provider list: pick which
 * provider new dialogs use and remove extra ones. When no browser backend is
 * available the panel falls back to a plain SWT tree plus a refresh button.
 */
public final class ContactsPanel {

    /** A selectable chat contact: provider plus model. */
    public static final class Contact {

        private final String ourProviderId;
        private final String ourModel;

        public Contact(final String theProviderId, final String theModel) {
            ourProviderId = theProviderId;
            ourModel = theModel;
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
         * @return stable key for per-contact session reuse
         */
        public String getKey() {
            return ourProviderId + "\u0000" + ourModel;
        }
    }

    private final Consumer<Contact> ourOnContactSelected;
    private final Consumer<List<Contact>> ourOnLoaded;

    private Composite ourRoot;
    private BridgeBrowser ourBridge;
    private String ourLastPalette = "";
    private Color ourPageBackground;

    private Tree ourTree;
    private boolean ourLoading;

    /**
     * @param theParent parent composite (caller provides the layout)
     * @param theOnContactSelected invoked on the UI thread for model selection
     * @param theOnLoaded invoked on the UI thread after each reload with the
     *            contacts that answered
     */
    public ContactsPanel(final Composite theParent, final Consumer<Contact> theOnContactSelected,
        final Consumer<List<Contact>> theOnLoaded) {
        ourOnContactSelected = theOnContactSelected;
        ourOnLoaded = theOnLoaded;
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
        Job.create("Colleague: contacts", (monitor) -> {
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
                    .filter(name -> !name.contains("embed") && !name.startsWith("bge"))
                    .collect(Collectors.toList());
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
            createFallback(theParent);
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
            ourLastPalette = ready.toString();
            applyNativePalette(ready);
            ourBridge.push("ready", ready);
            watchTheme();
            return;
        } catch (final RuntimeException | LinkageError | org.eclipse.swt.SWTError ex) {
            System.err.println("colleague: contacts browser unavailable, plain fallback: "
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
            case "bridgeReady":
                reload();
                return;
            case "refresh":
                reload();
                return;
            case "openContact":
                ourOnContactSelected.accept(new Contact(
                    BridgeBrowser.optString(theArguments, "provider"),
                    BridgeBrowser.optString(theArguments, "model")));
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
            default:
                return;
        }
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
        pushSaved(true, "");
        reload();
    }

    private void pushSaved(final boolean theOk, final String theError) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("ok", Boolean.valueOf(theOk));
        payload.addProperty("error", theError);
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
            item.addProperty("baseUrl", config.getBaseUrl());
            item.addProperty("default", Boolean.valueOf(
                id.equals(LLMSettings.getDefaultProviderId())));
            providers.add(item);
        }
        final JsonObject payload = new JsonObject();
        payload.add("providers", providers);
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
                item.addProperty("error", String.valueOf(entry[2]));
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
                ourLastPalette = palette.toString();
                applyNativePalette(palette);
                ourBridge.push("readyPalette", palette);
            }
        });
    }

    /** Plain tree layout used when no browser backend is available. */
    private void createFallback(final Composite theParent) {
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
