/*
 * NoiseModelling is an open-source tool designed to produce environmental noise maps on very large urban areas. It can be used as a Java library or be controlled through a user-friendly web interface.
 *
 * This version is developed by the DECIDE team from the Lab-STICC (CNRS) and by the Mixt Research Unit in Environmental Acoustics (Université Gustave Eiffel).
 * <http://noise-planet.org/noisemodelling.html>
 *
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 *
 * Contact: contact@noise-planet.org
 *
 */


package org.noise_planet.plamade.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jcraft.jsch.*;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import groovy.sql.Sql;
import org.apache.commons.compress.utils.CountingInputStream;
import org.h2.util.OsgiDataSourceFactory;
import org.h2gis.api.EmptyProgressVisitor;
import org.h2gis.api.ProgressVisitor;
import org.h2gis.functions.factory.H2GISFunctions;
import org.h2gis.functions.io.shp.SHPWrite;
import org.h2gis.utilities.JDBCUtilities;
import org.noise_planet.noisemodelling.jdbc.PointNoiseMap;
import org.noise_planet.plamade.config.DataBaseConfig;
import org.noise_planet.plamade.config.SlurmConfig;
import org.osgi.service.jdbc.DataSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * This process hold one instance of NoiseModelling
 * @author Nicolas Fortin, Université Gustave Eiffel
 */
public class NoiseModellingInstance implements RunnableFuture<String> {
    public enum JOB_STATES {
        QUEUED,
        RUNNING,
        FAILED,
        CANCELED,
        COMPLETED
    }
    public static final String RESULT_DIRECTORY_NAME = "results";
    public static final String POST_PROCESS_RESULT_DIRECTORY_NAME = "results_post";

    // https://curc.readthedocs.io/en/latest/running-jobs/squeue-status-codes.html
    public static final SlurmJobKnownStatus[] SLURM_JOB_KNOWN_STATUSES = new SlurmJobKnownStatus[]{
            new SlurmJobKnownStatus("COMPLETED", true), // The job has completed successfully.
            new SlurmJobKnownStatus("COMPLETING", false), // The job is finishing but some processes are still active.
            new SlurmJobKnownStatus("FAILED", true), // The job terminated with a non-zero exit code and failed to execute.
            new SlurmJobKnownStatus("PENDING", false), // The job is waiting for resource allocation. It will eventually run.
            new SlurmJobKnownStatus("PREEMPTED", false), // The job was terminated because of preemption by another job.
            new SlurmJobKnownStatus("RUNNING", false), // The job currently is allocated to a node and is running.
            new SlurmJobKnownStatus("SUSPENDED", false), // A running job has been stopped with its cores released to other jobs.
            new SlurmJobKnownStatus("STOPPED", true) // A running job has been stopped with its cores retained.
    };

    private static final Logger logger = LoggerFactory.getLogger(NoiseModellingInstance.class);
    Configuration configuration;
    DataSource nmDataSource;
    DataSource plamadeDataSource;
    boolean isRunning = false;
    private static final int SFTP_TIMEOUT = 60000;
    private static final int POLL_SLURM_STATUS_TIME = 40000;

    private static final String BATCH_FILE_NAME = "noisemodelling_batch.sh";

