package com.mad.edt.colleague.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.mad.edt.colleague.llm.ILLMProvider;
import com.mad.edt.colleague.llm.LLMProviderFactory;
import com.mad.edt.colleague.llm.LLMSettings;

/**
 * Messenger-style contact list: one node per configured LLM provider with
 * its chat-capable models as children. Selecting a model opens a chat tab.
 * Model discovery runs in a background job; the tree is updated via the UI
 * thread. Connecting new providers arrives in a later milestone, so the
 * add button is a visible placeholder now.
 */
public final class ContactsPanel {

    /** A selectable chat contact: provider plus model. */
    public static final class Contact {

        private final String ourProviderId;
        private final String ourModel;

        Contact(final String theProviderId, final String theModel) {
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

    private Tree ourTree;
    private Button ourConnectButton;
    private Button ourModeButton;
    private Runnable ourOnAddProvider;
    private Runnable ourOnModeToggle;
    private boolean ourLoading;

    /**
     * @param theOnModeToggle invoked when the layout mode button is pressed
     */
    public void setOnModeToggle(final Runnable theOnModeToggle) {
        ourOnModeToggle = theOnModeToggle;
    }

    /**
     * @param theLabel caption of the mode toggle button
     */
    public void setModeLabel(final String theLabel) {
        if(ourModeButton != null && !ourModeButton.isDisposed()) {
            ourModeButton.setText(theLabel);
        }
    }

    /**
     * @param theOnAddProvider invoked when the connect button is pressed
     */
    public void setOnAddProvider(final Runnable theOnAddProvider) {
        ourOnAddProvider = theOnAddProvider;
    }

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
        return ourTree;
    }

    /** Reloads provider model lists in the background. */
    public void reload() {
        if(ourLoading || ourTree.isDisposed()) {
            return;
        }
        ourLoading = true;
        Job.create("Colleague: contacts", (monitor) -> {
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
            Display.getDefault().asyncExec(() -> fillTree(providers));
            return Status.OK_STATUS;
        }).schedule();
    }

    /** Releases the SWT controls. */
    public void dispose() {
        if(ourTree != null && !ourTree.isDisposed()) {
            ourTree.dispose();
        }
    }

    private void createControls(final Composite theParent) {
        final Composite root = new Composite(theParent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        final Composite header = new Composite(root, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        final GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 2;
        header.setLayout(headerLayout);
        new Label(header, SWT.NONE).setText("Контакты");
        final Button refreshButton = new Button(header, SWT.PUSH);
        refreshButton.setText("Обновить");
        refreshButton.addListener(SWT.Selection, event -> reload());
        ourModeButton = new Button(header, SWT.PUSH);
        ourModeButton.setText("Мобильный");
        ourModeButton.addListener(SWT.Selection, event -> {
            if(ourOnModeToggle != null) {
                ourOnModeToggle.run();
            }
        });

        ourTree = new Tree(root, SWT.SINGLE | SWT.BORDER | SWT.HIDE_SELECTION);
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

        ourConnectButton = new Button(root, SWT.PUSH);
        ourConnectButton.setText("+ Подключить провайдер");
        ourConnectButton.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        ourConnectButton.addListener(SWT.Selection, event -> {
            if(ourOnAddProvider != null) {
                ourOnAddProvider.run();
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
        if(ourTree.isDisposed()) {
            return;
        }
        ourLoading = false;
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
