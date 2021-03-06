/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package nonapi.io.github.classgraph.classpath;

import java.io.File;
import java.lang.reflect.Array;
import java.util.LinkedHashSet;
import java.util.Map;

import io.github.classgraph.ClassGraph.ClasspathElementFilter;
import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A class to find the unique ordered classpath elements. */
public class ClasspathOrder {
    private final ScanSpec scanSpec;
    private final LinkedHashSet<String> classpathOrder = new LinkedHashSet<>();
    private final Map<String, ClassLoader[]> classpathEltPathToClassLoaders;

    ClasspathOrder(final Map<String, ClassLoader[]> classpathEltPathToClassLoaders, final ScanSpec scanSpec) {
        this.classpathEltPathToClassLoaders = classpathEltPathToClassLoaders;
        this.scanSpec = scanSpec;
    }

    /** Get the order of classpath elements. */
    public LinkedHashSet<String> getOrder() {
        return classpathOrder;
    }

    /** Test to see if a RelativePath has been filtered out by the user. */
    private boolean filter(final String classpathElementPath) {
        if (scanSpec.classpathElementFilters != null) {
            for (final ClasspathElementFilter filter : scanSpec.classpathElementFilters) {
                if (!filter.includeClasspathElement(classpathElementPath)) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean addSystemClasspathElement(final String pathElement, final ClassLoader[] classLoaders) {
        if (classpathOrder.add(pathElement)) {
            classpathEltPathToClassLoaders.put(pathElement, classLoaders);
            return true;
        }
        return false;
    }

    private boolean addClasspathElement(final String pathElement, final ClassLoader[] classLoaders) {
        if (SystemJarFinder.getJreLibOrExtJars().contains(pathElement)
                || pathElement.equals(SystemJarFinder.getJreRtJarPath())) {
            // JRE lib and ext jars are handled separately, so reject them as duplicates if they are 
            // returned by a system classloader
            return false;
        }
        if (classpathOrder.add(pathElement)) {
            classpathEltPathToClassLoaders.put(pathElement, classLoaders);
            return true;
        }
        return false;
    }

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about. ClassLoaders will be called in order.
     *
     * @param pathElement
     *            the URL or path of the classpath element.
     * @param classLoaders
     *            the ClassLoader(s) that this classpath element was obtained from.
     * @param scanSpec
     *            the ScanSpec.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null, empty, nonexistent, or filtered out
     *         by user-specified criteria, otherwise return false.
     */
    boolean addClasspathElement(final String pathElement, final ClassLoader[] classLoaders, final ScanSpec scanSpec,
            final LogNode log) {
        if (pathElement == null || pathElement.isEmpty()) {
            return false;
        }
        // Check for wildcard path element (allowable for local classpaths as of JDK 6)
        if (pathElement.endsWith("*")) {
            if (pathElement.length() == 1 || //
                    (pathElement.length() > 2 && pathElement.charAt(pathElement.length() - 1) == '*'
                            && (pathElement.charAt(pathElement.length() - 2) == File.separatorChar
                                    || (File.separatorChar != '/'
                                            && pathElement.charAt(pathElement.length() - 2) == '/')))) {
                // Apply classpath element filters, if any 
                final String baseDirPath = pathElement.length() == 1 ? ""
                        : pathElement.substring(0, pathElement.length() - 2);
                final String baseDirPathResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH, baseDirPath);
                if (!filter(baseDirPath)
                        || (!baseDirPathResolved.equals(baseDirPath) && !filter(baseDirPathResolved))) {
                    if (log != null) {
                        log.log("Classpath element did not match filter criterion, skipping: " + pathElement);
                    }
                    return false;
                }

                // Check the path before the "/*" suffix is a directory 
                final File baseDir = new File(baseDirPathResolved);
                if (!baseDir.exists()) {
                    if (log != null) {
                        log.log("Directory does not exist for wildcard classpath element: " + pathElement);
                    }
                    return false;
                }
                if (!FileUtils.canRead(baseDir)) {
                    if (log != null) {
                        log.log("Cannot read directory for wildcard classpath element: " + pathElement);
                    }
                    return false;
                }
                if (!baseDir.isDirectory()) {
                    if (log != null) {
                        log.log("Wildcard is appended to something other than a directory: " + pathElement);
                    }
                    return false;
                }

                // Add all elements in the requested directory to the classpath
                final LogNode dirLog = log == null ? null
                        : log.log("Adding classpath elements from wildcarded directory: " + pathElement);
                for (final File fileInDir : baseDir.listFiles()) {
                    final String name = fileInDir.getName();
                    if (!name.equals(".") && !name.equals("..")) {
                        // Add each directory entry as a classpath element
                        final String fileInDirPath = fileInDir.getPath();
                        final String fileInDirPathResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
                                fileInDirPath);
                        if (addClasspathElement(fileInDirPathResolved, classLoaders)) {
                            if (dirLog != null) {
                                dirLog.log("Found classpath element: " + fileInDirPath
                                        + (fileInDirPath.equals(fileInDirPathResolved) ? ""
                                                : " -> " + fileInDirPathResolved));
                            }
                        } else {
                            if (dirLog != null) {
                                dirLog.log("Ignoring duplicate classpath element: " + fileInDirPath
                                        + (fileInDirPath.equals(fileInDirPathResolved) ? ""
                                                : " -> " + fileInDirPathResolved));
                            }
                        }
                    }
                }
                return true;
            } else {
                if (log != null) {
                    log.log("Wildcard classpath elements can only end with a leaf of \"*\", "
                            + "can't have a partial name and then a wildcard: " + pathElement);
                }
                return false;
            }
        } else {
            // Non-wildcarded (standard) classpath element
            final String pathElementResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH, pathElement);
            if (!filter(pathElement)
                    || (!pathElementResolved.equals(pathElement) && !filter(pathElementResolved))) {
                if (log != null) {
                    log.log("Classpath element did not match filter criterion, skipping: " + pathElement
                            + (pathElement.equals(pathElementResolved) ? "" : " -> " + pathElementResolved));
                }
                return false;
            }
            if (addClasspathElement(pathElementResolved, classLoaders)) {
                if (log != null) {
                    log.log("Found classpath element: " + pathElement
                            + (pathElement.equals(pathElementResolved) ? "" : " -> " + pathElementResolved));
                }
                return true;
            } else {
                if (log != null) {
                    log.log("Ignoring duplicate classpath element: " + pathElement
                            + (pathElement.equals(pathElementResolved) ? "" : " -> " + pathElementResolved));
                }
                return false;
            }
        }
    }

