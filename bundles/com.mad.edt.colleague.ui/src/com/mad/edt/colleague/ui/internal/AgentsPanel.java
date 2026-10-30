package com.mad.edt.colleague.ui.internal;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mad.edt.colleague.agents.AgentProfile;
import com.mad.edt.colleague.agents.AgentProfileWriter;
import com.mad.edt.colleague.chat.PageCatalogEvents;

/**
 * The agents page the view opens first, rendered by the bundled
 * {@code web/agents.html} page inside an embedded browser: one card per agent
 * the workspace offers - the built-in default colleague first, then every
 * profile allowed to run as the chat itself. Clicking a card opens a chat tab
 * under that agent; the gear hands over to the provider and model settings
 * page. The page can also create, correct and remove agents: the panel turns
 * the form fields into a profile and writes it through
 * {@link AgentProfileWriter} into the catalog level the user chose.
 */
public final class AgentsPanel {

    private final Supplier<List<AgentProfile>> ourAgentsSupplier;
    private final Consumer<String> ourOnOpenAgent;
    private final Runnable ourOnOpenSettings;
    private final Function<String, File> ourLevelDirectory;
    private final Runnable ourOnCatalogChanged;

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
     * @param theLevelDirectory maps a level name ({@code personal} or
     *            {@code project}) to the agents directory to write there,
     *            {@code null} when that level is not available right now
     * @param theOnCatalogChanged invoked on the UI thread after a write so the
     *            view can drop its cached catalog
     */
    public AgentsPanel(final Composite theParent,
        final Supplier<List<AgentProfile>> theAgentsSupplier,
        final Consumer<String> theOnOpenAgent, final Runnable theOnOpenSettings,
        final Function<String, File> theLevelDirectory,
        final Runnable theOnCatalogChanged) {
        ourAgentsSupplier = theAgentsSupplier;
        ourOnOpenAgent = theOnOpenAgent;
        ourOnOpenSettings = theOnOpenSettings;
        ourLevelDirectory = theLevelDirectory;
        ourOnCatalogChanged = theOnCatalogChanged;
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
            case "saveAgent":
                saveAgent(theArguments);
                return;
            case "removeAgent":
                removeAgent(theArguments);
                return;
            default:
                return;
        }
    }

    /**
     * Stores one profile from the page form into the chosen catalog level. A
     * create refuses to shadow an existing id on that level; an edit writes
     * the same id again and replaces the file.
     *
     * @param theArguments form fields: id, level, isNew, title, description,
     *            model, maxSteps, mode, tools, system
     */
    private void saveAgent(final JsonObject theArguments) {
        final String id = BridgeBrowser.optString(theArguments, "id");
        final File dir = ourLevelDirectory.apply(BridgeBrowser.optString(theArguments, "level"));
        if(dir == null) {
            pushSaved(false, "этот уровень сейчас недоступен: нет открытого проекта?");
            return;
        }
        if(theArguments.has("isNew") && theArguments.get("isNew").getAsBoolean()
            && AgentProfileWriter.exists(dir, id)) {
            pushSaved(false, "агент с таким id уже есть на этом уровне");
            return;
        }
        try {
            AgentProfileWriter.save(dir, toProfile(theArguments, id));
        } catch (final IllegalArgumentException ex) {
            pushSaved(false, ex.getMessage());
            return;
        } catch (final IOException ex) {
            pushSaved(false, "запись не удалась: " + ex.getMessage());
            return;
        }
        ourOnCatalogChanged.run();
        pushSaved(true, "");
        pushAgents();
    }

    /**
     * Removes one profile file from the level it was edited on.
     *
     * @param theArguments form fields: id, level
     */
    private void removeAgent(final JsonObject theArguments) {
        final String id = BridgeBrowser.optString(theArguments, "id");
        final File dir = ourLevelDirectory.apply(BridgeBrowser.optString(theArguments, "level"));
        if(dir == null) {
            pushSaved(false, "этот уровень сейчас недоступен: нет открытого проекта?");
            return;
        }
        try {
            final boolean removed = AgentProfileWriter.delete(dir, id);
            if(removed) {
                ourOnCatalogChanged.run();
            }
            pushSaved(removed, removed ? "" : "агент не найден на этом уровне");
        } catch (final IllegalArgumentException ex) {
            pushSaved(false, ex.getMessage());
            return;
        } catch (final IOException ex) {
            pushSaved(false, "удаление не удалось: " + ex.getMessage());
            return;
        }
        pushAgents();
    }

    /**
     * Builds the profile exactly as the form states it. The id is validated by
     * the writer; blank model and zero steps mean "ask nothing special".
     *
     * @param theArguments form fields
     * @param theId validated-later profile id from the form
     * @return the profile to write
     */
    private static AgentProfile toProfile(final JsonObject theArguments, final String theId) {
        final String model = BridgeBrowser.optString(theArguments, "model");
        final Set<String> tools = new LinkedHashSet<>();
        for(final String part : BridgeBrowser.optString(theArguments, "tools").split(",")) {
            final String name = part.trim();
            if(!name.isEmpty()) {
                tools.add(name);
            }
        }
        int steps = 0;
        try {
            steps = Integer.parseInt(BridgeBrowser.optString(theArguments, "maxSteps").trim());
        } catch (final NumberFormatException ignored) {
            steps = 0;
        }
        return new AgentProfile(theId,
            BridgeBrowser.optString(theArguments, "description"),
            BridgeBrowser.optString(theArguments, "system"),
            model.isEmpty() ? null : model,
            steps, modeOf(BridgeBrowser.optString(theArguments, "mode")),
            tools, false, BridgeBrowser.optString(theArguments, "title"));
    }

    /**
     * @param theValue mode as the page names it
     * @return the matching profile mode, ALL for anything unrecognized
     */
    private static AgentProfile.Mode modeOf(final String theValue) {
        if("primary".equalsIgnoreCase(theValue)) {
            return AgentProfile.Mode.PRIMARY;
        }
        if("subagent".equalsIgnoreCase(theValue)) {
            return AgentProfile.Mode.SUBAGENT;
        }
        return AgentProfile.Mode.ALL;
    }

    /**
     * Reports one write attempt to the page form.
     *
     * @param theOk whether the write went through
     * @param theError what to show when it did not
     */
    private void pushSaved(final boolean theOk, final String theError) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("ok", Boolean.valueOf(theOk));
        payload.addProperty("error", theError);
        ourBridge.push("saved", payload);
    }

    /** Pushes the merged catalog as the cards the page renders. */
    private void pushAgents() {
        final JsonArray cards = PageCatalogEvents.agents(ourAgentsSupplier.get());
        for(int i = 0; i < cards.size(); i++) {
            final JsonObject card = cards.get(i).getAsJsonObject();
            if(!card.has("builtin")) {
                // the edit form writes back where the profile actually came
                // from: the same level the merge won with
                card.addProperty("level", levelOf(card.get("name").getAsString()));
            }
        }
        final JsonObject payload = new JsonObject();
        payload.add("agents", cards);
        ourBridge.push("agentsData", payload);
    }

    /**
     * @param theId profile id as the card carries it
     * @return the level whose file won the merge, {@code project} when
     *         neither answers (the safe default for a new write)
     */
    private String levelOf(final String theId) {
        final java.io.File project = ourLevelDirectory.apply("project");
        if(project != null && AgentProfileWriter.exists(project, theId)) {
            return "project";
        }
        final java.io.File personal = ourLevelDirectory.apply("personal");
        if(personal != null && AgentProfileWriter.exists(personal, theId)) {
            return "personal";
        }
        return "project";
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
