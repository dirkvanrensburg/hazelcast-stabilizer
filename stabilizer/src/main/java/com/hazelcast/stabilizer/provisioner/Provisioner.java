package com.hazelcast.stabilizer.provisioner;

import com.google.common.base.Predicate;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.stabilizer.Utils;
import com.hazelcast.stabilizer.agent.AgentRemoteService;
import com.hazelcast.stabilizer.agent.workerjvm.WorkerJvmManager;
import com.hazelcast.stabilizer.common.AgentAddress;
import com.hazelcast.stabilizer.common.AgentsFile;
import com.hazelcast.stabilizer.common.StabilizerProperties;
import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilderSpec;
import org.jclouds.logging.log4j.config.Log4JLoggingModule;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.scriptbuilder.statements.login.AdminAccess;
import org.jclouds.sshj.config.SshjSshClientModule;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.stabilizer.Utils.appendText;
import static com.hazelcast.stabilizer.Utils.getVersion;
import static com.hazelcast.stabilizer.Utils.secondsToHuman;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.jclouds.compute.config.ComputeServiceProperties.POLL_INITIAL_PERIOD;
import static org.jclouds.compute.config.ComputeServiceProperties.POLL_MAX_PERIOD;

//https://jclouds.apache.org/start/compute/ good read
//https://github.com/jclouds/jclouds-examples/blob/master/compute-basics/src/main/java/org/jclouds/examples/compute/basics/MainApp.java
//https://github.com/jclouds/jclouds-examples/blob/master/minecraft-compute/src/main/java/org/jclouds/examples/minecraft/NodeManager.java
public class Provisioner {
    private final static ILogger log = Logger.getLogger(Provisioner.class.getName());

    public final StabilizerProperties props = new StabilizerProperties();

    private final static String STABILIZER_HOME = Utils.getStablizerHome().getAbsolutePath();
    private File agentsFile = new File("agents.txt");
    //big number of threads, but they are used to offload ssh tasks. So there is no load on this machine..
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    private final List<AgentAddress> addresses = Collections.synchronizedList(new LinkedList<AgentAddress>());
    private Bash bash;
    private HazelcastJars hazelcastJars;

    public Provisioner() {
    }

    void init() throws Exception {
        if (!agentsFile.exists()) {
            agentsFile.createNewFile();
        }
        addresses.addAll(AgentsFile.load(agentsFile));
        bash = new Bash(props);
        hazelcastJars = new HazelcastJars(bash, props.get("HAZELCAST_VERSION_SPEC", "outofthebox"));
    }

    void installAgent(String ip) {
        //first we remove the old lib files to prevent different versions of the same jar to bite us.
        bash.sshQuiet(ip, format("rm -fr hazelcast-stabilizer-%s/lib", getVersion()));

        //then we copy the stabilizer directory
        bash.scpToRemote(ip, STABILIZER_HOME, "");

        String versionSpec = props.get("HAZELCAST_VERSION_SPEC", "outofthebox");

        if (!versionSpec.equals("outofthebox")) {
            //remove the hazelcast jars, they will be copied from the 'hazelcastJarsDir'.
            bash.ssh(ip, format("rm hazelcast-stabilizer-%s/lib/hazelcast-*.jar", getVersion()));

            if (!versionSpec.endsWith("none")) {
                //copy the actual hazelcast jars that are going to be used by the worker.
                bash.scpToRemote(ip, hazelcastJars.getAbsolutePath() + "/*.jar", format("hazelcast-stabilizer-%s/lib", getVersion()));
            }
        }
    }

    public void startAgents() {
        echoImportant("Starting %s Agents", addresses.size());

        for (AgentAddress address : addresses) {
            echo("Killing Agent %s", address.publicAddress);
            bash.ssh(address.publicAddress, "killall -9 java || true");
        }

        for (AgentAddress address : addresses) {
            echo("Starting Agent %s", address.publicAddress);
            bash.ssh(address.publicAddress, format("nohup hazelcast-stabilizer-%s/bin/agent > agent.out 2> agent.err < /dev/null &", getVersion()));
        }

        echoImportant("Successfully started %s Agents", addresses.size());
    }

    void startAgent(String ip) {
        bash.ssh(ip, "killall -9 java || true");
        bash.ssh(ip, format("nohup hazelcast-stabilizer-%s/bin/agent > agent.out 2> agent.err < /dev/null &", getVersion()));
    }

    void killAgents() {
        echoImportant("Killing %s Agents", addresses.size());

        for (AgentAddress address : addresses) {
            echo("Killing Agent, %s", address.publicAddress);
            bash.ssh(address.publicAddress, "killall -9 java || true");
        }

        echoImportant("Successfully killed %s Agents", addresses.size());
    }

    public void restart() {
        hazelcastJars.prepare();
        for (AgentAddress address : addresses) {
            installAgent(address.publicAddress);
        }
    }

