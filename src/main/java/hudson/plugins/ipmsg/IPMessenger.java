package hudson.plugins.ipmsg;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.Mailer;
import hudson.util.FormValidation;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class IPMessenger extends Notifier {
    
    protected static final Logger LOGGER = Logger.getLogger(IPMessenger.class.getName());
    private static final Charset CS = Charset.forName("Windows-31J");
    private static final Integer PROTOCOL_VER = Integer.valueOf(1);
    private static final Integer SEND_MSG_FLG = Integer.valueOf(0x20);
    private static final String HOSTNAME;
    static {
        try {
            HOSTNAME = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }
    private final String hostnames;
    
    @DataBoundConstructor
    public IPMessenger(String hostnames) {
        this.hostnames = hostnames == null ? "" : hostnames.trim();
    }
    
    public String getHostnames() {
        return hostnames;
    }
    
    /* (non-Javadoc)
     * @see hudson.tasks.BuildStep#getRequiredMonitorService()
     */
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {
        Result result = build.getResult();
        DescriptorImpl desc = getDescriptor();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(desc.port());
            byte[] message =
                String.format(
                    "%s:%s:%s:%s:%s:%s",
                    PROTOCOL_VER,
                    Long.valueOf(System.currentTimeMillis() / 1000),
                    desc.nickname,
                    HOSTNAME,
                    SEND_MSG_FLG,
                    "["
                        + result.toString()
                        + "] : "
                        + build.getFullDisplayName()
                        + "\r\n"
                        + Mailer.descriptor().getUrl()
                        + build.getUrl()).getBytes(CS);
            String[] hosts = hostnames.split("(?m:\\s*[,\\n\\r]\\s*)");
            for (String host : hosts) {
                if (!host.equals("")) {
                    try {
                        socket.send(new DatagramPacket(message, message.length,
                                InetAddress.getByName(host), desc.port()));
                    } catch (UnknownHostException e) {
                        LOGGER.warning(e.getMessage() + ": " + host);
                    }
                }
            }
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return true;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }
    
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private static final Pattern EMPTY_HOSTNAMES = Pattern.compile("(?m:(,|\\s)+)");
        
        private int port = 2425;
        private String nickname = "Jenkins";
        
        public int port() {
            return port;
        }
        
        public String nickname() {
            return nickname;
        }
        
        /* (non-Javadoc)
         * @see hudson.tasks.BuildStepDescriptor#isApplicable(java.lang.Class)
         */
        @SuppressWarnings("rawtypes")
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public FormValidation doCheckPort(@QueryParameter String value) throws IOException,
                ServletException {
            try {
                int p = Integer.parseInt(value);
                if (p < 1 || p > 65535) {
                    return FormValidation.error("invalid port number");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("invalid port number");
            }
        }

        public FormValidation doCheckNickname(@QueryParameter String value) throws IOException,
                ServletException {
            if (value == null || (value = value.trim()).equals("")) {
                return FormValidation.error("nickname must be empty");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckHostnames(@QueryParameter String value) throws IOException,
                ServletException {
            if (value == null
                || (value = value.trim()).equals("")
                || EMPTY_HOSTNAMES.matcher(value).matches()) {
                return FormValidation.error("hostnames must be empty");
            }
            return FormValidation.ok();
        }

        /* (non-Javadoc)
         * @see hudson.model.Descriptor#getDisplayName()
         */
        @Override
        public String getDisplayName() {
            return "IPMessenger Notifier";
        }
        
        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            port = formData.getInt("port");
            nickname = formData.getString("nickname");
            save();
            return super.configure(req, formData);
        }
    }
}
