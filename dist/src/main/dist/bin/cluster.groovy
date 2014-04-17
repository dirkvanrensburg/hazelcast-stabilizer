#!/usr/bin/env groovy

class Cluster {

    def COMMAND = "startmachines"
    def CLUSTER_SIZE = "small"
    def COACH_PORT = '8701'
    def config
    def STABILIZER_HOME
    def machineListFile = new File("machine_list.txt")

    List<String> privateIps = []

    Cluster() {
        def props = new Properties()

        new File("start.properties").withInputStream {
            stream -> props.load(stream)
        }
        config = new ConfigSlurper().parse(props)

        if (!machineListFile.exists()) machineListFile.createNewFile()
        machineListFile.text.eachLine { String line -> privateIps << line }

        STABILIZER_HOME = new File(Cluster.class.protectionDomain.codeSource.location.path).parentFile.parent
        echo "STABILIZER_HOME $STABILIZER_HOME"
    }

    void installCoach(String ip) {
        echo "=============================================================="
        echo "Installing Coach on ${ip}"
        echo "=============================================================="

        //updating the coach-hazelcast.xml file
        String members = ""
        privateIps.each { String memberIp -> members += "<member>$memberIp:$COACH_PORT</member>\n" }
        template "coach-hazelcast-template.xml", "coach-hazelcast.xml", "MEMBERS", members


        echo "Installing missing Java"
        //install java under Ubuntu.
        sshQuiet ip, "sudo apt-get update || true"
        sshQuiet ip, "sudo apt-get install -y openjdk-7-jdk || true"

        echo "Copying stabilizer files"
        scpToRemote ip, STABILIZER_HOME, ""
        //we need to override the hazelcast config file with the one we generated.
        scpToRemote ip, 'coach-hazelcast.xml', 'hazelcast-stabilizer-0.1-SNAPSHOT/conf/'

        echo "=============================================================="
        echo "Successfully installed Coach on ${ip}"
        echo "=============================================================="
    }


    void startCoaches() {
        echo "=============================================================="
        echo "Starting Coaches"
        echo "=============================================================="

        privateIps.each { String ip ->
            ssh ip, "killall -9 java || true"
        }

        privateIps.each { String ip ->
            ssh ip, "nohup hazelcast-stabilizer-0.1-SNAPSHOT/bin/coach > coach.out 2> coach.err < /dev/null &"
        }

        echo "=============================================================="
        echo "Coaches started"
        echo "=============================================================="
    }

    void installCoaches() {
        privateIps.each { String ip -> installCoach(ip) }
    }

    int calcSize(String sizeType) {
        switch (sizeType) {
            case "single": return 1
            case "tiny": return 2
            case "small": return 4
            case "medium": return 6
            case "large": return 10
            case "xlarge": return 20
            case "2xlarge": return 40
            case "3xlarge": return 100
            case "4xlarge": return 190
            default:
                println "Unknown size: sizeType"
                System.exit(1)
        }
    }

    void spawnMachines(String sizeType) {
        int instanceCount = calcSize(sizeType)

        machineListFile.write("")
        privateIps.clear()

        echo "echo Starting ${instanceCount} ${config.INSTANCE_TYPE} machines"

        def output = """ec2-run-instances \
        --availability-zone ${config.AVAILABILITY_ZONE} \
        --instance-type ${config.INSTANCE_TYPE} \
        --instance-count $instanceCount \
        --group ${config.SECURITY_GROUP} \
        --key ${config.KEY_PAIR} \
        ${config.AMI}""".execute().text

        echo "=============================================================="
        echo output
        echo "=============================================================="

        def ids = []
        output.eachLine { String line, count ->
            if (line.startsWith("INSTANCE")) {
                def id = line.split()[1]
                ids << id
            }
        }

        awaitStartup(ids)

        echo "=============================================================="
        echo "Successfully started ${instanceCount} ${config.INSTANCE_TYPE} machines "
        echo "=============================================================="

        initPrivateIps(ids)

        echo "Coaches started"
        privateIps.each { String ip -> println "--  $ip" }
    }


    void initManagerFile() {
        String members = ""
        privateIps.each { String memberIp -> members += "<address>$memberIp:$COACH_PORT</address>\n" }
        template "manager-hazelcast-template.xml", "manager-hazelcast.xml", "MEMBERS", members
    }

    void initPrivateIps(List<String> ids) {
        def x = "ec2-describe-instances".execute().text
        x.eachLine { String line, count ->
            def columns = line.split()
            if ("INSTANCE" == columns[0]) {
                def id = columns[1]
                if (ids.contains(id)) {
                    privateIps << columns[14]
                }
            }
        }

        machineListFile.text = ""
        privateIps.each { String ip ->
            machineListFile.text += "$ip\n"
        }
    }

