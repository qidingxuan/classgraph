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
package io.github.classgraph;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.recycler.RecycleOnClose;
import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.URLPathEncoder;

/** A module classpath element. */
class ClasspathElementModule extends ClasspathElement {
    private final ModuleRef moduleRef;
    private final NestedJarHandler nestedJarHandler;
    private Recycler<ModuleReaderProxy, IOException> moduleReaderProxyRecycler;
    private final Set<String> allResourcePaths = new HashSet<>();

    /** A zip/jarfile classpath element. */
    ClasspathElementModule(final ModuleRef moduleRef, final ClassLoader[] classLoaders,
            final NestedJarHandler nestedJarHandler, final ScanSpec scanSpec) {
        super(classLoaders, scanSpec);
        this.moduleRef = moduleRef;
        this.nestedJarHandler = nestedJarHandler;
        if (scanSpec.performScan) {
            resourceMatches = new ArrayList<>();
            whitelistedClassfileResources = new ArrayList<>();
            fileToLastModified = new HashMap<>();
        }
    }

    @Override
    void checkValid(final WorkQueue<String> workQueue, final LogNode log) {
        try {
            moduleReaderProxyRecycler = nestedJarHandler.moduleRefToModuleReaderProxyRecyclerMap.get(moduleRef,
                    log);
        } catch (final IOException | IllegalArgumentException e) {
            if (log != null) {
                log.log("Exception while creating ModuleReaderProxy recycler for " + moduleRef.getName() + " : "
                        + e);
            }
            skipClasspathElement = true;
            return;
        } catch (final Exception e) {
            if (log != null) {
                log.log("Exception while creating ModuleReaderProxy recycler for " + moduleRef.getName(), e);
            }
            skipClasspathElement = true;
            return;
        }
    }

    /** Create a new {@link Resource} object for a resource or classfile discovered while scanning paths. */
    private Resource newResource(final String moduleResourcePath) {
        return new Resource() {
            private ModuleReaderProxy moduleReaderProxy;

            @Override
            public String getPath() {
                return moduleResourcePath;
            }

            @Override
            public String getPathRelativeToClasspathElement() {
                return moduleResourcePath;
            }

            @Override
            public URL getURL() {
                try {
                    if (moduleRef.getLocationStr() != null) {
                        // Use module location string as URL base, if present
                        return URLPathEncoder.urlPathToURL(moduleRef.getLocationStr() + "!/" + moduleResourcePath);
                    } else {
                        // If there is no known module location, just make up a "jrt:" path based on the module
                        // name, so that the user can see something reasonable in the result
                        return URLPathEncoder
                                .urlPathToURL("jrt:/" + moduleRef.getName() + "/" + moduleResourcePath);
                    }
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException("Could not form URL for module location: "
                            + moduleRef.getLocationStr() + " ; path: " + moduleResourcePath);
                }
            }

            @Override
            public URL getClasspathElementURL() {
                try {
                    if (moduleRef.getLocation() == null) {
                        // If there is no known module location, just guess a "jrt:" path based on the module
                        // name, so that the user can see something reasonable in the result
                        return new URL(new URL("jrt:/" + moduleRef.getName()).toString());
                    } else {
                        return moduleRef.getLocation().toURL();
                    }
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException(
                            "Could not form URL for module classpath element: " + moduleRef.getLocationStr());
                }
            }

            @Override
            public File getClasspathElementFile() {
                return null;
            }

            @Override
            public ModuleRef getModuleRef() {
                return moduleRef;
            }

            @Override
            public ByteBuffer read() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Module could not be opened");
                }
                if (byteBuffer != null || inputStream != null || moduleReaderProxy != null) {
                    throw new IllegalArgumentException(
                            "Resource is already open -- cannot open it again without first calling close()");
                } else {
                    try {
                        moduleReaderProxy = moduleReaderProxyRecycler.acquire();
                        // ModuleReader#read(String name) internally calls:
                        // InputStream is = open(name); return ByteBuffer.wrap(is.readAllBytes());
                        byteBuffer = moduleReaderProxy.read(moduleResourcePath);
                        length = byteBuffer.remaining();
                        return byteBuffer;

                    } catch (final Exception e) {
                        close();
                        throw new IOException("Could not open " + this, e);
                    }
                }
            }

            @Override
            InputStreamOrByteBufferAdapter openOrRead() throws IOException {
                return new InputStreamOrByteBufferAdapter(open());
            }

