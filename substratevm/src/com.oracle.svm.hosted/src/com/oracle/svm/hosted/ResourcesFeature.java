/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.svm.hosted;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.oracle.svm.core.util.VMError;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.compiler.serviceprovider.JavaVersionUtil;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.configure.ConfigurationFiles;
import com.oracle.svm.core.configure.ResourceConfigurationParser;
import com.oracle.svm.core.configure.ResourcesRegistry;
import com.oracle.svm.core.jdk.LocalizationFeature;
import com.oracle.svm.core.jdk.Resources;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.hosted.FeatureImpl.DuringAnalysisAccessImpl;
import com.oracle.svm.hosted.config.ConfigurationParserUtils;


@AutomaticFeature
public final class ResourcesFeature implements Feature {

    public static class Options {
        @Option(help = "Regexp to match names of resources to be included in the image.", type = OptionType.User)//
        public static final HostedOptionKey<String[]> IncludeResources = new HostedOptionKey<>(new String[0]);
    }

    private boolean sealed = false;
    private Set<String> newResources = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private int loadedConfigurations;

    private class ResourcesRegistryImpl implements ResourcesRegistry {
        @Override
        public void addResources(String pattern) {
            UserError.guarantee(!sealed, "Resources added too late: %s", pattern);
            newResources.add(pattern);
        }

        @Override
        public void addResourceBundles(String name) {
            ImageSingletons.lookup(LocalizationFeature.class).addBundleToCache(name);
        }
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(ResourcesRegistry.class, new ResourcesRegistryImpl());
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        ImageClassLoader imageClassLoader = ((BeforeAnalysisAccessImpl) access).getImageClassLoader();
        ResourceConfigurationParser parser = new ResourceConfigurationParser(ImageSingletons.lookup(ResourcesRegistry.class));
        loadedConfigurations = ConfigurationParserUtils.parseAndRegisterConfigurations(parser, imageClassLoader, "resource",
                        ConfigurationFiles.Options.ResourceConfigurationFiles, ConfigurationFiles.Options.ResourceConfigurationResources,
                        ConfigurationFiles.RESOURCES_NAME);

        newResources.addAll(Arrays.asList(Options.IncludeResources.getValue()));
    }

    @Override
    public void duringAnalysis(DuringAnalysisAccess access) {
        if (newResources.isEmpty()) {
            return;
        }

        access.requireAnalysisIteration();
        DebugContext debugContext = ((DuringAnalysisAccessImpl) access).getDebugContext();
        final Pattern[] patterns = newResources.stream()
                        .filter(s -> s.length() > 0)
                        .map(Pattern::compile)
                        .collect(Collectors.toList())
                        .toArray(new Pattern[]{});

        if (JavaVersionUtil.JAVA_SPEC > 8) {
            findResourcesInModules(debugContext, patterns);
        }

        for (Pattern pattern : patterns) {

            /*
             * Since IncludeResources takes regular expressions it's safer to disallow passing
             * more than one regex with a single IncludeResources option. Note that it's still
             * possible pass multiple IncludeResources regular expressions by passing each as
             * its own IncludeResources option. E.g.
             * @formatter:off
             * -H:IncludeResources=nobel/prizes.json -H:IncludeResources=fields/prizes.json
             * @formatter:on
             */

            final Set<File> todo = new HashSet<>();
            // Checkstyle: stop
            final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            if (contextClassLoader instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) contextClassLoader).getURLs()) {
                    try {
                        final File file = new File(url.toURI());
                        todo.add(file);
                    } catch (URISyntaxException | IllegalArgumentException e) {
                        throw UserError.abort("Unable to handle imagecp element '" + url.toExternalForm() + "'. Make sure that all imagecp entries are either directories or valid jar files.");
                    }
                }
            }
            // Checkstyle: resume
            for (File element : todo) {
                try {
                    if (element.isDirectory()) {
                        scanDirectory(debugContext, element, "", pattern);
                    } else {
                        scanJar(debugContext, element, pattern);
                    }
                } catch (IOException ex) {
                    throw UserError.abort("Unable to handle classpath element '" + element + "'. Make sure that all classpath entries are either directories or valid jar files.");
                }
            }
        }
        newResources.clear();
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        sealed = true;
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        if (!ImageSingletons.contains(FallbackFeature.class)) {
            return;
        }
        FallbackFeature.FallbackImageRequest resourceFallback = ImageSingletons.lookup(FallbackFeature.class).resourceFallback;
        if (resourceFallback != null && Options.IncludeResources.getValue().length == 0 && loadedConfigurations == 0) {
            throw resourceFallback;
        }
    }

    @SuppressWarnings("try")
    private void scanDirectory(DebugContext debugContext, File f, String relativePath, Pattern... patterns) throws IOException {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files == null) {
                throw UserError.abort("Cannot scan directory " + f);
            } else {
                for (File ch : files) {
                    scanDirectory(debugContext, ch, relativePath.isEmpty() ? ch.getName() : relativePath + "/" + ch.getName(), patterns);
                }
            }
        } else {
            if (matches(patterns, relativePath)) {
                try (FileInputStream is = new FileInputStream(f)) {
                    registerResource(debugContext, relativePath, is);
                }
            }
        }
    }

    @SuppressWarnings("try")
    private static void scanJar(DebugContext debugContext, File element, Pattern... patterns) throws IOException {
        JarFile jf = new JarFile(element);
        Enumeration<JarEntry> en = jf.entries();
        while (en.hasMoreElements()) {
            JarEntry e = en.nextElement();
            if (e.getName().endsWith("/")) {
                continue;
            }
            if (matches(patterns, e.getName())) {
                try (InputStream is = jf.getInputStream(e)) {
                    registerResource(debugContext, e.getName(), is);
                }
            }
        }
    }

    private static void findResourcesInModules(DebugContext debugContext, Pattern[] patterns) {
        for (ResolvedModule resolvedModule : ModuleLayer.boot().configuration().modules()) {
            ModuleReference modRef = resolvedModule.reference();
            try (ModuleReader moduleReader = modRef.open()) {
                final List<String> resources = moduleReader.list()
                                .filter(s -> matches(patterns, s))
                                .collect(Collectors.toList());
                for (String resName : resources) {
                    moduleReader.open(resName)
                                    .ifPresent(is -> registerResource(debugContext, resName, is));
                }
            } catch (IOException ex) {
                VMError.shouldNotReachHere("Can not read the resources of module", ex);
            }
        }
    }

    private static boolean matches(Pattern[] patterns, String relativePath) {
        for (Pattern p : patterns) {
            if (p.matcher(relativePath).matches()) {
                return true;
            }
        }
        return false;
    }

    private static void registerResource(DebugContext debugContext, String resourceName, InputStream resourceStream) {
        debugContext.log(DebugContext.VERBOSE_LEVEL, "ResourcesFeature: registerResource: " + resourceName);
        Resources.registerResource(resourceName, resourceStream);
    }

}
