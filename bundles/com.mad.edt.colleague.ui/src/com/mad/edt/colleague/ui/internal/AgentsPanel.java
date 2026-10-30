package com.mad.edt.colleague.ui.internal;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import com.google.gson.JsonObject;
import com.mad.edt.colleague.agents.AgentProfile;
import com.mad.edt.colleague.chat.PageCatalogEvents;

/**
 * The agents page the view opens first, rendered by the bundled
 * {@code web/agents.html} page inside an embedded browser: one card per agent
 * the workspace offers - the built-in default colleague first, then every
 * profile allowed to run as the chat itself. Clicking a card opens a chat tab
 * under that agent; the gear hands over to the provider and model settings
 * page. The panel never probes providers or writes profiles: it lists what
 * the catalog supplier hands it and reports the user's choices back.
 */
public final class AgentsPanel {

    private final Supplier<List<AgentProfile>> ourAgentsSupplier;
    private final Consumer<String> ourOnOpenAgent;
    private final Runnable ourOnOpenSettings;

    private Composite ourRoot;
    private BridgeBrowser ourBridge;
    private String ourLastPalette = "";
    private Color ourPageBackground;

    /**
     * @param theParent parent composite (caller provides the layout)
     * @param theAgentsSupplier merged agent catalog, consulted on every push
     *            so a changed catalog needs no new panel
     * @param theOnOpenAgent invoked on the UI thread with the picked profile
     *            name, empty for the built-in default colleague
     * @param theOnOpenSettings invoked on the UI thread when the gear opens
     *            the provider and model settings page
     */
    public AgentsPanel(final Composite theParent,
        final Supplier<List<AgentProfile>> theAgentsSupplier,
        final Consumer<String> theOnOpenAgent, final Runnable theOnOpenSettings) {
        ourAgentsSupplier = theAgentsSupplier;
        ourOnOpenAgent = theOnOpenAgent;
        ourOnOpenSettings = theOnOpenSettings;
        ourRoot = new Composite(theParent, SWT.NONE);
        ourRoot.setLayout(new GridLayout(1, false));
        createBrowser();
    }

    /**
     * @return the top-level control of the panel
     */
    public Control getControl() {
        return ourRoot;
    }

    /**
     * @return whether the page path is live; without a browser backend the
     *         view keeps the provider list as the way into a chat instead
     */
    public boolean hasBrowser() {
        return ourBridge != null;
    }

    /** Re-sends the current catalog to the page. */
    public void refresh() {
        if(ourBridge != null && !ourBridge.getControl().isDisposed()) {
            pushAgents();
        }
    }

    /** Releases the SWT controls. */
    public void dispose() {
        if(ourBridge != null && !ourBridge.getControl().isDisposed()) {
            ourBridge.getControl().dispose();
        }
        if(ourRoot != null && !ourRoot.isDisposed()) {
            ourRoot.dispose();
        }
        if(ourPageBackground != null && !ourPageBackground.isDisposed()) {
            ourPageBackground.dispose();
        }
    }

    /**
     * Tries the embedded browser with the bundled agents page.
     */
    private void createBrowser() {
        try {
            ViewBridge.ensureUserDataDir();
            ourBridge = new BridgeBrowser(ourRoot, "web/agents.html", this::handleBridge);
            final JsonObject ready = ThemeSupport.pagePalette(ourRoot);
            ourLastPalette = ready.toString();
            applyNativePalette(ready);
            ourBridge.push("ready", ready);
            watchTheme();
            return;
        } catch (final RuntimeException | LinkageError | org.eclipse.swt.SWTError ex) {
            System.err.println("colleague: agents browser unavailable, plain fallback: "
                + ex.getMessage());
            ourBridge = null;
        }
        if(ourRoot.getChildren().length != 1) {
            for(final Control child : ourRoot.getChildren()) {
                child.dispose();
            }
        }
    }

    private void handleBridge(final String theMethod, final JsonObject theArguments) {
        switch(theMethod) {
            case "bridgeReady":
            case "refresh":
                pushAgents();
                return;
            case "openAgent":
                ourOnOpenAgent.accept(BridgeBrowser.optString(theArguments, "name"));
                return;
            case "openSettings":
                ourOnOpenSettings.run();
                return;
            default:
                return;
        }
    }

    /** Pushes the merged catalog as the cards the page renders. */
    private void pushAgents() {
        final JsonObject payload = new JsonObject();
        payload.add("agents", PageCatalogEvents.agents(ourAgentsSupplier.get()));
        ourBridge.push("agentsData", payload);
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
}
