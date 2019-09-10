/**
 * (C) Copyright IBM Corporation 2015, 2018.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.openliberty.tools.maven.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;

import java.text.MessageFormat;
import java.util.ArrayList;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import io.openliberty.tools.ant.InstallFeatureTask;
import io.openliberty.tools.ant.FeatureManagerTask.Feature;
import io.openliberty.tools.maven.BasicSupport;
import io.openliberty.tools.maven.server.types.Features;
import io.openliberty.tools.common.plugins.util.InstallFeatureUtil;
import io.openliberty.tools.common.plugins.util.PluginExecutionException;
import io.openliberty.tools.common.plugins.util.PluginScenarioException;

/**
 * This mojo installs a feature packaged as a Subsystem Archive (esa) to the
 * runtime.
 */
@Mojo(name = "install-feature", requiresProject = false)
public class InstallFeatureMojo extends BasicSupport {

    /**
     * Define a set of features to install in the server and the configuration to be
     * applied for all instances.
     */
    @Parameter
    private Features features;

    @Parameter(property = "serverXmlFile")
    private String serverXmlFile; // multiple features for CLI invocations

    @Parameter(property = "acceptLicense")
    private String acceptLicense;

    @Parameter(property = "feature")
    private String feature; // single feature for CLI invocations

    private final String STANDALONE_GROUPID = "org.apache.maven";
    private final String STANDALONE_ARTIFACTID = "standalone-pom";

    private boolean noFeaturesSection;

    private class InstallFeatureMojoUtil extends InstallFeatureUtil {
        public InstallFeatureMojoUtil(Set<String> pluginListedEsas)
                throws PluginScenarioException, PluginExecutionException {
            super(installDirectory, features.getFrom(), features.getTo(), pluginListedEsas);
        }

        @Override
        public void debug(String msg) {
            log.debug(msg);
        }

        @Override
        public void debug(String msg, Throwable e) {
            log.debug(msg, e);
        }

        @Override
        public void debug(Throwable e) {
            log.debug(e);
        }

        @Override
        public void warn(String msg) {
            log.warn(msg);
        }

        @Override
        public void info(String msg) {
            log.info(msg);
        }

        @Override
        public boolean isDebugEnabled() {
            return log.isDebugEnabled();
        }

