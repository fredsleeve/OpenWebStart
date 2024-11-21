package com.openwebstart.launcher;

import com.openwebstart.install4j.Install4JUpdateHandler;
import com.openwebstart.install4j.Install4JUtils;
import com.openwebstart.jvm.ui.dialogs.DialogFactory;
import com.openwebstart.update.UpdatePanelConfigConstants;
import net.adoptopenjdk.icedteaweb.commandline.CommandLineOptions;
import net.adoptopenjdk.icedteaweb.i18n.Translator;
import net.adoptopenjdk.icedteaweb.logging.Logger;
import net.adoptopenjdk.icedteaweb.logging.LoggerFactory;
import net.sourceforge.jnlp.config.ConfigurationConstants;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.Boot;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.FileLog;

import javax.naming.ConfigurationException;
import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static com.openwebstart.concurrent.ThreadPoolHolder.getNonDaemonExecutorService;
import static com.openwebstart.download.ApplicationDownloadIndicator.DOWNLOAD_INDICATOR;
import static net.sourceforge.jnlp.runtime.ForkingStrategy.ALWAYS;


/**
 * This launcher resolves OS specific command line argument handling and starts Iced-Tea Web with the correct
 * argument arrangement.
 */
public class PhaseTwoWebStartLauncher {

    static {
        // this is placed here above the anything else to ensure no logger has been created prior to this line
        FileLog.setLogFileNamePrefix(FileLog.FILE_LOG_NAME_FORMATTER.format(new Date()));
        FileLog.setLogFileNamePostfix("ows-stage1");
    }

    private static final Logger LOG = LoggerFactory.getLogger(PhaseTwoWebStartLauncher.class);
    private static final String consoleOption = "-console";
    private static final List<String> optionsToSkip = Arrays.asList(CommandLineOptions.NOFORK.getOption(), CommandLineOptions.VIEWER.getOption(), consoleOption);

    public static void main(final String... args) {
        // to allow install4j update behind proxy with auth
        // https://stackoverflow.com/questions/41806422/java-web-start-unable-to-tunnel-through-proxy-since-java-8-update-111
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

        Install4JUtils.applicationVersion().ifPresent(v -> LOG.info("Starting OpenWebStart {}", v));

        Translator.addBundle("i18n");

        final DeploymentConfiguration config = new DeploymentConfiguration();
        try {
            checkForLocalDeploymentPropertiesFile();
            config.load();
        } catch (final ConfigurationException e) {
            DialogFactory.showErrorDialog(Translator.getInstance().translate("error.loadConfig"), e);
            JNLPRuntime.exit(-1);
        }

        JNLPRuntime.setDefaultDownloadIndicator(DOWNLOAD_INDICATOR);

        try {
            new InitialConfigurationCheck(config).check();
        } catch (final Exception e) {
            DialogFactory.showErrorDialog(Translator.getInstance().translate("error.initialConfig"), e);
            JNLPRuntime.exit(-1);
        } catch (final UnsatisfiedLinkError e) {
            //TODO: this exception is thrown on windows if you start OWS from the ide instead of using install4J
            LOG.error("Initial configuration was not checked. This normally happens on Windows systems if you start OWS from the IDE.", e);
        }

        if (UpdatePanelConfigConstants.isAutoUpdateActivated(config)) {
            getNonDaemonExecutorService().execute(() -> {
                try {
                    new Install4JUpdateHandler(UpdatePanelConfigConstants.getUpdateScheduleForLauncher(config)).triggerPossibleUpdate();
                } catch (Exception e) {
                    LOG.error("Error in possible update process", e);
                    Install4JUpdateHandler.resetWaitForUpdate();
                }
            });
        } else {
            Install4JUpdateHandler.resetWaitForUpdate();
        }

        // we MUST fork in order to start the application with the jvm from the JVM Manager
        JNLPRuntime.setForkingStrategy(ALWAYS);

        final List<String> bootArgs = skipNotRelevantArgs(args);

        Install4JUpdateHandler.waitForUpdate();

        LOG.info("Calling ITW Boot with args {}.", bootArgs);
        Boot.main(bootArgs.toArray(new String[0]));

        System.exit(0);
    }

    private static List<String> skipNotRelevantArgs(final String[] args) {
        final List<String> relevantJavawsArgs = Arrays.stream(args)
                .filter(arg -> !optionsToSkip.contains(arg))
                .collect(Collectors.toList());

        LOG.debug("RelevantJavawsArgs: '{}'", relevantJavawsArgs);

        return relevantJavawsArgs;
    }

    public static void checkForLocalDeploymentPropertiesFile() {
        if (Install4JUtils.installationDirectory().isPresent()) {
            File[] localDeploymentPropertiesFile = new File(Install4JUtils.installationDirectory().get()).listFiles(file -> file.getName().equalsIgnoreCase(ConfigurationConstants.DEPLOYMENT_PROPERTIES));
            if (localDeploymentPropertiesFile != null && localDeploymentPropertiesFile.length > 0) {
                System.setProperty(DeploymentConfiguration.LOCAL_DEPLOYMENT_PROPERTIES_FILE_PATH, localDeploymentPropertiesFile[0].getAbsolutePath());
            }
        }
    }
}