    public NoiseModellingInstance(Configuration configuration, DataSource plamadeDataSource) {
        this.configuration = configuration;
        this.plamadeDataSource = plamadeDataSource;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public static DataSource createDataSource(String user, String password, String dbDirectory, String dbName, boolean debug) throws SQLException {
        // Create H2 memory DataSource
        org.h2.Driver driver = org.h2.Driver.load();
        OsgiDataSourceFactory dataSourceFactory = new OsgiDataSourceFactory(driver);
        Properties properties = new Properties();
        String databasePath = "jdbc:h2:" + new File(dbDirectory, dbName).getAbsolutePath();
        properties.setProperty(DataSourceFactory.JDBC_URL, databasePath);
        properties.setProperty(DataSourceFactory.JDBC_USER, user);
        properties.setProperty(DataSourceFactory.JDBC_PASSWORD, password);
        if(debug) {
            properties.setProperty("TRACE_LEVEL_FILE", "3"); // enable debug
        }
        DataSource dataSource = dataSourceFactory.createDataSource(properties);
        // Init spatial ext
        try (Connection connection = dataSource.getConnection()) {
            if(!JDBCUtilities.tableExists(connection, "GEOMETRY_COLUMNS")) {
                H2GISFunctions.load(connection);
            }
        }
        return dataSource;

    }

    public void importData(Connection nmConnection, ProgressVisitor progressVisitor) throws SQLException, IOException {

        GroovyShell shell = new GroovyShell();
        Script extractDepartment= shell.parse(new File("../script_groovy", "s1_Extract_Department.groovy"));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("databaseUser", configuration.getDataBaseConfig().user);
        inputs.put("databasePassword", configuration.getDataBaseConfig().password);
        inputs.put("fetchDistance", 1000);
        inputs.put("inseeDepartment", configuration.getInseeDepartment());
        inputs.put("progressVisitor", progressVisitor);
        inputs.put("inputServer", "cloud");

        Object result = extractDepartment.invokeMethod("exec", new Object[] {nmConnection, inputs});

        if(result instanceof String) {
            try (OutputStreamWriter f = new OutputStreamWriter(new FileOutputStream(new File(configuration.getWorkingDirectory(), "import.html")), StandardCharsets.UTF_8)) {
                f.write(result.toString());
            }
        }
    }

    public void makeGrid(Connection nmConnection) throws SQLException, IOException {
        GroovyShell shell = new GroovyShell();
        Script receiversGrid= shell.parse(new File("../script_groovy", "s2_Receivers_Grid.groovy"));
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("confId", configuration.getConfigurationId());

        Object result = receiversGrid.invokeMethod("exec", new Object[] {nmConnection, inputs});
    }

    public void makeEmission(Connection nmConnection) throws SQLException, IOException {
        GroovyShell shell = new GroovyShell();
        Script receiversGrid= shell.parse(new File("../script_groovy", "s3_Emission_Noise_level.groovy"));
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("confId", configuration.getConfigurationId());

        Object result = receiversGrid.invokeMethod("exec", new Object[] {nmConnection, inputs});
    }


    public Object RoadNoiselevel(Connection nmConnection, ProgressVisitor progressVisitor) throws SQLException, IOException {
        GroovyShell shell = new GroovyShell();
        Script process= shell.parse(new File("../script_groovy", "s4_Road_Noise_level.groovy"));
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("confId", configuration.getConfigurationId());
        inputs.put("workingDirectory", configuration.getWorkingDirectory());
        inputs.put("progressVisitor", progressVisitor);
        inputs.put("outputToSql", false);
        return process.invokeMethod("exec", new Object[] {nmConnection, inputs});
    }

    public static JsonNode convertToJson(List<ArrayList<PointNoiseMap.CellIndex>> cells) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode rootNode = mapper.createArrayNode();
        int nodeIndex = 0;
        for(ArrayList<PointNoiseMap.CellIndex> node : cells) {
            for(PointNoiseMap.CellIndex cellIndex : node) {
                ObjectNode cellNode = mapper.createObjectNode();
                cellNode.put("nodeIndex", nodeIndex);
                cellNode.put("latitudeIndex", cellIndex.getLatitudeIndex());
                cellNode.put("longitudeIndex", cellIndex.getLongitudeIndex());
                rootNode.add(cellNode);
            }
            nodeIndex++;
        }
        return rootNode;
    }

    private static class JobElement implements Comparable<JobElement> {
        public List<String> UUEID = new ArrayList<>();
        public double totalRoadLength = 0;


        @Override
        public int compareTo(JobElement o) {
            return Double.compare(o.totalRoadLength, totalRoadLength);
        }
    }

    public static Integer asInteger(Object v) {
        if(v instanceof Number) {
            return ((Number)v).intValue();
        } else {
            return null;
        }
    }

    public static class ShapeFileFilter implements FilenameFilter {
        String prefix;
        String suffix;

        public ShapeFileFilter(String prefix, String suffix) {
            this.prefix = prefix;
            this.suffix = suffix;
        }

        @Override
        public boolean accept(File dir, String name) {
            if(!name.toLowerCase(Locale.ROOT).endsWith("shp")) {
                return false;
            }
            if(!name.startsWith(prefix)) {
                return false;
            }
            return suffix.isEmpty() || name.indexOf(suffix, prefix.length()) != -1;
        }
    }

