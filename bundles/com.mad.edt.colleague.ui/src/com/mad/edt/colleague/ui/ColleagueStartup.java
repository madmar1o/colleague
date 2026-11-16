package com.mad.edt.colleague.ui;

import com.mad.edt.colleague.ColleagueLog;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IStartup;
import org.osgi.framework.Bundle;

import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMProviderFactory;
import com.mad.edt.colleague.llm.LLMSettings;

/**
 * Workbench startup hook: reports that the Colleague plugin has been loaded.
 */
public class ColleagueStartup implements IStartup {

    @Override
    public void earlyStartup() {
        Bundle bundle = Platform.getBundle(ColleagueUIPlugin.PLUGIN_ID);
        if(bundle == null) {
            return;
        }
        IProduct product = Platform.getProduct();
        String productInfo = product == null
            ? "unknown product"
            : product.getId() + " " + product.getDefiningBundle().getVersion();
        ColleagueUIPlugin plugin = ColleagueUIPlugin.getDefault();
        if(plugin != null) {
            plugin.getLog().log(new Status(IStatus.INFO, ColleagueUIPlugin.PLUGIN_ID, 0,
                "Colleague " + bundle.getVersion() + " activated (product: " + productInfo + ")", null));
        }
        Platform.getLog(bundle).log(new Status(IStatus.INFO, ColleagueUIPlugin.PLUGIN_ID, 0,
            "Colleague startup hook ran: version " + bundle.getVersion() + ", state " + bundle.getState()
                + ", product " + productInfo, null));
        ColleagueLog.info("[colleague] startup hook ran, version=" + bundle.getVersion());
        if(Boolean.getBoolean("colleague.llm.smoke")) {
            runLLMSmoke(bundle);
        }
        if(Boolean.getBoolean("colleague.open.view")) {
            scheduleViewOpen();
        }
    }

    /**
     * Opens the assistant view once the workbench page is available.
     */
    private static void scheduleViewOpen() {
        org.eclipse.swt.widgets.Display.getDefault().syncExec(() -> {
            org.eclipse.swt.widgets.Display.getDefault().timerExec(4_000, () -> {
                try {
                    final var window = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if(window == null || window.getActivePage() == null) {
                        ColleagueLog.warn("[colleague] page not ready, retrying");
                        org.eclipse.swt.widgets.Display.getDefault().timerExec(5_000, VIEW_RETRY);
                        return;
                    }
                    window.getActivePage().showView(com.mad.edt.colleague.ui.view.ColleagueView.ID);
                    ColleagueLog.warn("[colleague] view opened");
                } catch (final Exception ex) {
                    ColleagueLog.warn("[colleague] view open failed: " + ex);
                }
            });
        });
    }

    private static final Runnable VIEW_RETRY = () -> {
        try {
            final var window = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            final var page = window == null ? null : window.getActivePage();
            if(page == null) {
                ColleagueLog.warn("[colleague] page still not ready, giving up");
                return;
            }
            page.showView(com.mad.edt.colleague.ui.view.ColleagueView.ID);
            ColleagueLog.warn("[colleague] view opened (retry)");
        } catch (final Exception ex) {
            ColleagueLog.warn("[colleague] view open retry failed: " + ex);
        }
    };

    private static void runLLMSmoke(final Bundle theBundle) {
        try {
            final var config = LLMSettings.getConfig(LLMSettings.LOCAL_PROVIDER_ID);
            final ILLMProvider provider = LLMProviderFactory.create(config);
            final String message = "LLM smoke: available=" + provider.isAvailable()
                + ", models=" + provider.listModels();
            Platform.getLog(theBundle).log(new Status(IStatus.INFO, ColleagueUIPlugin.PLUGIN_ID, 0,
                message, null));
            ColleagueLog.warn("[colleague] " + message);
        } catch (final Exception ex) {
            Platform.getLog(theBundle).log(new Status(IStatus.ERROR, ColleagueUIPlugin.PLUGIN_ID, 0,
                "LLM smoke failed: " + ex.getMessage(), ex));
            ColleagueLog.error("[colleague] LLM smoke failed: " + ex.getMessage());
        }
    }
}
