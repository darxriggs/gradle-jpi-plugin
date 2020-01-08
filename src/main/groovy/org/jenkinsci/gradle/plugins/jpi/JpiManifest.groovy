/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.gradle.plugins.jpi

import hudson.Extension
import jenkins.YesNoMaybe
import net.java.sezpoz.Index
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.util.GradleVersion
import org.jenkinsci.gradle.plugins.jpi.internal.VersionCalculator

import java.util.jar.Attributes
import java.util.jar.Manifest

import static java.util.jar.Attributes.Name.MANIFEST_VERSION
import static org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME

/**
 * Encapsulates the Jenkins plugin manifest and its generation.
 *
 * @author Kohsuke Kawaguchi
 */
class JpiManifest extends Manifest {
    JpiManifest(Project project) {
        def conv = project.extensions.getByType(JpiExtension)
        def javaPluginConvention = project.convention.getPlugin(JavaPluginConvention)
        def classDirs = javaPluginConvention.sourceSets.getByName(MAIN_SOURCE_SET_NAME).output.classesDirs

        mainAttributes[MANIFEST_VERSION] = '1.0'

        checkForDuplicateSezpozDetections(classDirs)

        def pluginImpls = classDirs.collect {
            new File(it, 'META-INF/services/hudson.Plugin')
        }.findAll {
            it.exists()
        }

        if (pluginImpls.size() > 1) {
            throw new GradleException(
                    'Found multiple directories containing Jenkins plugin implementations ' +
                            "('${pluginImpls*.path.join("', '")}'). " +
                            'Use joint compilation to work around this problem.'
            )
        }

        def pluginImpl = pluginImpls.find()

        if (pluginImpl?.exists()) {
            mainAttributes.putValue('Plugin-Class', pluginImpl.readLines('UTF-8')[0])
        }

        mainAttributes.putValue('Group-Id', project.group.toString())
        mainAttributes.putValue('Short-Name', conv.shortName)
        mainAttributes.putValue('Long-Name', conv.displayName)
        mainAttributes.putValue('Url', conv.url)
        mainAttributes.putValue('Compatible-Since-Version', conv.compatibleSinceVersion)
        if (conv.sandboxStatus) {
            mainAttributes.putValue('Sandbox-Status', conv.sandboxStatus.toString())
        }
        mainAttributes.putValue('Extension-Name', conv.shortName)

        def version = new VersionCalculator().calculate(project.version.toString())
        mainAttributes.putValue('Plugin-Version', version.toString())

        mainAttributes.putValue('Jenkins-Version', conv.coreVersion)
        mainAttributes.putValue('Minimum-Java-Version', javaPluginConvention.targetCompatibility.toString())

        mainAttributes.putValue('Mask-Classes', conv.maskClasses)

        def dep = findDependencyProjects(project)
        if (dep.length() > 0) {
            mainAttributes.putValue('Plugin-Dependencies', dep)
        }

        if (conv.pluginFirstClassLoader) {
            mainAttributes.putValue('PluginFirstClassLoader', 'true')
        }

        if (conv.developers) {
            mainAttributes.putValue(
                    'Plugin-Developers',
                    conv.developers.collect { "${it.name ?: ''}:${it.id ?: ''}:${it.email ?: ''}" }.join(',')
            )
        }

        YesNoMaybe supportDynamicLoading = isSupportDynamicLoading(classDirs)
        if (supportDynamicLoading != YesNoMaybe.MAYBE) {
            mainAttributes.putValue('Support-Dynamic-Loading', (supportDynamicLoading == YesNoMaybe.YES).toString())
        }

        // remove empty values
        mainAttributes.entrySet().removeAll { it.value == null || it.value.toString().empty }
    }

    static void checkForDuplicateSezpozDetections(FileCollection classesDirs) {
        Set<String> existingSezpozFiles
        classesDirs.each { classDir ->
            def files = new File(classDir, 'META-INF/annotations').list()
            if (files == null) {
                return
            }
            files.each {
                if (!new File(classDir, it).isFile()) {
                    return
                }
                if (existingSezpozFiles.contains(it)) {
                    throw new GradleException("Overlapping Sezpoz file: ${it}. Use joint compilation!")
                }
                existingSezpozFiles.add(it)
            }
        }
    }