    /**
     * Merge shape files with the same file name
     * prefix[NUMJOB]suffix[TABLENAME].shp
     * @param connection database connection
     * @param folder folder that contains shp files
     * @param prefix common prefix before the number
     * @param suffix common suffix after the number
     * @return Tables created
     */
    public static List<String> mergeShapeFiles(Connection connection,String folder, String prefix, String suffix) throws SQLException {
        String extension = ".shp";
        File workingFolder = new File(folder);
        // Search files that match expected file name format
        String[] files = workingFolder.list(new ShapeFileFilter(prefix, suffix));
        if(files == null) {
            return new ArrayList<>();
        }
        Map<String, ArrayList<String>> tableNameToFileNames = new HashMap<>();
        for(String fileName : files) {
            // Extract tableName from file name
            String tableName = fileName.substring(fileName.indexOf(suffix, prefix.length()) + suffix.length(),
                    fileName.length() - extension.length());
            ArrayList<String> fileNames;
            if(!tableNameToFileNames.containsKey(tableName)) {
                fileNames = new ArrayList<>();
                tableNameToFileNames.put(tableName, fileNames);
            } else {
                fileNames = tableNameToFileNames.get(tableName);
            }
            fileNames.add(fileName);
        }
        List<String> createdTables = new ArrayList<>();
        for(Map.Entry<String, ArrayList<String>> entry : tableNameToFileNames.entrySet()) {
            try(Statement st = connection.createStatement()) {
                ArrayList<String> fileNames = entry.getValue();
                // Copy the first table as a new table
                if(!fileNames.isEmpty()) {
                    String fileName = fileNames.remove(0);
                    String tableName = fileName.substring(0, fileName.length() - extension.length()).toUpperCase(Locale.ROOT);
                    st.execute("DROP TABLE IF EXISTS " + tableName);
                    st.execute("CALL FILE_TABLE('" + new File(workingFolder, fileName).getAbsolutePath() + "','" + tableName + "');");
                    st.execute("DROP TABLE IF EXISTS " + entry.getKey().toUpperCase(Locale.ROOT));
                    st.execute("CREATE TABLE " + entry.getKey().toUpperCase(Locale.ROOT) + " AS SELECT * FROM " + tableName);
                    createdTables.add(entry.getKey().toUpperCase(Locale.ROOT));
                    st.execute("DROP TABLE IF EXISTS " + tableName);
                }
                for(String fileName : fileNames) {
                    // Link to shp file into the database
                    String tableName = fileName.substring(0, fileName.length() - extension.length()).toUpperCase(Locale.ROOT);
                    st.execute("DROP TABLE IF EXISTS " + tableName);
                    st.execute("CALL FILE_TABLE('" + new File(workingFolder, fileName).getAbsolutePath() + "','" + tableName + "');");
                    // insert into the existing table
                    st.execute("INSERT INTO " + entry.getKey() + " SELECT * FROM " + tableName);
                    st.execute("DROP TABLE IF EXISTS " + tableName);
                }
            }
        }
        return createdTables;
    }






