package com.mad.edt.colleague.ui;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * UI activator for the Colleague plugin. M4 will contribute the assistant
 * view and commands here.
 */
public class ColleagueUIPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.mad.edt.colleague.ui";

    private static ColleagueUIPlugin instance;

    @Override
    public void start(final BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        Platform.getLog(context.getBundle()).log(new Status(IStatus.INFO, PLUGIN_ID, 0,
            "Colleague bundle started", null));
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }

    public static ColleagueUIPlugin getDefault() {
        return instance;
    }
}