        @Override
        public File downloadArtifact(String groupId, String artifactId, String type, String version)
                throws PluginExecutionException {
            try {
                return getArtifact(groupId, artifactId, type, version).getFile();
            } catch (MojoExecutionException e) {
                throw new PluginExecutionException(e);
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.codehaus.mojo.pluginsupport.MojoSupport#doExecute()
     */
    @Override
    protected void doExecute() throws Exception {
        if (skip) {
            return;
        }

        if (features == null) {
            // For liberty-assembly integration:
            // When using installUtility, if no features section was specified,
            // then don't install features because it requires license acceptance
            noFeaturesSection = true;

            // initialize features section for all scenarios except for the above
            features = new Features();
        }

        checkServerHomeExists();

        if (isRunningCliWithoutPom(getProject())) {
            // running with pom
            System.out.println("Running without pom");
            addCommandLineFeatures();
        }
        installFeatures();

    }

    private void validateParameters() throws MojoExecutionException {
        if (acceptLicense == null) {
            throw new MojoExecutionException("The acceptLicense parameter is missing.");
        }
        if (!new Boolean(acceptLicense).booleanValue()) {
            throw new MojoExecutionException(
                    "The acceptLicense input is false.  To accept the license, set acceptLicense to true.");
        }
        if ((feature == null || feature.isEmpty()) && (serverXmlFile == null || serverXmlFile.isEmpty())) {
            throw new MojoExecutionException("Missing feature or serverXmlFile parameter.");
        }
    }

    private void addCommandLineFeatures() throws MojoExecutionException {
        validateParameters();
        parseSingleFeature();
        parseServerXmlForFeatures();
    }

    private void parseSingleFeature() throws MojoExecutionException {
        if (feature == null) {
            return;
        }

        String[] split = feature.split(":");
        if (split.length == 1) { // feature shortname
            features.addFeature(split[0]);
        }
        if (split.length == 2) {
            // groupid:artifactid
            features.addFeature(split[1]);
        }
        if (split.length == 3) {
            // groupid:artifactid:version
            String version = split[2];
            if (version.equals(detectRuntimeVersion())) {
                features.addFeature(split[1]);
            } else {
                throw new MojoExecutionException("Feature version doesnt match runtime version");
            }
        }

        if (split.length == 4) {
            // groupid:artifactid:type:version
            String type = split[2];
            String version = split[3];
            if ("esa".equals(type) && version.equals(detectRuntimeVersion())) {
                features.addFeature(split[1]);
            } else {
                throw new MojoExecutionException("Feature version doesnt match runtime version or type not esa");
                // TODO: change later to seperate exception from type and version
            }

        }

    }

    private void installFeatures() throws PluginExecutionException {
        Set<String> pluginListedFeatures = getPluginListedFeatures(false);
        Set<String> pluginListedEsas = getPluginListedFeatures(true);

        InstallFeatureUtil util;
        try {
            util = new InstallFeatureMojoUtil(pluginListedEsas);
        } catch (PluginScenarioException e) {
            log.debug(e.getMessage());
            if (noFeaturesSection) {
                log.debug("Skipping feature installation with installUtility because the "
                        + "features configuration element with an acceptLicense parameter "
                        + "was not specified for the install-feature goal.");
            } else {
                log.debug("Installing features from installUtility.");
                installFeaturesFromAnt(features.getFeatures());
            }
            return;
        }

        Set<String> dependencyFeatures = getDependencyFeatures();
        Set<String> serverFeatures = serverDirectory.exists() ? util.getServerFeatures(serverDirectory) : null;

        Set<String> featuresToInstall = InstallFeatureUtil.combineToSet(pluginListedFeatures, dependencyFeatures,
                serverFeatures);

        util.installFeatures(features.isAcceptLicense(), new ArrayList<String>(featuresToInstall));
    }

    private Set<String> getPluginListedFeatures(boolean findEsaFiles) {
        Set<String> result = new HashSet<String>();
        for (Feature feature : features.getFeatures()) {
            if ((findEsaFiles && feature.getFeature().endsWith(".esa"))
                    || (!findEsaFiles && !feature.getFeature().endsWith(".esa"))) {
                result.add(feature.getFeature());
                log.debug("Plugin listed " + (findEsaFiles ? "ESA" : "feature") + ": " + feature.getFeature());
            }
        }
        return result;
    }

    private Set<String> getDependencyFeatures() {
        Set<String> result = new HashSet<String>();
        List<org.apache.maven.model.Dependency> dependencyArtifacts = project.getDependencies();
        for (org.apache.maven.model.Dependency dependencyArtifact : dependencyArtifacts) {
            if (("esa").equals(dependencyArtifact.getType())) {
                result.add(dependencyArtifact.getArtifactId());
                log.debug("Dependency feature: " + dependencyArtifact.getArtifactId());
            }
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private void installFeaturesFromAnt(List<Feature> installFeatures) {
        InstallFeatureTask installFeatureTask = (InstallFeatureTask) ant
                .createTask("antlib:io/openliberty/tools/ant:install-feature");

        if (installFeatureTask == null) {
            throw new IllegalStateException(
                    MessageFormat.format(messages.getString("error.dependencies.not.found"), "install-feature"));
        }

        installFeatureTask.setInstallDir(installDirectory);
        installFeatureTask.setServerName(serverName);
        installFeatureTask.setUserDir(userDirectory);
        installFeatureTask.setOutputDir(outputDirectory);
        installFeatureTask.setAcceptLicense(features.isAcceptLicense());
        installFeatureTask.setTo(features.getTo());
        // whenFileExist is deprecated, but keep it to ensure backward compatibility
        installFeatureTask.setWhenFileExists(features.getWhenFileExists());
        installFeatureTask.setFeatures(installFeatures);
        installFeatureTask.setFrom(features.getFrom());
        installFeatureTask.execute();
    }

    private boolean isRunningCliWithoutPom(MavenProject project) {
        if (project.getFile() == null && project.getGroupId().equals(STANDALONE_GROUPID)
                && project.getArtifactId().equals(STANDALONE_ARTIFACTID)) {
            return true;
        }

        return false;
    }

    private void parseServerXmlForFeatures() throws MojoExecutionException {
        // TODO: REUSE below to get server xml features
        // io.openliberty.tools.common.plugins.util.ServerFeatureUtil.getServerXmlFeatures()

        if (serverXmlFile != null) {
            if (!new File(serverXmlFile).exists()) {
                throw new MojoExecutionException("Cannot locate the server xml " + serverXmlFile);
            }
            try {
                InputStream serverXMLInputStream = new FileInputStream(serverXmlFile);
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(serverXMLInputStream);
                Element element = doc.getDocumentElement();
                NodeList fmList = element.getElementsByTagName("featureManager");
                for (int i = 0; i < fmList.getLength(); i++) {
                    Node fm = fmList.item(i);
                    Element fmElement = (Element) fm;
                    NodeList fList = fmElement.getElementsByTagName("feature");
                    for (int j = 0; j < fList.getLength(); j++) {
                        Node f = fList.item(j);
                        features.addFeature(f.getTextContent().trim());
                    }
                }
            } catch (Exception e) {
                throw new MojoExecutionException("The server xml is invalid :" + e.getMessage());
            }
        }

    }

    private String detectRuntimeVersion() throws MojoExecutionException {
        String sep = System.getProperty("os.name").toLowerCase().indexOf("win") >= 0 ? "\\" : "/"; // get file seperator
        String openLibProperties = installDirectory + sep + "lib" + sep + "versions" + sep + "openliberty.properties";

        try (BufferedReader br = new BufferedReader(new FileReader(openLibProperties))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("com.ibm.websphere.productVersion")) {
                    return line.split("=")[1].trim();
                }
            }

        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }

        return null;

    }

}