    public static void generateClusterConfig(Connection nmConnection, ProgressVisitor progressVisitor, int numberOfJobs, String workingDirectory) throws SQLException, IOException {
        // Sum UUEID roads length
        // because the length of the roads should be proportional with the work load
        // Can't have more jobs than UUEID
        Sql sql = new Sql(nmConnection);
        int numberOfUUEID = asInteger(sql.firstRow("SELECT COUNT(DISTINCT UUEID) CPT FROM ROADS").get("CPT"));
        numberOfJobs = Math.min(numberOfUUEID, numberOfJobs);
        // Distribute UUEID over numberOfJobs (least road length receive the next one
        List<JobElement> jobElementList = new ArrayList<>(numberOfJobs);
        for(int i=0; i < numberOfJobs; i++) {
            jobElementList.add(new JobElement());
        }
        try(ResultSet rs = nmConnection.createStatement().executeQuery("SELECT UUEID , SUM(st_length(the_geom)) weight  FROM ROADS r GROUP BY uueid ORDER BY WEIGHT DESC;")) {
            while(rs.next()) {
                jobElementList.get(jobElementList.size() - 1).UUEID.add(rs.getString("UUEID"));
                jobElementList.get(jobElementList.size() - 1).totalRoadLength+=rs.getDouble("WEIGHT");
                // Sort by road length
                Collections.sort(jobElementList);
            }
        }
        AtomicInteger nodeId = new AtomicInteger();
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode rootDoc = mapper.createArrayNode();
        // create lists so that each node have at least the quota length of roads (except the last one)
        for (JobElement jobElement : jobElementList) {
            ObjectNode nodeDoc = mapper.createObjectNode();
            rootDoc.add(nodeDoc);
            nodeDoc.put("nodeId", nodeId.getAndIncrement());
            nodeDoc.put("node_sum_length", jobElement.totalRoadLength);
            ArrayNode nodeUUEIDs = mapper.createArrayNode();
            nodeDoc.set("uueids", nodeUUEIDs);
            for(String uueid : jobElement.UUEID) {
                nodeUUEIDs.add(uueid);
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(workingDirectory, "cluster_config.json"), rootDoc);
    }


    public Object LoadNoiselevel(Connection nmConnection, ProgressVisitor progressVisitor) throws SQLException, IOException {
        GroovyShell shell = new GroovyShell();
        Script process= shell.parse(new File("../script_groovy", "s42_Load_Noise_level.groovy"));
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("confId", configuration.getConfigurationId());
        inputs.put("workingDirectory", configuration.getWorkingDirectory());
        inputs.put("progressVisitor", progressVisitor);
        return process.invokeMethod("exec", new Object[] {nmConnection, inputs});
    }

    public Object Isosurface(Connection nmConnection, ProgressVisitor progressVisitor) throws SQLException, IOException {
        GroovyShell shell = new GroovyShell();
        Script process= shell.parse(new File("../script_groovy", "s5_Isosurface.groovy"));
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("confId", configuration.getConfigurationId());
        inputs.put("workingDirectory", configuration.getWorkingDirectory());
        inputs.put("progressVisitor", progressVisitor);
        return process.invokeMethod("exec", new Object[] {nmConnection, inputs});
    }


    public Object Export(Connection nmConnection, ProgressVisitor progressVisitor) throws SQLException, IOException {
        GroovyShell shell = new GroovyShell();
        Script process= shell.parse(new File("../script_groovy", "s7_Export.groovy"));
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("confId", configuration.getConfigurationId());
        inputs.put("workingDirectory", configuration.getWorkingDirectory());
        inputs.put("progressVisitor", progressVisitor);
        inputs.put("databaseUser", configuration.getDataBaseConfig().user);
        inputs.put("databasePassword", configuration.getDataBaseConfig().password);
        inputs.put("batchSize", 1000);
        inputs.put("inputServer", "cloud");
        return process.invokeMethod("exec", new Object[] {nmConnection, inputs});
    }

    public static void pullFromSSH(ChannelSftp c, ProgressVisitor progressVisitor, String from, String to) throws IOException, SftpException {
        Vector v = c.ls(from);
        ProgressVisitor dirProg = progressVisitor.subProcess(v.size());
        File rootFolder = new File(to);
        if(!rootFolder.exists()) {
            logger.debug("mkdir " + to);
            if(!rootFolder.mkdir()) {
                return;
            }
        }
        for(Object e : v) {
            if(e instanceof ChannelSftp.LsEntry) {
                ChannelSftp.LsEntry sftpEntry = (ChannelSftp.LsEntry)e;
                File remoteFile = new File(from, sftpEntry.getFilename());
                File localFile = new File(to, sftpEntry.getFilename());
                if(sftpEntry.getAttrs().isDir()) {
                    if(!sftpEntry.getAttrs().isLink() && !sftpEntry.getFilename().startsWith(".")) {
                        pullFromSSH(c, dirProg, remoteFile.getAbsolutePath(), localFile.getAbsolutePath());
                    }
                } else {
                    logger.debug("get " + remoteFile.getAbsolutePath() + " " + localFile.getAbsolutePath());
                    c.get(remoteFile.getAbsolutePath(), localFile.getAbsolutePath());
                }
            }
            dirProg.endStep();
        }
    }
    public static void pushToSSH(ChannelSftp c, ProgressVisitor progressVisitor, String from, String to, boolean createSubDirectory) throws IOException, SftpException {
        // List All files
        File fromFile = new File(from);
        File parentFile = fromFile;
        if(createSubDirectory) {
            parentFile = fromFile.getParentFile();
        }
        List<Path> collectedPaths = new ArrayList<>();
        Files.walk(fromFile.toPath(), FileVisitOption.FOLLOW_LINKS).forEach(collectedPaths::add);
        ProgressVisitor subProg = progressVisitor.subProcess(collectedPaths.size());
        for(Path path : collectedPaths) {
            if(path.toFile().isDirectory()) {
                // do create directory
                Path destFolder = parentFile.toPath().relativize(path);
                File remoteFolder;
                if(to.isEmpty()) {
                    remoteFolder = new File(destFolder.toString());
                } else {
                    remoteFolder = new File(to, destFolder.toString());
                }
                if(!remoteFolder.toString().isEmpty()) {
                    logger.debug("mkdir " + remoteFolder);
                    if (c != null) {
                        try {
                            c.mkdir(remoteFolder.toString());
                        } catch (SftpException ex) {
                            // dir exist / ignore
                            logger.debug(ex.getLocalizedMessage());
                        }
                    }
                }
            } else {
                // transfer file
                Path dest = parentFile.toPath().relativize(path);
                File remoteFile;
                if(to.isEmpty()) {
                    remoteFile = new File(dest.toString());
                } else {
                    remoteFile = new File(to, dest.toString());
                }
                if(c != null) {
                    logger.debug("put " + path.toFile() + " " + remoteFile);
                    c.put(path.toFile().toString(), remoteFile.toString());
                }
            }
            subProg.endStep();
        }
    }

    private static final String[] names = {
            "ssh-dss",
            "ssh-rsa",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521"
    };

    protected static int name2type(String name){
        for(int i = 0; i < names.length; i++){
            if(names[i].equals(name)){
                return i + 1;
            }
        }
        return 6;
    }

    public void recurseMkDir(ChannelSftp c, String folder) throws SftpException {
        Path folderPath = new File(folder).toPath();
        int folderCount = 1;
        while(folderCount <= folderPath.getNameCount()) {
            Path firstFolder = folderPath.subpath(0, folderCount);
            Path parentPath;
            if(folderCount > 1) {
                parentPath = folderPath.subpath(0, folderCount - 1);
            } else {
                parentPath = new File(c.getHome()).toPath();
            }
            boolean foundFolder = false;
            try {
                Vector files = c.ls(parentPath.toString());
                for (Object oEntry : files) {
                    if (oEntry instanceof ChannelSftp.LsEntry) {
                        if (((ChannelSftp.LsEntry) oEntry).getFilename().equals(firstFolder.getFileName().toString())) {
                            foundFolder = true;
                            break;
                        }
                    }
                }
            } catch (SftpException ex) {
                logger.info("Error while checking folder \"" + parentPath + "\" content");
                throw ex;
            }
            if(!foundFolder) {
                try {
                    c.mkdir(firstFolder.toString());
                } catch (SftpException ex) {
                    logger.info("Error while trying to create folder \"" + firstFolder + "\"");
                    throw ex;
                }
            }
            folderCount++;
        }
    }

    public static Session openSshSession(SlurmConfig slurmConfig) throws JSchException {
        JSch jsch=new JSch();
        HostKeyRepository hkr = jsch.getHostKeyRepository();
        // Add host identification if not already in the repository
        if(hkr.getHostKey(slurmConfig.host, slurmConfig.serverKeyType).length == 0) {
            hkr.add(new HostKey(slurmConfig.host, name2type(slurmConfig.serverKeyType),
                    Base64.getDecoder().decode(slurmConfig.serverKey)), null);
        }

        jsch.addIdentity(slurmConfig.sshFile, slurmConfig.sshFilePassword);
        // Load known_hosts
        Session session = jsch.getSession(slurmConfig.user, slurmConfig.host, slurmConfig.port);
        try {
            session.connect(SFTP_TIMEOUT);
            logger.info("Successfully connected to the server " + slurmConfig.host);
        } catch (JSchException ex) {
            if(ex.getMessage().contains("UnknownHostKey") || ex.getMessage().contains("has been changed")) {
                // Connect but only print the expected key
                Session insecureSession = jsch.getSession(slurmConfig.user, slurmConfig.host, slurmConfig.port);
                insecureSession.setConfig("StrictHostKeyChecking", "no");
                insecureSession.connect(SFTP_TIMEOUT);
                HostKey hk=insecureSession.getHostKey();
                logger.error("Unknown host. Use the following configuration in config.yaml file if you trust this server:\n" +
                        " serverKeyType:\"" + hk.getType() + "\"\n serverKey:\""+hk.getKey()+"\"");
                insecureSession.disconnect();
            }
            throw ex;
        }
        return session;
    }

    public List<String> runCommand(Session session, String command) throws JSchException, IOException {
        return runCommand(session, command, true);
    }
    public List<String> runCommand(Session session, String command, boolean logResult) throws JSchException, IOException {
        return runCommand(session, command, logResult, new AtomicLong());
    }
    public List<String> runCommand(Session session, String command, boolean logResult, AtomicLong readBytes) throws JSchException, IOException {
        List<String> lines = new ArrayList<>();
        ChannelExec shell = (ChannelExec) session.openChannel("exec");
        try {
            shell.setCommand(command);
            shell.setInputStream(null);
            shell.setErrStream(System.err);

            InputStream in = shell.getInputStream();

            CountingInputStream countingInputStream = new CountingInputStream(in);
            InputStreamReader inputStreamReader = new InputStreamReader(countingInputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            // run command
            shell.connect(SFTP_TIMEOUT);

            while (true) {
                String line = bufferedReader.readLine();
                if (line != null) {
                    if(logResult) {
                        logger.info(line);
                    }
                    lines.add(line);
                } else {
                    if (shell.isClosed()) {
                        if(shell.getExitStatus() != 0) {
                            logger.error(String.format("Command %s \n exit-status: %d", command, shell.getExitStatus()));
                        }
                    } else {
                        logger.warn("Stream is closed but the channel is still open");
                    }
                    break;
                }
            }
            readBytes.addAndGet(countingInputStream.getBytesRead());
        } finally {
            shell.disconnect();
        }

        return lines;
    }


    public static List<SlurmJobStatus> parseSlurmStatus(List<String> commandOutput, int jobId) {
        List<SlurmJobStatus> slurmJobStatus = new ArrayList<>();
        for(String line : commandOutput) {
            line = line.trim();
            if(line.contains(BATCH_FILE_NAME.substring(0, 9))) {
                StringTokenizer stringTokenizer = new StringTokenizer(line, " ");
                List<String> columns = Collections.list(new StringTokenizer(line, " ")).stream()
                        .map(token -> (String) token)
                        .collect(Collectors.toList());
                String jobColumn = columns.get(0);
                int jobColumnId = Integer.parseInt(jobColumn.substring(0, jobColumn.lastIndexOf("_")));
                if(jobColumnId == jobId) {
                    int jobIndex = Integer.parseInt(jobColumn.substring(jobColumn.lastIndexOf("_") + 1));
                    String status = columns.get(6);
                    slurmJobStatus.add(new SlurmJobStatus(status, jobIndex));
                }
            }
        }


        return slurmJobStatus;
    }

    public static List<String> parseLSCommand(List<String> lines) {
        List<String> fileList = Collections.list(new StringTokenizer(String.join(" ", lines))).stream()
                .map(token -> (String) token)
                .collect(Collectors.toList());
        return fileList;
    }

    public void slurmInitAndStart(SlurmConfig slurmConfig, ProgressVisitor progressVisitor) throws JSchException, IOException, SftpException, InterruptedException {
        Session session = openSshSession(slurmConfig);
        try {
            Channel sftp;
            int slurmJobId = 14905486;
            sftp = session.openChannel("sftp");
            try {
                sftp.connect(SFTP_TIMEOUT);
                ChannelSftp c = (ChannelSftp) sftp;
                recurseMkDir(c, configuration.remoteJobFolder);
                // copy computation core
                File computationCoreFolder = new File(new File("").getAbsoluteFile().getParentFile(), "computation_core");
                logger.debug("Computation core folder: " + computationCoreFolder);
                String libFolder = new File(computationCoreFolder, "build" + File.separator + "libs").toString();
                pushToSSH(c, progressVisitor, libFolder, configuration.remoteJobFolder, true);
                // copy data
                pushToSSH(c, progressVisitor, configuration.workingDirectory, configuration.remoteJobFolder, false);
                // copy slurm file
                c.put(new File(computationCoreFolder, BATCH_FILE_NAME).toString(), new File(configuration.remoteJobFolder, BATCH_FILE_NAME).toString());
            } finally {
                sftp.disconnect();
            }
            logger.info("File transferred running computation core on cluster");
            // Run batch jobs
            List<String> output = runCommand(session,
                    String.format("cd %s && sbatch --array=0-%d %s", configuration.remoteJobFolder,
                            configuration.slurmConfig.maxJobs - 1, BATCH_FILE_NAME));
            // Parse Cluster Job identifier
            if(output.isEmpty()) {
                logger.info("Cannot read the job identifier");
                throw new IOException("No output in ssh command");
            }
            for(String line : output) {
                line = line.trim();
                if(line.startsWith("Submitted batch job")) {
                    slurmJobId = Integer.parseInt(line.substring(line.lastIndexOf(" ")).trim());
                    break;
                }
            }
            if(slurmJobId == -1) {
                logger.info("Cannot read the job identifier");
                throw new IOException("Not expected output in ssh command");
            }
            try (Connection connection = plamadeDataSource.getConnection()) {
                PreparedStatement st = connection.prepareStatement("UPDATE JOBS SET SLURM_JOB_ID = ? WHERE PK_JOB = ?");
                st.setInt(1, slurmJobId);
                st.setInt(2, configuration.getTaskPrimaryKey());
                st.execute();
            } catch (SQLException | SecurityException ex) {
                logger.error(ex.getLocalizedMessage(), ex);
            }
            // Loop check for job status
            Set<String> finishedStates = new HashSet<>();
            for(SlurmJobKnownStatus s : SLURM_JOB_KNOWN_STATUSES) {
                if(s.finished) {
                    finishedStates.add(s.status);
                }
            }
            ProgressVisitor slurmJobProgress = progressVisitor.subProcess(configuration.slurmConfig.maxJobs);
            int oldFinishedJobs = 0;
            Map<String, Long> bytesReadInFiles = new HashMap<>();
            while(!progressVisitor.isCanceled()) {
                long lastPullTime = System.currentTimeMillis();
                output = runCommand(session, String.format("sacct --format JobID,Jobname,Start,End,Elapsed,NodeList,State,ExitCode,TotalCPU -j %d", slurmJobId));
                List<SlurmJobStatus> jobStatusList = parseSlurmStatus(output, slurmJobId);
                int finishedStatusCount = 0;
                for(SlurmJobStatus s : jobStatusList) {
                    if(finishedStates.contains(s.status)) {
                        finishedStatusCount++;
                    }
                }
                // increase progress if needed
                if(oldFinishedJobs != finishedStatusCount) {
                    for(int i=0; i < (finishedStatusCount - oldFinishedJobs); i++) {
                        slurmJobProgress.endStep();
                    }
                    oldFinishedJobs = finishedStatusCount;
                }
                if(finishedStatusCount == configuration.slurmConfig.maxJobs) {
                    break;
                }
                // Log output of the computing nodes into the logger
                // we have to keep track of how much bytes we have already read in order to not read two times the same log rows
                // we will use the ls command in conjunction with the tail command
                try {
                    output = runCommand(session, String.format("cd %s && ls *.out", configuration.remoteJobFolder), false);
                    List<String> files = parseLSCommand(output);
                    for (String file : files) {
                        AtomicLong readBytes = new AtomicLong(0L);
                        Long alreadyReadBytes = 0L;
                        if (bytesReadInFiles.containsKey(file)) {
                            alreadyReadBytes = bytesReadInFiles.get(file);
                        }
                        // the command "tail -c +N" will skip N bytes and read the remaining bytes
                        runCommand(session, String.format("cd %s && tail -c +%d %s", configuration.remoteJobFolder, alreadyReadBytes, file), true, readBytes);
                        bytesReadInFiles.put(file, alreadyReadBytes + readBytes.get());
                    }
                } catch (JSchException | IOException e) {
                    logger.error("Error while reading remote log files", e);
                }
                // Sleep up to POLL_SLURM_STATUS_TIME
                Thread.sleep(Math.max(1000, POLL_SLURM_STATUS_TIME - (System.currentTimeMillis() - lastPullTime)));
            }
            // retrieve data
            sftp = session.openChannel("sftp");
            String resultDir;
            try {
                sftp.connect(SFTP_TIMEOUT);
                ChannelSftp c = (ChannelSftp) sftp;
                String remoteHome = c.getHome();
                resultDir = new File(remoteHome, String.format("results_%d", slurmJobId)).getAbsolutePath();
                pullFromSSH(c, progressVisitor,
                        resultDir,
                        new File(configuration.workingDirectory, RESULT_DIRECTORY_NAME).getAbsolutePath());
            } finally {
                sftp.disconnect();
            }
            // Remote cleaning
            logger.info("Clean remote data");
            runCommand(session, String.format("rm -rvf %s", configuration.remoteJobFolder));
            runCommand(session, String.format("rm -rvf %s", resultDir));
        } finally {
            session.disconnect();
        }

    }

    void setJobState(JOB_STATES newState) {
        try (Connection connection = plamadeDataSource.getConnection()) {
            PreparedStatement st = connection.prepareStatement("UPDATE JOBS SET STATE = ? WHERE PK_JOB = ?");
            st.setString(1, newState.toString());
            st.setInt(2, configuration.getTaskPrimaryKey());
            st.execute();
        } catch (SQLException | SecurityException ex) {
            logger.error(ex.getLocalizedMessage(), ex);
        }
    }

    /**
     * Export provided spatial table names to a specified folder
     * @param connection JDBC connection
     * @param tablesToExport Table name to export
     * @param folder Folder to save shape files
     * @throws SQLException
     * @throws IOException
     */
    public static void exportTables(Connection connection, List<String> tablesToExport, String folder) throws SQLException, IOException {
        for(String tableName : tablesToExport) {
            SHPWrite.exportTable(connection, new File(folder, tableName + ".shp").getAbsolutePath(), tableName);
        }
    }


    @Override
    public void run() {
        Thread.currentThread().setName("JOB_" + configuration.getTaskPrimaryKey());
        isRunning = true;
        setJobState(JOB_STATES.RUNNING);
        try {
            // create folder
            File workingDir = new File(configuration.workingDirectory);
            if (workingDir.exists() && workingDir.isDirectory()) {
                if (workingDir.getAbsolutePath().startsWith(new File("").getAbsolutePath())) {
                    if (!workingDir.delete()) {
                        logger.error("Cannot delete the working directory\n" + configuration.workingDirectory);
                        return;
                    }
                } else {
                    logger.error(String.format(Locale.ROOT, "Can delete only sub-folder \n%s\n%s", new File("").getAbsolutePath(), workingDir.getAbsolutePath()));
                }
            }
            if(!workingDir.exists()) {
                if (!(workingDir.mkdirs())) {
                    logger.error("Cannot create the working directory\n" + configuration.workingDirectory);
                    return;
                }
            }
            nmDataSource = createDataSource("", "", configuration.workingDirectory, "h2gisdb", false);

            // Download data from external database
            ProgressVisitor progressVisitor = configuration.progressVisitor;
            ProgressVisitor subProg = progressVisitor.subProcess(8);
            try (Connection nmConnection = nmDataSource.getConnection()) {
                importData(nmConnection, subProg);
                if(subProg.isCanceled()) {
                    setJobState(JOB_STATES.CANCELED);
                    return;
                }
                makeGrid(nmConnection);
                subProg.endStep();
                makeEmission(nmConnection);
                subProg.endStep();
                generateClusterConfig(nmConnection, subProg, configuration.slurmConfig.maxJobs, configuration.workingDirectory);
                subProg.endStep();
                slurmInitAndStart(configuration.slurmConfig, subProg);
                List<String> createdTables = mergeShapeFiles(nmConnection,
                        new File(configuration.workingDirectory, RESULT_DIRECTORY_NAME).getAbsolutePath(),
                        "out_", "_");
                subProg.endStep();
                // Save merged final tables
                File outDir = new File(configuration.workingDirectory, POST_PROCESS_RESULT_DIRECTORY_NAME);
                if(!outDir.exists()) {
                    if(!outDir.mkdir()) {
                        return;
                    }
                }
                createdTables.add("ROADS");
                exportTables(nmConnection, createdTables, outDir.getAbsolutePath());
                subProg.endStep();
                logger.info(Export(nmConnection, subProg).toString());
                subProg.endStep();
            }
            setJobState(JOB_STATES.COMPLETED);
        } catch (SQLException ex) {
            while(ex != null) {
                logger.error(ex.getLocalizedMessage(), ex);
                ex = ex.getNextException();
            }
        } catch (Exception ex) {
            logger.error(ex.getLocalizedMessage(), ex);
            setJobState(JOB_STATES.FAILED);
        } finally {
            // Update Job data
            try (Connection connection = plamadeDataSource.getConnection()) {
                PreparedStatement st = connection.prepareStatement("UPDATE JOBS SET END_DATE = ?, PROGRESSION = 100 WHERE PK_JOB = ?");
                st.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
                st.setInt(2, configuration.getTaskPrimaryKey());
                st.execute();
            } catch (SQLException | SecurityException ex) {
                logger.error(ex.getLocalizedMessage(), ex);
            }
            isRunning = false;
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        configuration.progressVisitor.cancel();
        return false;
    }

    @Override
    public boolean isCancelled() {
        return configuration.progressVisitor.isCanceled();
    }

    @Override
    public boolean isDone() {
        return !isRunning;
    }

    @Override
    public String get() throws InterruptedException, ExecutionException {
        return "";
    }

    @Override
    public String get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return "";
    }

    public static class Configuration {
        private String workingDirectory;
        private int configurationId;
        private String inseeDepartment;
        private int taskPrimaryKey;
        private DataBaseConfig dataBaseConfig;
        private ProgressVisitor progressVisitor = new EmptyProgressVisitor();
        private SlurmConfig slurmConfig;
        private String remoteJobFolder;

        public Configuration(String workingDirectory, int configurationId, String inseeDepartment, int taskPrimaryKey
                , DataBaseConfig dataBaseConfig, ProgressVisitor progressVisitor, String remoteJobFolder) {
            this.workingDirectory = workingDirectory;
            this.configurationId = configurationId;
            this.inseeDepartment = inseeDepartment;
            this.taskPrimaryKey = taskPrimaryKey;
            this.dataBaseConfig = dataBaseConfig;
            this.progressVisitor = progressVisitor;
            this.remoteJobFolder = remoteJobFolder;
        }

        public SlurmConfig getSlurmConfig() {
            return slurmConfig;
        }

        public void setSlurmConfig(SlurmConfig slurmConfig) {
            this.slurmConfig = slurmConfig;
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public int getConfigurationId() {
            return configurationId;
        }

        public String getInseeDepartment() {
            return inseeDepartment;
        }

        public int getTaskPrimaryKey() {
            return taskPrimaryKey;
        }

        public DataBaseConfig getDataBaseConfig() {
            return dataBaseConfig;
        }
    }


    public static class SlurmJobStatus {
        public final String status;
        public final int taskId;

        public SlurmJobStatus(String status, int taskId) {
            this.status = status;
            this.taskId = taskId;
        }
    }

    public static class SlurmJobKnownStatus {
        public final String status;
        public final boolean finished;

        public SlurmJobKnownStatus(String status, boolean finished) {
            this.status = status;
            this.finished = finished;
        }
    }
}
