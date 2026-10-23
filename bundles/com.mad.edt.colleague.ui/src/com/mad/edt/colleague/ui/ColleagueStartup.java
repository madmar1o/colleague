package com.mad.edt.colleague.ui;

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
        System.err.println("[colleague] startup hook ran, version=" + bundle.getVersion());
        if(Boolean.getBoolean("colleague.llm.smoke")) {
            runLLMSmoke(bundle);
        }
    }

    private static void runLLMSmoke(final Bundle theBundle) {
        try {
            final var config = LLMSettings.getConfig(LLMSettings.LOCAL_PROVIDER_ID);
            final ILLMProvider provider = LLMProviderFactory.create(config);
            final String message = "LLM smoke: available=" + provider.isAvailable()
                + ", models=" + provider.listModels();
            Platform.getLog(theBundle).log(new Status(IStatus.INFO, ColleagueUIPlugin.PLUGIN_ID, 0,
                message, null));
            System.err.println("[colleague] " + message);
        } catch (final Exception ex) {
            Platform.getLog(theBundle).log(new Status(IStatus.ERROR, ColleagueUIPlugin.PLUGIN_ID, 0,
                "LLM smoke failed: " + ex.getMessage(), ex));
            System.err.println("[colleague] LLM smoke failed: " + ex.getMessage());
        }
    }
}