            @Override
            public InputStream open() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Module could not be opened");
                }
                if (byteBuffer != null || inputStream != null || moduleReaderProxy != null) {
                    throw new IllegalArgumentException(
                            "Resource is already open -- cannot open it again without first calling close()");
                } else {
                    try {
                        moduleReaderProxy = moduleReaderProxyRecycler.acquire();
                        inputStream = new InputStreamResourceCloser(this,
                                moduleReaderProxy.open(moduleResourcePath));
                        // Length cannot be obtained from ModuleReader
                        length = -1L;
                        return inputStream;

                    } catch (final Exception e) {
                        close();
                        throw new IOException("Could not open " + this, e);
                    }
                }
            }

            @Override
            public byte[] load() throws IOException {
                try {
                    read();
                    final byte[] byteArray = byteBufferToByteArray();
                    length = byteArray.length;
                    return byteArray;
                } finally {
                    close();
                }
            }

            @Override
            public void close() {
                if (inputStream != null) {
                    try {
                        // Avoid infinite loop with InputStreamResourceCloser trying to close its parent resource
                        final InputStream inputStreamWrapper = inputStream;
                        inputStream = null;
                        inputStreamWrapper.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                }
                if (byteBuffer != null) {
                    if (moduleReaderProxy != null) {
                        try {
                            // Release any open ByteBuffer
                            moduleReaderProxy.release(byteBuffer);
                        } catch (final Exception e) {
                            // Ignore
                        }
                    }
                    byteBuffer = null;
                }
                if (moduleReaderProxy != null) {
                    // Recycle the (open) ModuleReaderProxy instance.
                    moduleReaderProxyRecycler.recycle(moduleReaderProxy);
                    // Don't call ModuleReaderProxy#close(), leave the ModuleReaderProxy open in the recycler.
                    // Just set the ref to null here. The ModuleReaderProxy will be closed by
                    // ClasspathElementModule#close().
                    moduleReaderProxy = null;
                }
            }
        };
    }

    /**
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    @Override
    Resource getResource(final String relativePath) {
        final String path = FileUtils.sanitizeEntryPath(relativePath);
        if (path.isEmpty() || path.endsWith("/")) {
            return null;
        }
        return allResourcePaths.contains(path) ? newResource(path) : null;
    }

    /** Scan for package matches within module */
    @Override
    void scanPaths(final LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalArgumentException("Already scanned classpath element " + toString());
        }

        final String moduleLocationStr = moduleRef.getLocationStr();
        final LogNode subLog = log == null ? null
                : log.log(moduleLocationStr, "Scanning module " + moduleRef.getName());

        try (final RecycleOnClose<ModuleReaderProxy, IOException> moduleReaderProxyRecycleOnClose //
                = moduleReaderProxyRecycler.acquireRecycleOnClose()) {
            // Look for whitelisted files in the module.
            List<String> resourceRelativePaths;
            try {
                resourceRelativePaths = moduleReaderProxyRecycleOnClose.get().list();
            } catch (final Exception e) {
                if (subLog != null) {
                    subLog.log("Could not get resource list for module " + moduleRef.getName(), e);
                }
                return;
            }
            Collections.sort(resourceRelativePaths);

            String prevParentRelativePath = null;
            ScanSpecPathMatch prevParentMatchStatus = null;
            for (final String relativePath : resourceRelativePaths) {
                // From ModuleReader#find(): "If the module reader can determine that the name locates a
                // directory then the resulting URI will end with a slash ('/')."  But from the documentation
                // for ModuleReader#list(): "Whether the stream of elements includes names corresponding to
                // directories in the module is module reader specific."  We don't have a way of checking if
                // a resource is a directory without trying to open it, unless ModuleReader#list() also decides
                // to put a "/" on the end of resource paths corresponding to directories. Skip directories if
                // they are found, but if they are not able to be skipped, we will have to settle for having
                // some IOExceptions thrown when directories are mistaken for resource files.
                if (relativePath.endsWith("/")) {
                    continue;
                }

                // Whitelist/blacklist classpath elements based on file resource paths
                if (!scanSpec.classpathElementResourcePathWhiteBlackList.whitelistAndBlacklistAreEmpty()) {
                    if (scanSpec.classpathElementResourcePathWhiteBlackList.isBlacklisted(relativePath)) {
                        if (subLog != null) {
                            subLog.log("Reached blacklisted classpath element resource path, stopping scanning: "
                                    + relativePath);
                        }
                        skipClasspathElement = true;
                        return;
                    }
                    if (scanSpec.classpathElementResourcePathWhiteBlackList
                            .isSpecificallyWhitelisted(relativePath)) {
                        if (subLog != null) {
                            subLog.log("Reached specifically whitelisted classpath element resource path: "
                                    + relativePath);
                        }
                        containsSpecificallyWhitelistedClasspathElementResourcePath = true;
                    }
                }

                // Get match status of the parent directory of this resource's relative path (or reuse the last
                // match status for speed, if the directory name hasn't changed).
                final int lastSlashIdx = relativePath.lastIndexOf("/");
                final String parentRelativePath = lastSlashIdx < 0 ? "/"
                        : relativePath.substring(0, lastSlashIdx + 1);
                final boolean parentRelativePathChanged = !parentRelativePath.equals(prevParentRelativePath);
                final ScanSpecPathMatch parentMatchStatus = //
                        prevParentRelativePath == null || parentRelativePathChanged
                                ? scanSpec.dirWhitelistMatchStatus(parentRelativePath)
                                : prevParentMatchStatus;
                prevParentRelativePath = parentRelativePath;
                prevParentMatchStatus = parentMatchStatus;

                if (parentMatchStatus == ScanSpecPathMatch.HAS_BLACKLISTED_PATH_PREFIX) {
                    // The parent dir or one of its ancestral dirs is blacklisted
                    if (subLog != null) {
                        subLog.log("Skipping blacklisted path: " + relativePath);
                    }
                    continue;
                }

                // Found non-blacklisted relative path
                allResourcePaths.add(relativePath);

                // If resource is whitelisted
                if (parentMatchStatus == ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                        || parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_PATH
                        || (parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                                && scanSpec.classfileIsSpecificallyWhitelisted(relativePath))) {
                    // Add whitelisted resource
                    final Resource resource = newResource(relativePath);
                    addWhitelistedResource(resource, parentMatchStatus, subLog);
                }
            }

            // Save last modified time for the module file
            final File moduleFile = moduleRef.getLocationFile();
            if (moduleFile != null && moduleFile.exists()) {
                fileToLastModified.put(moduleFile, moduleFile.lastModified());
            }

        } catch (final IOException e) {
            if (subLog != null) {
                subLog.log("Exception opening module " + moduleRef.getName(), e);
            }
            skipClasspathElement = true;
        }
        if (subLog != null) {
            subLog.addElapsedTime();
        }
    }

    /** Get the ModuleRef for this classpath element. */
    ModuleRef getModuleRef() {
        return moduleRef;
    }

    /** Return the module reference. */
    @Override
    public String toString() {
        return moduleRef.toString();
    }
}
