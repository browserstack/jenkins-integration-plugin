package com.browserstack.automate.ci.jenkins.analytics;

import com.browserstack.automate.ci.common.analytics.AnalyticsDataProvider;
import hudson.Plugin;
import hudson.PluginWrapper;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * @author Shirish Kamath
 * @author Anirudha Khanna
 */
public class JenkinsAnalyticsDataProvider implements AnalyticsDataProvider {
    private static final Logger LOGGER = Logger.getLogger(JenkinsAnalyticsDataProvider.class.getName());

    private static final String PLUGIN_NAME = "browserstack-integration";

    private static PluginWrapper pluginWrapper;

    private boolean isEnabled;

    public JenkinsAnalyticsDataProvider() {
        this.isEnabled = true;

        try {
            pluginWrapper = getPluginWrapper();
        } catch (IOException e) {
            LOGGER.warning("Failed to load plugin properties: " + e.getMessage());
        }
    }

    private static PluginWrapper getPluginWrapper() throws IOException {
        Plugin plugin = Jenkins.getInstance().getPlugin(PLUGIN_NAME);
        if (plugin == null || plugin.getWrapper() == null) {
            throw new IOException("Plugin not found");
        }

        return plugin.getWrapper();
    }

    @Override
    public File getRootDir() {
        return Jenkins.getInstance().getRootDir();
    }

    @Override
    public String getApplicationName() {
        return Jenkins.getInstance().getFullDisplayName();
    }

    @Override
    public String getApplicationVersion() {
        return Jenkins.VERSION;
    }

    @Override
    public String getPluginName() {
        // For Jenkins we add a 'jenkins' at the end because the plugin name is browserstack-integration.
        return pluginWrapper.getShortName() + "-jenkins";
    }

    @Override
    public String getPluginVersion() {
        return pluginWrapper.getVersion();
    }

    @Override
    public boolean isEnabled() {
        return isEnabled;
    }
}