    public void scale(int size) throws Exception {
        int delta = size - addresses.size();
        if (delta == 0) {
            echo("Current number of machines: " + addresses.size());
            echo("Desired number of machines: " + (addresses.size() + delta));
            echo("Ignoring spawn machines, desired number of machines already exists.");
        } else if (delta < 0) {
            terminate(-delta);
        } else {
            scaleUp(delta);
        }
    }

    private int[] inboundPorts() {
        List<Integer> ports = new ArrayList<Integer>();
        ports.add(22);
        //todo:the following 2 ports should not be needed
        ports.add(443);
        ports.add(80);
        ports.add(AgentRemoteService.PORT);
        ports.add(WorkerJvmManager.PORT);
        for (int k = 5701; k < 5751; k++) {
            ports.add(k);
        }

        int[] result = new int[ports.size()];
        for (int k = 0; k < result.length; k++) {
            result[k] = ports.get(k);
        }
        return result;
    }

    private void scaleUp(int delta) throws Exception {
        echoImportant("Provisioning %s %s machines", delta, props.get("CLOUD_PROVIDER"));
        echo("Current number of machines: " + addresses.size());
        echo("Desired number of machines: " + (addresses.size() + delta));
        String groupName = props.get("GROUP_NAME", "stabilizer-agent");
        echo("GroupName:"+groupName);

        echo("Machine spec: "+props.get("MACHINE_SPEC"));

        long startTimeMs = System.currentTimeMillis();

        String jdkFlavor = props.get("JDK_FLAVOR");
        if ("outofthebox".equals(jdkFlavor)) {
            log.info("JDK Spec: outofthebox");
        } else {
            log.info(format("JDK_SPEC: %s %s", jdkFlavor, props.get("JDK_VERSION")));
        }

        hazelcastJars.prepare();

        ComputeService compute = getComputeService();

        echo("Created compute");

        Template template = buildTemplate(compute);

        echo("Creating nodes");

        Set<Future> futures = new HashSet<Future>();
        echo("Created machines, waiting for startup (can take a few minutes)");

        for (int batch : calcBatches(delta)) {

            Set<? extends NodeMetadata> nodes = compute.createNodesInGroup(groupName, batch, template);

            for (NodeMetadata node : nodes) {
                String privateIpAddress = node.getPrivateAddresses().iterator().next();
                String publicIpAddress = node.getPublicAddresses().iterator().next();

                echo("\t" + publicIpAddress + " LAUNCHED");
                appendText(publicIpAddress + "," + privateIpAddress + "\n", agentsFile);

                AgentAddress address = new AgentAddress(publicIpAddress, privateIpAddress);
                addresses.add(address);
            }

            for (NodeMetadata node : nodes) {
                String publicIpAddress = node.getPublicAddresses().iterator().next();
                Future f = executor.submit(new InstallNodeTask(publicIpAddress));
                futures.add(f);
            }
        }

        for (Future f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                log.severe("Failed provision", e);
                System.exit(1);
            }
        }

