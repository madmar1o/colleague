package com.mad.edt.colleague.ui;

import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IStartup;
import org.osgi.framework.Bundle;

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
    }
}
