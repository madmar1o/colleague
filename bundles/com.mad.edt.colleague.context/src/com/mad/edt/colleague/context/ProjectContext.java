package com.mad.edt.colleague.context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

/**
 * Builds textual descriptions of workspace projects and resources for LLM
 * prompts. Operates on the plain Eclipse resource model, so it works with
 * any project layout without platform-specific APIs.
 */
public final class ProjectContext {

    /** File suffixes whose content is safe to embed as text. */
    private static final List<String> TEXT_SUFFIXES =
        List.of("bsl", "mdo", "form", "xml", "txt", "properties", "json", "flex", "fct");

    private static final int DEFAULT_MAX_FILE_CHARS = 6_000;

    private ProjectContext() {
        // utility
    }

    /**
     * Describes an opened project: name, comment and a shallow listing of
     * the top-level directories below the conventional {@code src} folder.
     *
     * @param theProject the project
     * @return multi-line description, empty when the project is unavailable
     */
    public static String describeProject(final IProject theProject) {
        if(theProject == null || !theProject.isOpen()) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        builder.append("Project: ").append(theProject.getName());
        try {
            final IFolder source = theProject.getFolder("src");
            if(source.exists()) {
                builder.append("\nSource layout (src/<type>/<name>): ");
                builder.append(joinNames(childrenOf(source), 40));            }
        } catch (final CoreException ex) {
            builder.append("\n(resource listing failed: ").append(ex.getMessage()).append(')');
        }
        return builder.toString();
    }

    /**
     * Describes one selected resource. For text files the content (possibly
     * truncated) is embedded; for folders the child names are listed.
     *
     * @param theResource the resource
     * @param theMaxFileChars budget for embedded file text
     * @return description, empty when the resource is unavailable
     */
    public static String describeResource(final IResource theResource, final int theMaxFileChars) {
        if(theResource == null) {
            return "";
        }
        final int maxChars = theMaxFileChars > 0 ? theMaxFileChars : DEFAULT_MAX_FILE_CHARS;
        switch(theResource.getType()) {
            case IResource.FILE:
                return describeFile((IFile)theResource, maxChars);
            case IResource.FOLDER:
            case IResource.PROJECT:
                if(!(theResource instanceof IContainer)) {
                    return "Resource: " + theResource.getFullPath();
                }
                final IContainer container = (IContainer)theResource;
                try {
                    return "Folder: " + theResource.getProjectRelativePath()
                        + "\nChildren: " + joinNames(childrenOf(container), 60);
                } catch (final CoreException ex) {
                    return "Folder: " + theResource.getProjectRelativePath();
                }
            default:
                return "Resource: " + theResource.getFullPath();
        }
    }

    /**
     * Convenience overload with the default content budget.
     *
     * @param theResource the resource
     * @return description
     */
    public static String describeResource(final IResource theResource) {
        return describeResource(theResource, DEFAULT_MAX_FILE_CHARS);
    }

    private static String describeFile(final IFile theFile, final int theMaxChars) {
        final StringBuilder builder = new StringBuilder();
        builder.append("File: ").append(theFile.getProjectRelativePath().toPortableString());
        final File local = theFile.getLocation() == null ? null : theFile.getLocation().toFile();
        if(local == null || !local.isFile()) {
            return builder.toString();
        }
        if(!isTextual(local.getName())) {
            return builder.append(" (binary, content omitted)").toString();
        }
        builder.append("\nContent:\n");
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(local), StandardCharsets.UTF_8))) {
            final char[] buffer = new char[theMaxChars];
            final int read = readAtMost(reader, buffer);
            if(read > 0) {
                builder.append(buffer, 0, read);
                if(reader.ready()) {
                    builder.append("\n...[truncated]");
                }
            }
        } catch (final IOException ex) {
            builder.append("(cannot read: ").append(ex.getMessage()).append(')');
        }
        return builder.toString();
    }

    /**
     * @param thePath path to test
     * @return {@code true} when the extension belongs to known text formats
     */
    public static boolean isTextual(final String thePath) {
        final int dot = thePath.lastIndexOf('.');
        if(dot < 0) {
            return false;
        }
        return TEXT_SUFFIXES.contains(thePath.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Finds the project that owns the given absolute or workspace-relative
     * path, if any.
     *
     * @param thePath file system path
     * @return matching open project or {@code null}
     */
    public static IProject findProjectFor(final IPath thePath) {
        final org.eclipse.core.resources.IWorkspaceRoot root =
            org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
        for(final IProject project : root.getProjects()) {
            final IPath location = project.getLocation();
            if(location != null && thePath != null && thePath.makeAbsolute().toString()
                .startsWith(location.toPortableString())) {
                return project;
            }
        }
        return null;
    }

    private static List<IResource> childrenOf(final IContainer theContainer) throws CoreException {
        final List<IResource> children = new ArrayList<>();
        for(final IResource child : theContainer.members()) {
            children.add(child);
        }
        return children;
    }

    private static String joinNames(final List<IResource> theResources, final int theLimit) {
        final StringBuilder builder = new StringBuilder();
        int index = 0;
        for(final IResource resource : theResources) {
            if(index >= theLimit) {
                builder.append(", ...");
                break;
            }
            if(index > 0) {
                builder.append(", ");
            }
            builder.append(resource.getName());
            if(resource.getType() == IResource.FOLDER || resource.getType() == IResource.PROJECT) {
                builder.append('/');
            }
            index++;
        }
        return builder.toString();
    }

    private static int readAtMost(final BufferedReader theReader, final char[] theBuffer) throws IOException {
        int total = 0;
        int read;
        while(total < theBuffer.length && (read = theReader.read(theBuffer, total,
            theBuffer.length - total)) > 0) {
            total += read;
        }
        return total;
    }
}