        long durationMs = System.currentTimeMillis() - startTimeMs;
        echo("Duration: " + secondsToHuman(TimeUnit.MILLISECONDS.toSeconds(durationMs)));
        echoImportant(format("Successfully provisioned %s %s machines",
                delta, props.get("CLOUD_PROVIDER")));
    }

    private Template buildTemplate(ComputeService compute) {
        Template template = compute.templateBuilder()
                .from(TemplateBuilderSpec.parse(props.get("MACHINE_SPEC")))
                .build();

        echo("Created template");

        template.getOptions()
                .inboundPorts(inboundPorts())
                .runScript(AdminAccess.standard())
                .securityGroups(props.get("SECURITY_GROUP"));

        return template;
    }

    private class InstallNodeTask implements Runnable {
        private final String ip;

        InstallNodeTask(String ip) {
            this.ip = ip;
        }

        @Override
        public void run() {
            //install java if needed
            if (!"outofthebox".equals(props.get("JDK_FLAVOR"))) {
                bash.ssh(ip, "touch install-java.sh");
                bash.ssh(ip, "chmod +x install-java.sh");
                bash.scpToRemote(ip, getJavaInstallScript().getAbsolutePath(), "install-java.sh");
                bash.ssh(ip, "bash install-java.sh");
                echo("\t" + ip + " JAVA INSTALLED");
            }

            installAgent(ip);
            echo("\t" + ip + " STABILIZER AGENT INSTALLED");

            startAgent(ip);
            echo("\t" + ip + " STABILIZER AGENT STARTED");
        }
    }

    private int[] calcBatches(int size) {
        List<Integer> batches = new LinkedList<Integer>();
        int batchSize = Integer.parseInt(props.get("CLOUD_BATCH_SIZE"));
        while (size > 0) {
            int x = size >= batchSize ? batchSize : size;
            batches.add(x);
            size -= x;
        }

        int[] result = new int[batches.size()];
        for (int k = 0; k < result.length; k++) {
            result[k] = batches.get(k);
        }
        return result;
    }

    private ComputeService getComputeService() {
        //http://javadocs.jclouds.cloudbees.net/org/jclouds/compute/config/ComputeServiceProperties.html
        Properties overrides = new Properties();
        overrides.setProperty(POLL_INITIAL_PERIOD, props.get("CLOUD_POLL_INITIAL_PERIOD"));
        overrides.setProperty(POLL_MAX_PERIOD, props.get("CLOUD_POLL_MAX_PERIOD"));

        String credentials = props.get("CLOUD_CREDENTIAL");
        File file = new File(credentials);
        if (file.exists()) {
            credentials = Utils.fileAsText(file);
        }

        return ContextBuilder.newBuilder(props.get("CLOUD_PROVIDER"))
                .overrides(overrides)
                .credentials(props.get("CLOUD_IDENTITY"), credentials)
                .modules(asList(new SLF4JLoggingModule(), new SshjSshClientModule()))
                .buildView(ComputeServiceContext.class)
                .getComputeService();
    }

    private File getJavaInstallScript() {
        String flavor = props.get("JDK_FLAVOR");
        String version = props.get("JDK_VERSION");

        String script = "jdk-" + flavor + "-" + version + "-64.sh";
        File scriptDir = new File(STABILIZER_HOME, "jdk-install");
        return new File(scriptDir, script);
    }

    public void download() {
        echoImportant("Download artifacts of %s machines", addresses.size());

        bash.bash("mkdir -p workers");

        for (AgentAddress address : addresses) {
            echo("Downloading from %s", address.publicAddress);

            String syncCommand = format("rsync  -av -e \"ssh %s\" %s@%s:hazelcast-stabilizer-%s/workers .",
                    props.get("SSH_OPTIONS"), props.get("USER"), address.publicAddress, getVersion());

            bash.bash(syncCommand);
        }

        echoImportant("Finished Downloading Artifacts of %s machines", addresses.size());
    }

    public void clean() {
        echoImportant("Cleaning worker homes of %s machines", addresses.size());

        for (AgentAddress address : addresses) {
            echo("Cleaning %s", address.publicAddress);
            bash.ssh(address.publicAddress, format("rm -fr hazelcast-stabilizer-%s/workers/*", getVersion()));
        }

        echoImportant("Finished cleaning worker homes of %s machines", addresses.size());
    }

    public void terminate() {
        terminate(Integer.MAX_VALUE);
    }

    public void terminate(int count) {
        if (count > addresses.size()) {
            count = addresses.size();
        }

        echoImportant(format("Terminating %s %s machines (can take some time)", count, props.get("CLOUD_PROVIDER")));
        echo("Current number of machines: " + addresses.size());
        echo("Desired number of machines: " + (addresses.size() - count));

        long startMs = System.currentTimeMillis();

        for (int batchSize : calcBatches(count)) {
            final Map<String, AgentAddress> terminateMap = new HashMap<String, AgentAddress>();
            for (AgentAddress address : addresses.subList(0, batchSize)) {
                terminateMap.put(address.publicAddress, address);
            }

            ComputeService computeService = getComputeService();
            computeService.destroyNodesMatching(
                    new Predicate<NodeMetadata>() {
                        @Override
                        public boolean apply(NodeMetadata nodeMetadata) {
                            for (String publicAddress : nodeMetadata.getPublicAddresses()) {
                                AgentAddress address = terminateMap.remove(publicAddress);
                                if (address != null) {
                                    echo(format("\t%s Terminating", publicAddress));
                                    addresses.remove(address);
                                    return true;
                                }
                            }
                            return false;
                        }
                    }
            );
        }

        log.info("Updating " + agentsFile.getAbsolutePath());

        AgentsFile.save(agentsFile, addresses);

        long durationSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startMs);
        echo("Duration: " + secondsToHuman(durationSeconds));
        echoImportant("Finished terminating %s %s machines, %s machines remaining.",
                count, props.get("CLOUD_PROVIDER"), addresses.size());
    }


    private void echo(String s, Object... args) {
        log.info(s == null ? "null" : String.format(s, args));
    }

    private void echoImportant(String s, Object... args) {
        echo("==============================================================");
        echo(s, args);
        echo("==============================================================");
    }

    public static void main(String[] args) {
        log.info("Hazelcast Stabilizer Provisioner");
        log.info(format("Version: %s", getVersion()));
        log.info(format("STABILIZER_HOME: %s", STABILIZER_HOME));

        try {
            Provisioner provisioner = new Provisioner();
            ProvisionerCli cli = new ProvisionerCli(provisioner);
            cli.run(args);


            System.exit(0);
        } catch (Throwable e) {
            log.severe(e);
            System.exit(1);
        }
    }
}
