/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.feature.launcher.impl;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonReader;

import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.Configuration;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.FeatureConstants;
import org.apache.sling.feature.KeyValueMap;
import org.apache.sling.feature.builder.FeatureBuilder;
import org.apache.sling.feature.io.ArtifactHandler;
import org.apache.sling.feature.io.ArtifactManager;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.feature.launcher.impl.LauncherConfig.StartupMode;

public class FeatureProcessor {

    /**
     * Initialize the launcher
     * Read the features and prepare the application
     * @param config The current configuration
     * @param artifactManager The artifact manager
     */
    public static Feature createApplication(final LauncherConfig config,
            final ArtifactManager artifactManager)
    throws IOException {
        Feature app = null;
        if ( config.getApplicationFile() != null ) {
            app = read(config.getApplicationFile(), artifactManager, config.getVariables());
            // write application back
            final File file = new File(config.getHomeDirectory(), "resources" + File.separatorChar + "provisioning" + File.separatorChar + "application.json");
            file.getParentFile().mkdirs();

            try (final FileWriter writer = new FileWriter(file)) {
                FeatureJSONWriter.write(writer, app);
            } catch ( final IOException ioe) {
                Main.LOG().error("Error while writing application file: {}", ioe.getMessage(), ioe);
                System.exit(1);
            }
        }
        else {
            app = read(new File(config.getHomeDirectory(), "resources" + File.separatorChar + "provisioning" + File.separatorChar + "application.json").getPath(),
                    artifactManager, config.getVariables());
        }

        for (Artifact bundle : app.getBundles()) {
            if ( bundle.getStartOrder() == 0) {
                final int so = bundle.getMetadata().get("start-level") != null ? Integer.parseInt(bundle.getMetadata().get("start-level")) : 1;
                bundle.setStartOrder(so);
            }
        }

        return app;
    }

    private static Feature read(String absoluteArg, ArtifactManager artifactManager,
            KeyValueMap overriddenVars) throws IOException {
        if ( absoluteArg.indexOf(":") < 2 ) {
            absoluteArg = new File(absoluteArg).getAbsolutePath();
        }
        final ArtifactHandler appArtifact = artifactManager.getArtifactHandler(absoluteArg);

        try (final FileReader r = new FileReader(appArtifact.getFile())) {
            final Feature f = FeatureJSONReader.read(r, appArtifact.getUrl());
            FeatureBuilder.resolveVariables(f, overriddenVars);
            return f;
        }
    }
    /**
     * Prepare the launcher
     * - add all bundles to the bundle map of the installation object
     * - add all other artifacts to the install directory (only if startup mode is INSTALL)
     * - process configurations
     */
    public static void prepareLauncher(final LauncherConfig config,
            final ArtifactManager artifactManager,
            final Feature app) throws Exception {
        for(final Map.Entry<Integer, List<Artifact>> entry : app.getBundles().getBundlesByStartOrder().entrySet()) {
            for(final Artifact a : entry.getValue()) {
                final ArtifactHandler handler = artifactManager.getArtifactHandler(":" + a.getId().toMvnPath());
                final File artifactFile = handler.getFile();

                config.getInstallation().addBundle(entry.getKey(), artifactFile);
            }
        }
        int index = 1;
        for(final Extension ext : app.getExtensions()) {
            if ( ext.getType() == ExtensionType.ARTIFACTS ) {
                for(final Artifact a : ext.getArtifacts() ) {
                    if ( config.getStartupMode() == StartupMode.PURE ) {
                        throw new Exception("Artifacts other than bundle are not supported by framework launcher.");
                    }
                    final ArtifactHandler handler = artifactManager.getArtifactHandler(":" + a.getId().toMvnPath());
                    config.getInstallation().addInstallableArtifact(handler.getFile());
                }
            } else if ( ext.getName().equals(FeatureConstants.EXTENSION_NAME_REPOINIT) ) {
                    String text;
                    if ( ext.getType() == ExtensionType.TEXT ) {
                        text = ext.getText();
                    }
                    else if (ext.getType() == ExtensionType.JSON) {
                        try (JsonReader reader = Json.createReader(new StringReader(ext.getJSON()))){
                            JsonArray array = reader.readArray();
                            if (array.size() > 0) {
                                text = array.getString(0);
                                for (int i = 1; i < array.size(); i++) {
                                    text += "\n" + array.getString(i);
                                }
                            }
                            else {
                                text = "";
                            }
                        }
                    }
                    else {
                        throw new Exception(FeatureConstants.EXTENSION_NAME_REPOINIT + " extension must be of type text or json");
                    }
                    final Configuration cfg = new Configuration("org.apache.sling.jcr.repoinit.RepositoryInitializer", "repoinit" + String.valueOf(index));
                    index++;
                    cfg.getProperties().put("scripts", text);
                    config.getInstallation().addConfiguration(cfg.getName(), cfg.getFactoryPid(), cfg.getProperties());
            } else {
                if ( ext.isRequired() ) {
                    throw new Exception("Unknown required extension " + ext.getName());
                }
            }
        }

        for(final Configuration cfg : app.getConfigurations()) {
            if ( cfg.isFactoryConfiguration() ) {
                config.getInstallation().addConfiguration(cfg.getName(), cfg.getFactoryPid(), cfg.getProperties());
            } else {
                config.getInstallation().addConfiguration(cfg.getPid(), null, cfg.getProperties());
            }
        }

        for(final Map.Entry<String, String> prop : app.getFrameworkProperties()) {
            if ( !config.getInstallation().getFrameworkProperties().containsKey(prop.getKey()) ) {
                config.getInstallation().getFrameworkProperties().put(prop.getKey(), prop.getValue());
            }
        }
    }

    /**
     * Prepare the cache
     * - add all bundles
     * - add all other artifacts (only if startup mode is INSTALL)
     */
    public static Map<Artifact, File> calculateArtifacts(final ArtifactManager artifactManager,
        final Feature app) throws Exception
    {
        Map<Artifact, File> result = new HashMap<>();
        for (final Map.Entry<Integer, List<Artifact>> entry : app.getBundles().getBundlesByStartOrder().entrySet())
        {
            for (final Artifact a : entry.getValue())
            {
                final ArtifactHandler handler = artifactManager.getArtifactHandler(":" + a.getId().toMvnPath());
                final File artifactFile = handler.getFile();

                result.put(a, artifactFile);
            }
        }
        for (final Extension ext : app.getExtensions())
        {
            if (ext.getType() == ExtensionType.ARTIFACTS)
            {
                for (final Artifact a : ext.getArtifacts())
                {
                    final ArtifactHandler handler = artifactManager.getArtifactHandler(":" + a.getId().toMvnPath());
                    result.put(a, handler.getFile());
                }
            }
        }
        return result;
    }
}