    private static String findDependencyProjects(Project project) {
        def buf = new StringBuilder()

        project.configurations.each { conf ->
            if (isJenkinsRuntimeClasspath(conf)) {
                Configuration runtimeElements = project.configurations.named(conf.name.replace("Classpath", "Elements")).get()
                listUpDependencies(conf, conf.name != JpiPlugin.JENKINS_RUNTIME_CLASSPATH_CONFIGURATION_NAME, runtimeElements, buf)
            }
        }

        buf.toString()
    }

    private static boolean isJenkinsRuntimeClasspath(Configuration variant) {
        return (variant.canBeResolved
                && variant.name != JpiPlugin.TEST_JENKINS_RUNTIME_CLASSPATH_CONFIGURATION_NAME
                && variant.attributes.getAttribute(Usage.USAGE_ATTRIBUTE)?.name == Usage.JAVA_RUNTIME
                && variant.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name == Category.LIBRARY
                && variant.attributes.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE)?.name == "jpi")
    }

    private static listUpDependencies(Configuration runtimeClasspath, boolean optional, Configuration runtimeElements, StringBuilder buf) {
        // The category attribute has been introduced in Gradle '5.3'
        if (GradleVersion.current() >= GradleVersion.version('5.3')) {
            listUpResolvedDependencies(runtimeClasspath, optional, runtimeElements, buf)
        } else {
            legacyListUpDependencies(runtimeClasspath, optional, buf)
        }
    }

    private static listUpResolvedDependencies(Configuration runtimeClasspath, boolean optional, Configuration runtimeElements, StringBuilder buf) {
        def categoryAttribute = Attribute.of(Category.CATEGORY_ATTRIBUTE.name, String)

        runtimeClasspath.incoming.resolutionResult.root.dependencies.each { DependencyResult result ->
            if (result.constraint || !(result instanceof ResolvedDependencyResult)) {
                return
            }
            def selected = ((ResolvedDependencyResult) result).selected
            // TODO: Find a better way to exclude platform dependencies
            // TODO: look at all variants and handle capabilities below in runtimeElements.dependencies.add()
            if (selected.variants.size() == 1 &&
                    selected.variants.get(0).attributes.getAttribute(categoryAttribute) != Category.LIBRARY) {
                return
            }
            def moduleVersion = selected.moduleVersion
            if (moduleVersion == null) {
                return
            }
            def pluginDependency = runtimeClasspath.allDependencies.find {
                it.group == moduleVersion.group && it.name == moduleVersion.name }
            runtimeElements.dependencies.add(pluginDependency)

            if (buf.length() > 0) {
                buf.append(',')
            }
            buf.append(moduleVersion.name)
            buf.append(':')
            buf.append(moduleVersion.version)
            if (optional) {
                buf.append(';resolution:=optional')
            }
        }
    }

    private static legacyListUpDependencies(Configuration c, boolean optional, StringBuilder buf) {
        for (Dependency d : c.dependencies) {
            if (buf.length() > 0) {
                buf.append(',')
            }
            buf.append(d.name)
            buf.append(':')
            buf.append(d.version)
            if (optional) {
                buf.append(';resolution:=optional')
            }
        }
    }

    private static YesNoMaybe isSupportDynamicLoading(FileCollection classDirs) throws IOException {
        ClassLoader classLoader = new URLClassLoader(
                classDirs*.toURI()*.toURL() as URL[],
                JpiManifest.classLoader as ClassLoader
        )
        def enums = Index.load(Extension, Object, classLoader).collect { it.annotation().dynamicLoadable() }
        if (enums.contains(YesNoMaybe.NO)) {
            return YesNoMaybe.NO
        }
        if (enums.contains(YesNoMaybe.MAYBE)) {
            return YesNoMaybe.MAYBE
        }
        YesNoMaybe.YES
    }

    static Map<String, ?> attributesToMap(Attributes attributes) {
        attributes.collectEntries { k, v -> [k.toString(), v] } as Map<String, ?>
    }
}