    /**
     * Add classpath elements, separated by the system path separator character. May be called by a
     * ClassLoaderHandler to add a path string that it knows about.
     *
     * @param pathStr
     *            the delimited string of URLs or paths of the classpath.
     * @param classLoaders
     *            the ClassLoader(s) that this classpath was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElements(final String pathStr, final ClassLoader[] classLoaders, final LogNode log) {
        if (pathStr == null || pathStr.isEmpty()) {
            return false;
        } else {
            final String[] parts = JarUtils.smartPathSplit(pathStr);
            if (parts.length == 0) {
                return false;
            } else {
                for (final String pathElement : parts) {
                    addClasspathElement(pathElement, classLoaders, scanSpec, log);
                }
                return true;
            }
        }
    }

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about.
     *
     * @param pathElement
     *            the URL or path of the classpath element.
     * @param classLoader
     *            the ClassLoader that this classpath element was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElement(final String pathElement, final ClassLoader classLoader, final LogNode log) {
        return addClasspathElement(pathElement, new ClassLoader[] { classLoader }, scanSpec, log);
    }

    /**
     * Add classpath elements from an object obtained from reflection. The object may be a String (containing a
     * single path, or several paths separated with File.pathSeparator), a List or other Iterable, or an array
     * object. In the case of Iterables and arrays, the elements may be any type whose {@code toString()} method
     * returns a path or URL string (including the {@code URL} and {@code Path} types).
     *
     * @param pathObject
     *            the object containing a classpath string or strings.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathEl)ement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElementObject(final Object pathObject, final ClassLoader classLoader,
            final LogNode log) {
        boolean valid = false;
        if (pathObject != null) {
            if (pathObject instanceof String) {
                valid |= addClasspathElements((String) pathObject, classLoader, log);
            } else if (pathObject instanceof Iterable) {
                for (final Object p : (Iterable<?>) pathObject) {
                    if (p != null) {
                        valid |= addClasspathElements(p.toString(), classLoader, log);
                    }
                }
            } else {
                final Class<?> valClass = pathObject.getClass();
                if (valClass.isArray()) {
                    for (int j = 0, n = Array.getLength(pathObject); j < n; j++) {
                        final Object elt = Array.get(pathObject, j);
                        if (elt != null) {
                            valid |= addClasspathElementObject(elt, classLoader, log);
                        }
                    }
                } else {
                    // Try simply calling toString() as a final fallback, in case this returns something sensible
                    valid |= addClasspathElements(pathObject.toString(), classLoader, log);
                }
            }
        }
        return valid;
    }

    /**
     * Add classpath elements, separated by the system path separator character. May be called by a
     * ClassLoaderHandler to add a path string that it knows about.
     *
     * @param pathStr
     *            the delimited string of URLs or paths of the classpath.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathEl)ement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElements(final String pathStr, final ClassLoader classLoader, final LogNode log) {
        return addClasspathElements(pathStr, new ClassLoader[] { classLoader }, log);
    }

    /**
     * Add all classpath elements in another ClasspathElementOrder after the elements in this order.
     *
     * @param subsequentOrder
     *            the ordering to add after this one.
     * @return true, if at least one element was added (i.e. was not a duplicate).
     */
    public boolean addClasspathElements(final ClasspathOrder subsequentOrder) {
        boolean added = false;
        for (final String classpathElt : subsequentOrder.getOrder()) {
            added |= addClasspathElement(classpathElt,
                    subsequentOrder.classpathEltPathToClassLoaders.get(classpathElt));
        }
        return added;
    }
}