    void awaitStartup(List<String> ids) {
        def remainingIds = ids.clone()

        for (int k = 1; k < 600; k++) {
            def lines = "ec2-describe-instance-status".execute().text.split("\n")
            echo "Status scan $k"
            for (int l = 0; l < (lines.length / 3); l++) {
                def instanceLine = lines[l * 3].split()
                def systemStatusLine = lines[l * 3 + 1].split()
                def instanceStatusLine = lines[l * 3 + 2].split()

                def id = instanceLine[1]
                if (remainingIds.contains(id)) {
                    def status = instanceStatusLine[2]
                    def started = status == "passed"
                    if (started) {
                        remainingIds.remove(id)
                    } else {
                        println "    $id $status"
                    }
                }

                if (remainingIds.size == 0) return
            }
        }

        echo "Timeout waiting for all instances to start, failed instances:"
        echo remainingIds
        System.exit(1)
    }

    def downloadArtifacts() {
        echo "=============================================================="
        echo "Download artifacts"
        echo "=============================================================="

        privateIps.each {String ip ->
            echo "Downoading from $ip"
            bash """rsync -av -e "ssh -i ${config.LICENSE} -o StrictHostKeyChecking=no" \
                ${config.USER}@$ip:hazelcast-stabilizer-0.1-SNAPSHOT/gym/ \
                ${STABILIZER_HOME}/gym"""
        }

        echo "=============================================================="
        echo "Finished Downloading Artifacts"
        echo "=============================================================="
    }

    void terminate(){
        echo "=============================================================="
        echo "Terminating cluster members"
        echo "=============================================================="

        def x = "ec2-describe-instances".execute().text
        def ids = ""
        x.eachLine { String line, count ->
            def columns = line.split()
            if ("INSTANCE" == columns[0]) {
                def id = columns[1]
                def ip = columns[14]
                if(privateIps.contains(ip)){
                    ids += "$id "
                }
            }
        }



        def output = "ec2kill $ids".execute().text
        echo output

        machineListFile.write("")

        echo "=============================================================="
        echo "Finished Terminating Cluster members"
        echo "=============================================================="
    }

    void template(String templateFile, String resultFile, String token, String replacement) {
        String cfg = new File(templateFile).text.replace(token, replacement);
        def file = new File(resultFile)
        file.createNewFile()
        file.text = cfg
    }

    void bash(String command) {
        def sout = new StringBuffer(), serr = new StringBuffer()

        // create a process for the shell
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        Process shell = pb.start();
        shell.consumeProcessOutput(sout, serr)

        // wait for the shell to finish and get the return code
        int shellExitStatus = shell.waitFor();
        if (shellExitStatus != 0) {
            echo "Failed to execute [$command]"
            println "out> $sout err> $serr"
            System.exit 1
        }
    }

    void scpToRemote(String ip, String src, String target) {
        String command = "scp -i ${config.LICENSE} -r -o StrictHostKeyChecking=no $src ${STABILIZER_HOME} ${config.USER}@$ip:$target"
        bash(command)
    }

    void ssh(String ip, String command) {
        String sshCommand = "ssh -i ${config.LICENSE} -q -o StrictHostKeyChecking=no ${config.USER}@$ip \"$command\""
        bash sshCommand
    }

    void sshQuiet(String ip, String command) {
        String sshCommand = "ssh -i ${config.LICENSE} -q -o StrictHostKeyChecking=no ${config.USER}@$ip \"$command\" || true"
        bash sshCommand
    }

    void echo(Object s) {
        println s
    }
}

def cli = new CliBuilder(
        usage: 'cluster [options]',
        header: '\nAvailable options (use -h for help):\n',
        stopAtNonOption: false)
cli.with {
    h(longOpt: 'help', 'print this message')
    r(longOpt: 'restart', ' Restarts all coaches')
    d(longOpt: 'download', 'Downloads the logs')
    s(longOpt: 'scale', args: 1, 'Scales the cluster')
    t(longOpt: 'terminate','Terminate all members in the cluster')
}

OptionAccessor opt = cli.parse(args)

if (!opt) {
    println "Failure parsing options"
    System.exit 1
    return
}

if (opt.h) {
    cli.usage()
    System.exit 0
}

if (opt.r) {
    def cluster = new Cluster()
    cluster.startCoaches()
    System.exit 0
}

if (opt.d) {
    def cluster = new Cluster()
    cluster.downloadArtifacts()
    System.exit 0
}

if (opt.t) {
    def cluster = new Cluster()
    cluster.terminate()
    System.exit 0
}

if (opt.s) {
    def cluster = new Cluster()

    String sizeType = opt.s
    cluster.spawnMachines(sizeType)
    cluster.initManagerFile()
    cluster.installCoaches()
    cluster.startCoaches()
    System.exit 0
}

