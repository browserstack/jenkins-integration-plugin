package com.browserstack.automate.ci.jenkins;

import com.browserstack.automate.ci.common.TestCaseTracker;
import com.browserstack.automate.ci.jenkins.local.JenkinsBrowserStackLocal;
import com.browserstack.automate.ci.jenkins.local.LocalConfig;
import com.browserstack.automate.ci.jenkins.util.BrowserListingInfo;
import com.browserstack.client.model.BrowserStackObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.tasks.BuildWrapper;
import hudson.util.DescribableList;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class BrowserStackBuildWrapper extends BuildWrapper {
    private static final boolean ENABLE_BROWSER_LISTING = false;


    private final BrowserConfig[] browserConfigs;
    private final LocalConfig localConfig;

    private String credentialsId;
    private String username;
    private String accesskey;
    private boolean hasLoadedBrowsers;
    private BrowserListingInfo browserListingInfo;

    @DataBoundConstructor
    public BrowserStackBuildWrapper(String credentialsId, BrowserConfig[] browserConfig, LocalConfig localConfig) {
        this.credentialsId = credentialsId;
        this.browserConfigs = browserConfig;
        this.localConfig = localConfig;
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher,
                             BuildListener listener) throws IOException, InterruptedException {
        final PrintStream logger = listener.getLogger();

        final BrowserStackCredentials credentials = BrowserStackCredentials.getCredentials(build.getProject(),
                credentialsId);
        if (credentials != null) {
            this.username = credentials.getUsername();
            this.accesskey = credentials.getDecryptedAccesskey();
        }

        JenkinsBrowserStackLocal browserstackLocal = null;
        boolean isMaster = (Computer.currentComputer() instanceof Hudson.MasterComputer);
        if (isMaster && accesskey != null && this.localConfig != null) {
            String binaryPath = Util.fixEmptyAndTrim(this.localConfig.getLocalPath());
            if (StringUtils.isNotBlank(binaryPath)) {
                File binPath = getDescriptor().resolvePath(build.getProject(), binaryPath);
                if (binPath == null) {
                    TestCaseTracker.log(logger, "Local: ERROR: Failed to find binary path.");
                } else {
                    binaryPath = binPath.getAbsolutePath();
                }
            }

            String argString = this.localConfig.getLocalOptions();
            browserstackLocal = new JenkinsBrowserStackLocal(launcher, accesskey, binaryPath, argString);

            try {
                browserstackLocal.start();
            } catch (Exception e) {
                TestCaseTracker.log(logger, "Local: ERROR: " + e.getMessage());
                listener.error("Local: ERROR: Failed to set up BrowserStack Local");
                listener.fatalError("Local: ERROR: " + e.getMessage());
                throw new IOException(e.getCause());
            }
        }

        // loadBrowsers(logger);
        return new AutomateBuildEnvironment(credentials, browserstackLocal, logger);
    }

    @Override
    public BrowserStackBuildWrapperDescriptor getDescriptor() {
        return (BrowserStackBuildWrapperDescriptor) super.getDescriptor();
    }

    @Override
    public OutputStream decorateLogger(AbstractBuild build, OutputStream logger) throws IOException, InterruptedException, Run.RunnerAbortedException {
        return new BuildOutputStream(build, logger, accesskey);
    }

    public BrowserConfig[] getBrowserConfigs() {
        return this.browserConfigs;
    }

    public LocalConfig getLocalConfig() {
        return this.localConfig;
    }

    private void loadBrowsers(PrintStream logger) {
        if (!ENABLE_BROWSER_LISTING || hasLoadedBrowsers) {
            return;
        }

        if (username == null || accesskey == null) {
            TestCaseTracker.log(logger, "Missing BrowserStack credentials");
            return;
        }

        browserListingInfo = BrowserListingInfo.getInstance();
        if (browserListingInfo == null) {
            TestCaseTracker.log(logger, "Error loading browsers: Failed to load OS/Browser list");
            return;
        }

        try {
            browserListingInfo.init(this.username, this.accesskey);
            hasLoadedBrowsers = true;
            TestCaseTracker.log(logger, "Loading browsers... DONE");
        } catch (IOException e) {
            TestCaseTracker.log(logger, "Error loading browsers: " + e.getMessage());
        }
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }


    private interface EnvVars {
        String BROWSERSTACK_USER = "BROWSERSTACK_USER";
        String BROWSERSTACK_ACCESSKEY = "BROWSERSTACK_ACCESSKEY";
        String BROWSERSTACK_BROWSERS = "BROWSERSTACK_BROWSERS";
        String BROWSERSTACK_LOCAL = "BROWSERSTACK_LOCAL";
        String BROWSERSTACK_LOCAL_IDENTIFIER = "BROWSERSTACK_LOCAL_IDENTIFIER";
    }

    private class AutomateBuildEnvironment extends BuildWrapper.Environment {
        private final BrowserStackCredentials credentials;
        private final JenkinsBrowserStackLocal browserstackLocal;
        private final PrintStream logger;

        AutomateBuildEnvironment(BrowserStackCredentials credentials, JenkinsBrowserStackLocal browserstackLocal, PrintStream logger) {
            this.credentials = credentials;
            this.browserstackLocal = browserstackLocal;
            this.logger = logger;
        }

        public void buildEnvVars(Map<String, String> env) {
            if (credentials != null) {
                if (credentials.hasUsername()) {
                    String username = credentials.getUsername();
                    env.put(EnvVars.BROWSERSTACK_USER, username);
                    TestCaseTracker.log(logger, EnvVars.BROWSERSTACK_USER + "=" + username);
                }

                if (credentials.hasAccesskey()) {
                    String accesskey = credentials.getDecryptedAccesskey();
                    env.put(EnvVars.BROWSERSTACK_ACCESSKEY, accesskey);
                    TestCaseTracker.log(logger, EnvVars.BROWSERSTACK_ACCESSKEY + "=" + accesskey);
                }
            }

            if (ENABLE_BROWSER_LISTING) {
                try {
                    String browsersJson = new ObjectMapper().writeValueAsString(generateBrowserList());
                    env.put(EnvVars.BROWSERSTACK_BROWSERS, browsersJson);
                    TestCaseTracker.log(logger, EnvVars.BROWSERSTACK_BROWSERS + "=" + browsersJson);
                } catch (JsonProcessingException e) {
                    // ignore
                }
            }

            String isLocalEnabled = BrowserStackBuildWrapper.this.localConfig != null ? "true" : "false";
            env.put(EnvVars.BROWSERSTACK_LOCAL, "" + isLocalEnabled);
            TestCaseTracker.log(logger, EnvVars.BROWSERSTACK_LOCAL + "=" + isLocalEnabled);

            String localIdentifier = (browserstackLocal != null) ? browserstackLocal.getLocalIdentifier() : "";
            if (StringUtils.isNotBlank(localIdentifier)) {
                env.put(EnvVars.BROWSERSTACK_LOCAL_IDENTIFIER, localIdentifier);
                TestCaseTracker.log(logger, EnvVars.BROWSERSTACK_LOCAL_IDENTIFIER + "=" + localIdentifier);
            }

            super.buildEnvVars(env);
        }

        public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
            if (browserstackLocal != null) {
                try {
                    browserstackLocal.stop();
                } catch (Exception e) {
                    TestCaseTracker.log(logger, "Local: ERROR: " + e.getMessage());
                }
            }

            return true;
        }

        private List<BrowserStackObject> generateBrowserList() {
            List<BrowserStackObject> allBrowsers = new ArrayList<BrowserStackObject>();

            browserListingInfo = BrowserListingInfo.getInstance();
            if (browserListingInfo == null) {
                return allBrowsers;
            }

            if (browserConfigs != null) {
                for (BrowserConfig browserConfig : browserConfigs) {
                    if (browserConfig.getOs() != null && !browserConfig.getOs().equals("null")) {
                        BrowserStackObject browser = browserListingInfo.getDisplayBrowser(browserConfig.getOs(), browserConfig.getBrowser());
                        if (browser != null) {
                            BrowserStackObject automateBrowser = browserListingInfo.getAutomateBrowser(browser);
                            if (automateBrowser != null) {
                                allBrowsers.add(automateBrowser);
                            }
                        }
                    }
                }
            }

            return allBrowsers;
        }
    }

    static BuildWrapperItem<BrowserStackBuildWrapper> findBrowserStackBuildWrapper(final Job<?, ?> job) {
        BuildWrapperItem<BrowserStackBuildWrapper> wrapperItem = findItemWithBuildWrapper(job, BrowserStackBuildWrapper.class);
        return (wrapperItem != null) ? wrapperItem : null;
    }

    private static <T extends BuildWrapper> BuildWrapperItem<T> findItemWithBuildWrapper(final AbstractItem buildItem, Class<T> buildWrapperClass) {
        if (buildItem == null) {
            return null;
        }

        if (buildItem instanceof BuildableItemWithBuildWrappers) {
            BuildableItemWithBuildWrappers buildWrapper = (BuildableItemWithBuildWrappers) buildItem;
            DescribableList<BuildWrapper, Descriptor<BuildWrapper>> buildWrappersList = buildWrapper.getBuildWrappersList();

            if (buildWrappersList != null && !buildWrappersList.isEmpty()) {
                return new BuildWrapperItem<T>(buildWrappersList.get(buildWrapperClass), buildItem);
            }
        }

        if (buildItem.getParent() instanceof AbstractItem) {
            return findItemWithBuildWrapper((AbstractItem) buildItem.getParent(), buildWrapperClass);
        }

        return null;
    }

    static class BuildWrapperItem<T> {
        final T buildWrapper;
        final AbstractItem buildItem;

        BuildWrapperItem(T buildWrapper, AbstractItem buildItem) {
            this.buildWrapper = buildWrapper;
            this.buildItem = buildItem;
        }
    }
}
