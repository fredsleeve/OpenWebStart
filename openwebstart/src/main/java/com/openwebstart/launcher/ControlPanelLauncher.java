package com.openwebstart.launcher;

import com.openwebstart.install4j.Install4JUpdateHandler;
import com.openwebstart.install4j.Install4JUtils;
import com.openwebstart.jvm.ui.dialogs.DialogFactory;
import com.openwebstart.update.UpdatePanelConfigConstants;
import net.adoptopenjdk.icedteaweb.client.controlpanel.ControlPanel;
import net.adoptopenjdk.icedteaweb.commandline.CommandLineOptions;
import net.adoptopenjdk.icedteaweb.i18n.Translator;
import net.adoptopenjdk.icedteaweb.logging.Logger;
import net.adoptopenjdk.icedteaweb.logging.LoggerFactory;
import net.adoptopenjdk.icedteaweb.ui.swing.SwingUtils;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.EnvironmentPrinter;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.FileLog;

import javax.naming.ConfigurationException;
import javax.swing.UIManager;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;

import static com.openwebstart.concurrent.ThreadPoolHolder.getNonDaemonExecutorService;
import static com.openwebstart.download.ApplicationDownloadIndicator.DOWNLOAD_INDICATOR;

public class ControlPanelLauncher {

    static {
        // this is placed here above the any thing else to ensure no logger has been created prior to this line
        FileLog.setLogFileNamePostfix("ows-settings");
    }

    private static final Logger LOG = LoggerFactory.getLogger(ControlPanelLauncher.class);


    public static void main(final String[] args) {
        // to allow install4j update behind proxy with auth
        // https://stackoverflow.com/questions/41806422/java-web-start-unable-to-tunnel-through-proxy-since-java-8-update-111
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

        EnvironmentPrinter.logEnvironment(args);
        if (Arrays.asList(args).contains(CommandLineOptions.VERBOSE.getOption())) {
            JNLPRuntime.setDebug(true);
        }

        Install4JUtils.applicationVersion().ifPresent(v -> LOG.info("Starting OpenWebStart ControlPanel {}", v));

        Translator.addBundle("i18n");
        final DeploymentConfiguration config = new DeploymentConfiguration();;

        try {
            checkForOwsUserPropertiesFile();
            config.load();
        } catch (final ConfigurationException e) {
            DialogFactory.showErrorDialog(Translator.getInstance().translate("error.loadConfig"), e);
            JNLPRuntime.exit(-1);
        }

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
                    new Install4JUpdateHandler(UpdatePanelConfigConstants.getUpdateScheduleForSettings(config)).triggerPossibleUpdate();
                } catch (Exception e) {
                    LOG.error("Error in possible update process", e);
                }
            });
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (final Exception e) {
            LOG.error("Can not set look&feel", e);
        }

        JNLPRuntime.setDefaultDownloadIndicator(DOWNLOAD_INDICATOR);
        SwingUtils.invokeLater(() -> {
            final ControlPanel editor = new ControlPanel(config);
            editor.setVisible(true);
        });
    }

    public static void checkForOwsUserPropertiesFile() {
        if (Install4JUtils.installationDirectory().isPresent()) {
            File[] owsUserPropertiesFile = new File(Install4JUtils.installationDirectory().get()).listFiles(file -> file.getName().equalsIgnoreCase("ows.properties"));
            if (owsUserPropertiesFile != null && owsUserPropertiesFile.length > 0) {
                System.setProperty("owsUserPropertiesFilePath", owsUserPropertiesFile[0].getAbsolutePath());
            }
        }
    }
}
