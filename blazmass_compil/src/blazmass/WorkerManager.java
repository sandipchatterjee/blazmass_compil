package blazmass;

import blazmass.dbindex.DBIndexer;
import blazmass.dbindex.DBIndexer.IndexerMode;
import blazmass.dbindex.DBIndexerException;
import blazmass.io.FileResultWriter;
import blazmass.io.MS2Scan;
import blazmass.io.MS2ScanReader;
import blazmass.io.ResultWriter;
import blazmass.io.SearchParamReader;
import blazmass.io.SearchParams;
import com.mongodb.MongoException;
import java.io.BufferedWriter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import mongoconnect.MongoConnect;
import org.apache.commons.io.FileUtils;

import org.apache.commons.io.FilenameUtils;


/**
 * Manages blazmass worker threads
 *
 * TODO:
 *
 * - optional param to print stats every n secs only
 *
 * - use FileResultWriterQueue: write() produces strings, and consumer thread
 * writes to file
 *
 * @author Adam
 */
public class WorkerManager {

    /**
     * number workers / threads that can process at the same time TODO read from
     * command-line
     */
    public static final int MAX_WORKERS = 32;
    public static final int DEFAULT_WORKERS = 4;
    private int numWorkers;
    private static WorkerManager instance = null;
    //params
    private SearchParams params = null;
    public SearchParamReader paramReader = null;
    private boolean inited = false;
    private static final Logger logger = Logger.getLogger(WorkerManager.class.getName());

    public WorkerManager(int numWorkers) {
        this.numWorkers = numWorkers;
    }

    public void init(String paramsDir, String paramsFile) {
        if (inited) {
            logger.log(Level.WARNING, "Already inited");
            return;
        }
        try {
            paramReader = new SearchParamReader(paramsDir, paramsFile);
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Cannot read params and init worker manager", ex);
            System.exit(1);
            return;
        }
        params = paramReader.getSearchParams();
        inited = true;
    }

    private static void usage_exit() {
        System.out.println("Usage: java blazmass ms2_directory num_threads");
        System.out.println("Usage: java blazmass ms2_directory ms2_file param_file num_threads");
        System.exit(1);
    }

    public void runFile(File ms2File) {
        if (!inited) {
            logger.severe("Not inited");
            return;
        }
        //submit work for ms2 file
        logger.log(Level.INFO, "Processing MS2: " + ms2File.getAbsolutePath());

        try {
            WorkerPool workers = new WorkerPool(ms2File.getAbsolutePath(), params, numWorkers, paramReader);
            workers.start(); //workers are running and WorkerPool blocks til done
            
            // Here: workers are done (one file)
            // System.out.println("checking workers");
            // workers.checkWorkerPoolError();

            // Join result file
            workers.joinSQTFiles();
            
        } catch (WorkerManagerException ex) {
            logger.log(Level.SEVERE, null, ex);
            System.exit(1);
        } catch (IOException ex) {
            logger.log(Level.SEVERE, null, ex);
            System.exit(1);
        }
        
        logger.log(Level.INFO, "Done processing MS2: " + ms2File.getAbsolutePath());
    }

    public void runDir(File dataDir) {
        if (!inited) {
            logger.severe("Not inited");
            return;
        }
        final File[] flist = dataDir.listFiles();

        //submit work for every file
        for (File ms2File : flist) {
            if (!ms2File.getName().endsWith(".ms2")) {
                continue;
            }

            logger.log(Level.INFO, "Processing MS2: " + ms2File.getAbsolutePath());

            try {
                WorkerPool workers = new WorkerPool(ms2File.getAbsolutePath(), params, numWorkers, paramReader);
                workers.start();

                //workers are running and WorkerPool blocks til done
            } catch (WorkerManagerException ex) {
                logger.log(Level.SEVERE, null, ex);
                System.out.println("!!!!!!!!!!!!!worker manager exception");
            } catch (Exception ex){
                System.out.println("!!!!!!!!!!!!!!exception");
                ex.printStackTrace();
            }
            // Here workerpool is done (runDir)
            logger.log(Level.INFO, "Done processing MS2: " + ms2File.getAbsolutePath());
        }
    }

    /**
     * Entry point with dir args
     */
    public static void run(String ms2FilesDirPath, String threads) {
        int numWorkers = DEFAULT_WORKERS;

        try {
            numWorkers = Integer.parseInt(threads);
            if (numWorkers < 1 || numWorkers > MAX_WORKERS) {
                numWorkers = DEFAULT_WORKERS;
                System.out.println("Warning: invalid number of worker threads: " + threads + " using default: " + numWorkers);
            }
        } catch (NumberFormatException e) {
            System.out.println("Warning: invalid number of worker threads: " + threads + " using default: " + numWorkers);
        }

        final File dataDir = new File(ms2FilesDirPath);
        if (!dataDir.isDirectory()) {
            usage_exit();
        }

        String paramsFilePath = ms2FilesDirPath + File.separator + blazmass.io.SearchParamReader.DEFAULT_PARAM_FILE_NAME;
        File paramsFileF = new File(paramsFilePath);
        if (!paramsFileF.canRead()) {
            System.out.println("Error: cannot read params file: " + paramsFilePath);
            System.exit(-1);
        }

        System.out.println("Using max. worker threads: " + numWorkers);
        final WorkerManager manager = new WorkerManager(numWorkers);
        manager.init(ms2FilesDirPath, blazmass.io.SearchParamReader.DEFAULT_PARAM_FILE_NAME);
        manager.runDir(dataDir);

    }

    /**
     * Entry point with root path and ms2 file
     */
    public static void run(String rootPath, String ms2FileName, String paramsFileName, String threads) {
        int numWorkers = DEFAULT_WORKERS;

        try {
            numWorkers = Integer.parseInt(threads);
            if (numWorkers < 1 || numWorkers > MAX_WORKERS) {
                numWorkers = DEFAULT_WORKERS;
                System.out.println("Warning: invalid number of worker threads: " + threads + " using default: " + numWorkers);
            }
        } catch (NumberFormatException e) {
            System.out.println("Warning: invalid number of worker threads: " + threads + " using default: " + numWorkers);
        }

        final String paramsFilePath = rootPath + File.separator + paramsFileName;
        final File paramsFileF = new File(paramsFilePath);
        if (!paramsFileF.canRead()) {
            System.out.println("Error: cannot read params file: " + paramsFilePath);
            System.exit(-1);
        }

        final String dataFilePath = rootPath + File.separator + ms2FileName;
        final File dataFileF = new File(dataFilePath);
        if (!dataFileF.canRead()) {
            System.out.println("Error: cannot read data file: " + dataFilePath);
            System.exit(-1);
        }

        System.out.println("Using max. worker threads: " + numWorkers);
        final WorkerManager manager = new WorkerManager(numWorkers);
        manager.init(rootPath, paramsFileName);
        manager.runFile(dataFileF);
    }

    public static void main(String[] args) {
        if (args.length != 2 && args.length != 4) {
            usage_exit();
        }

        if (args.length == 2) {
            String ms2FilesDirPath = args[0];
            String numWorkers = args[1];
            run(ms2FilesDirPath, numWorkers);
        } else {
            String ms2FilesDirPath = args[0];
            String ms2File = args[1];
            String numWorkers = args[2];
            String paramFile = args[3];
            run(ms2FilesDirPath, ms2File, paramFile, numWorkers);
        }
    }

    /**
     * Custom worker pool of blazmass threads Lives for duration of a single MS2
     * search. We are using our own, because threads should be polling
     * themselves for new tasks, and have resources associated with them
     *
     * @author Adam
     */
    private static class WorkerPool {

        private final List<Worker> workers = new ArrayList<>();
        private int numWorkers;
        //input
        private SearchParams params; //shared
        private String ms2FilePath;
        private MS2ScanReader scanReader;
        private MS2ScanQueue scanQueue; //shared
        private MS2ScanProducer scanProducer; //shared
        private SearchParamReader paramReader; //so each thread can have its own params
        //output FileResultWriter has some sort of bug and causes malformed sqt files sometimes, randomly]
        // so, each worker now writes to its own file
        private List<BufferedWriter> sqtWriters = new ArrayList<>();
        public final List<String> sqtPaths;
        private FileWriter finalSQTWriter = null;
        private static final Logger logger = Logger.getLogger(WorkerPool.class.getName());
        private static final Logger fileLogger = Logger.getLogger("WorkerPool_filelogger");
        public final String finalSQTPath;
        FileHandler fh;
        private final MongoConnect mongoConnection;
        
        WorkerPool(String ms2FilePath, SearchParams params, int numWorkers, SearchParamReader paramReader) throws WorkerManagerException {
            this.sqtPaths = new ArrayList<>();
            this.ms2FilePath = ms2FilePath;
            this.params = params;
            this.numWorkers = numWorkers;
            this.paramReader = paramReader;
            // one mongoconnection for the entire pool, shared across workers
            this.mongoConnection = new mongoconnect.MongoConnect(params);

            try {
                this.scanReader = new MS2ScanReader(ms2FilePath);
                this.scanQueue = new MS2ScanQueue();
                scanProducer = new MS2ScanProducer(scanReader, scanQueue);
                
                String fullPath = FilenameUtils.getFullPath(ms2FilePath);
                String basePath = FilenameUtils.getBaseName(ms2FilePath);
                finalSQTPath = FilenameUtils.concat(fullPath, basePath + "." + Blazmass.SQT_EXT);
                
                // Initialize final sqt file. 0 out file
                // this won't be written to until the workers are done
                finalSQTWriter = new FileWriter(finalSQTPath);
                
                // Initialize sqtWriters, one for each worker
                for (int i = 0; i < numWorkers; ++i) {
                    String workerSqtPath = finalSQTPath + "_" + String.valueOf(i);
                    sqtPaths.add(workerSqtPath);
                    sqtWriters.add(new BufferedWriter(new FileWriter(workerSqtPath)));
                }

                System.out.println("Writing output SQT to: " + finalSQTPath);

            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Could not start create result file and start worker pool");
                throw new WorkerManagerException("Could not start create result file and start worker pool", ex);
            }
            
            String logPath = FilenameUtils.getBaseName(Paths.get(ms2FilePath).getFileName().toString()) + ".LOG";
            try {
                fh = new FileHandler(logPath);
                fileLogger.addHandler(fh);
                SimpleFormatter formatter = new SimpleFormatter();  
                fh.setFormatter(formatter);
            } catch (IOException ex) {
                Logger.getLogger(WorkerManager.class.getName()).log(Level.SEVERE, null, ex);
            } catch (SecurityException ex) {
                Logger.getLogger(WorkerManager.class.getName()).log(Level.SEVERE, null, ex);
            }
                        
        }

        /**
         * Start all the threads and let them terminate if there is no more work
         *
         * @throws WorkerManagerException
         */
        void start() throws WorkerManagerException, IOException {

            //start scan producer
            logger.log(Level.INFO, "Starting scan producer");
            scanProducer.start();

            //create and start threads
            for (int i = 0; i < numWorkers; ++i) {
                String id = "Worker#" + (i + 1);
                SearchParams thisParams = paramReader.getSearchParams(); // so each thread has its own params
                // necessary for the diffmod search so a single residue can have different diffmod masses
                final Worker worker = new Worker(id, scanQueue, sqtWriters.get(i), thisParams, fileLogger, mongoConnection);
                workers.add(worker);
                worker.start();
            }

            logger.log(Level.INFO, "Workers started");

            //start a monitor and wait for it to finish
            //monitor exits when all work is done for this ms2
            logger.log(Level.INFO, "Starting monitor");

            final Monitor monitor = new Monitor();
            monitor.start();
            try {
                // monitor join() blocks until all worker threads are "done"
                monitor.join();
            } catch (InterruptedException ex) {
                logger.log(Level.SEVERE, "Cannot join monitor", ex);
            }

            logger.log(Level.INFO, "Monitor Completed");
            mongoConnection.disconnect();
            // Flush result writers
            for (BufferedWriter sqtWriter : sqtWriters){
                sqtWriter.flush();
                sqtWriter.close();
            }
        }

        /**
         * Force stop all the worker threads even if there is still work
         *
         * @throws WorkerManagerException
         */
        void stop() throws WorkerManagerException {
            scanProducer.stopProducer();

            for (Worker worker : workers) {
                worker.setStop();
            }
        }

        /**
         * Check if any threads are still running
         *
         * @return
         */
        boolean isRunning() {
            for (Worker worker : workers) {
                if (worker.isRunning) {
                    return true;
                }
            }
            return false;
        }

        String getStats() {
            StringBuilder sb = new StringBuilder();
            sb.append(this.scanProducer.toString()).append("\n");
            sb.append(this.scanQueue.toString()).append("\n");
            for (Worker worker : workers) {
                sb.append(worker.getStats()).append("\n");
            }
            return sb.toString();
        }
        
        public void checkWorkerPoolError(){
            int spectraAttempted = 0;
            int spectraErrors = 0;
            int spectraProcessed = 0;
            for (Worker worker : workers) {
                Map<String, Integer> stats = worker.getErrorStatus();
                spectraAttempted += stats.get("spectraAttempted");
                spectraErrors += stats.get("spectraErrors");
                spectraProcessed += stats.get("spectraProcessed");
            }
            System.out.println("spectraAttempted: " + spectraAttempted);
            System.out.println("spectraErrors: " + spectraErrors);
            System.out.println("spectraProcessed: " + spectraProcessed);
            if (( (float) spectraErrors / spectraAttempted) > 0.05 ){
                System.out.println("Too many scans failed. Completed Unsuccesfully");
                System.exit(1);
            }
        }
        
        public void joinSQTFiles() throws IOException{
            
            //write header just once, create temp blazmass for that...
            final Blazmass bmass = new Blazmass();
            finalSQTWriter.write(bmass.header(params).toString());
            finalSQTWriter.close();
            
            File finalSQTFile = new File(finalSQTPath);
            for (String sqtPath : sqtPaths){
                String fileStr = FileUtils.readFileToString(new File(sqtPath));
                FileUtils.write(finalSQTFile, fileStr, true);
            }
            // If this finishes without throwing an error, delete the worker temp files
            for (String sqtPath : sqtPaths){
                File f = new File(sqtPath);
                f.delete();
            }          
            
        }

        /**
         *
         * Monitors current job, prints stats and waits till done
         *
         * @author Adam
         */
        class Monitor extends Thread {

            static final int MONITOR_STATS_INTERVAL = 5 * 1000;
            private final Logger logger = Logger.getLogger(Monitor.class.getName());

            private void updateLogFile(FileWriter logWriter, String logPath, String totalScansStr) {
                //update log file
                if (logWriter != null) {
                    int totalScansDone = 0;
                    for (int i = 0; i < numWorkers; ++i) {
                        totalScansDone += workers.get(i).spectraProcessed;
                    }
                    try {
                        logWriter.append(totalScansStr).append("\t").append(Integer.toString(totalScansDone)).append("\n");
                        logWriter.flush(); //always flush since every few secs
                    } catch (IOException ex) {
                        logger.log(Level.SEVERE, "Error writing to log file: " + logPath, ex);
                    }
                }
            }

            @Override
            public void run() {
                /*
                String baseName = Util.getFileBaseName(ms2FilePath);
                String logPath = baseName + "." + Blazmass.LOG_EXT;
                FileWriter logWriter = null;
                try {
                    logWriter = new FileWriter(logPath);
                } catch (IOException ex) {
                    logger.log(Level.SEVERE, "Cannot initialize the log writer: " + logPath, ex);
                }
                */
                final int totalScans = scanProducer.getNumScansIdx();
                String totalScansStr = null;
                if (totalScans == -1) {
                    totalScansStr = "Unknown";
                } else {
                    totalScansStr = Integer.toString(totalScans);
                }

                while (!scanProducer.isIsDone() || isRunning()) {
                    try {
                        System.out.println(getStats());

                        //update log file
                        //updateLogFile(logWriter, logPath, totalScansStr);

                        Thread.sleep(MONITOR_STATS_INTERVAL);
                    } catch (InterruptedException ex) {
                        logger.log(Level.SEVERE, "Sleep interrupted in monitor", ex);
                    }
                }

                //done, write final progress
                System.out.println(getStats());
                //update log file
                //updateLogFile(logWriter, logPath, totalScansStr);

                logger.log(Level.INFO, "Monitor done");
                /*
                if (logWriter != null) {
                    try {
                        logWriter.close();
                    } catch (IOException ex) {
                        logger.log(Level.SEVERE, "Error closing log file", ex);
                    }
                }*/

            }
        }
    }

    /**
     * Blazmass worker thread, its lifecycle is for duration of search for a
     * single MS2 file Has its own indexer handle, and handle to shared params
     * and shared MS2 iterator handle, and shared output buffer TODO consider
     * result queue and result writer thread
     *
     * Gets and new spectrum Executes blazmass search on the spectrum, using the
     * params and indexer Writes result of search to
     *
     * @author Adam
     */
    private static class Worker extends Thread {

        private String id;
        //input
        private SearchParams params;
        private DBIndexer indexer; //not shared, every needs own instance with own connections
        private MS2ScanQueue scanQueue;
        //output
        private BufferedWriter resultWriter;
        private static final Logger logger = Logger.getLogger(Worker.class.getName());
        //state
        private volatile boolean shouldRun;
        private volatile boolean isRunning;
        //algorithm
        private Blazmass bmass; //not shared, reusing instance for all scan searches TODO check
        //stats
        private int spectraProcessed = 0;
        private int spectraErrors = 0;
        private int spectraAttempted = 0;
        private long startTime;
        private long endTime;
        private long totalTime;
        private HighResMassProcessor hprocessor;
        private final Logger fileLogger;
        private final MongoConnect mongoConnection;
        
        Map<String, Integer> getErrorStatus() {
            Map<String, Integer> map = new HashMap<>();
            map.put("spectraAttempted", spectraAttempted);
            map.put("spectraErrors", spectraErrors);
            map.put("spectraProcessed", spectraProcessed);
            return map;
        }
        
        Worker(final String id, MS2ScanQueue scanQueue, final BufferedWriter resultWriter, 
                final SearchParams params, Logger fileLogger, MongoConnect mongoConnection) throws WorkerManagerException {
            this.id = id;
            this.scanQueue = scanQueue;
            this.resultWriter = resultWriter;
            this.params = params;
            this.fileLogger = fileLogger;
            this.mongoConnection = mongoConnection;

            this.shouldRun = true;
            this.isRunning = false;

            this.bmass = new Blazmass();

            final IndexerMode indexerMode = params.isUseIndex() ? IndexerMode.SEARCH_INDEXED : IndexerMode.SEARCH_UNINDEXED;

            if (!params.isUsingMongoDB()) {
                try {
                    this.indexer = new DBIndexer(params, indexerMode);
                    this.indexer.init();
                } catch (DBIndexerException ex) {
                    logger.log(Level.SEVERE, "Could not initialize the indexer in search mode and init the worker thread");
                    throw new WorkerManagerException("Could not initialize the indexer and init the worker thread", ex);
                }
            }
        }

        void setStop() {
            shouldRun = false;
        }

        boolean isRunning() {
            return isRunning;
        }

        @Override
        public void run() {
            isRunning = true;
            this.startTime = System.currentTimeMillis();
            this.bmass.hprocessor = new HighResMassProcessor();

            while (shouldRun) {

                final MS2Scan scan = scanQueue.dequeue();
                
                if (scan == null) {
                    logger.log(Level.FINE, "no more scans");
                    break;
                }
                fileLogger.info(scan.getIsScan1() + "");
                spectraAttempted++;
                try {

                    if (params.isHighResolution()) {
                        bmass.runScanHigh(scan, params, indexer, resultWriter, mongoConnection);
                    } else {
                        bmass.runScan(scan, params, indexer, resultWriter, mongoConnection);
                    }
                    ++spectraProcessed;

                    //update stats
                    this.endTime = System.currentTimeMillis();
                    this.totalTime = endTime - startTime;

                } catch (MongoException ex) {
                    ex.printStackTrace();
                    System.out.println("Mongo failed. Exiting");
                    System.exit(1);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    ++spectraErrors;
                    logger.log(Level.SEVERE, "Unexpected exception while running worker on scan: " + scan.toString(), ex);
                    logger.log(Level.INFO, "Skipping the scan and tryng to continue");
                    logger.log(Level.INFO, "Number of errors: " + spectraErrors + "/" + spectraAttempted);
                }
            }
            isRunning = false;
        }

        public boolean isShouldRun() {
            return shouldRun;
        }

        public boolean isIsRunning() {
            return isRunning;
        }

        public int getSpectraProcessed() {
            return spectraProcessed;
        }

        public int getSpectraErrors() {
            return spectraErrors;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getTotalTime() {
            return totalTime;
        }

        /**
         * get worker stats id, total spectrum, total time, time per spectrum,
         * time reading, time writing is running
         *
         * @return
         */
        public String getStats() {
            int perSpectrumTime = 0;
            if (spectraProcessed > 0) {
                perSpectrumTime = (int) totalTime / spectraProcessed;
            }

            //    return id;
            return id + ": spectra: " + spectraProcessed + ", errors: " + spectraErrors
                    + ", total run time: " + totalTime + "ms."
                    + ", per scan time: " + perSpectrumTime + "ms."
                    + ", isRunning: " + isRunning; //TODO start time, end time

        }
    }
}

/**
 * Exception thrown by worker manager
 *
 * @author Adam
 */
class WorkerManagerException extends Exception {

    public WorkerManagerException(String message) {
        super(message);
    }

    public WorkerManagerException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Blocking producer-consumer queue to optimize scan processing and increase
 * scan throughput
 *
 * @author Adam
 *
 */
class MS2ScanQueue {

    private final Queue<MS2Scan> queue = new LinkedList<MS2Scan>();
    private volatile boolean isDone = false;
    private static final int MAX_SCANS = 1000;
    private static final Logger logger = Logger.getLogger(MS2ScanQueue.class.getName());
    private int dequedScans = 0;
    private FileWriter scanLogWriter = null;
    
    synchronized void setIsDone() {
        this.isDone = true;
        notify();
    }

    /**
     * blocks until either new spectra arrive or there is no more spectra to
     * arrive
     *
     * @return scan or null if done
     */
    synchronized MS2Scan dequeue() {
        while (queue.isEmpty()) {
            try {
                if (isDone) {
                    //logger.info("is done 0");
                    return null;
                }
                //block
                //logger.info("dequeue wait");
                wait();
                //logger.info("dequeue awaken");
                //notified by producer to awaken
                if (isDone) {
                    //logger.info("is done");
                    //no more spectra to produce but queue may still have some
                    if (queue.isEmpty()) {
                        // logger.info("is done and empty");
                        notify();
                        return null;
                    } else {
                        // logger.info("is done and not empty");
                        //has more scans
                        break;
                    }
                } else {
                    //has more scans
                    // logger.info("is not done");
                    break;
                }
            } catch (InterruptedException ex) {
                Logger.getLogger(MS2ScanQueue.class.getName()).log(Level.SEVERE, "Interrupted while in dequeue", ex);
                return null;
            }
        }

        //logger.info("polling from queue");
        final MS2Scan scan = queue.poll();
        notifyAll();
        ++dequedScans;
        return scan;

    }

    synchronized void enqueue(MS2Scan scan) {
        while (queue.size() == MAX_SCANS) {
            //logger.info("enqueue max scans, will block");
            try {
                notify(); //awaken other and sleep
                wait();
            } catch (InterruptedException ex) {
                Logger.getLogger(MS2ScanQueue.class.getName()).log(Level.SEVERE, "Interrupted while in enqueue", ex);
            }
        }
        //logger.info("enqueueing scan");
        queue.add(scan);
        notify();
    }

    @Override
    public synchronized String toString() {
        return "MS2ScanQueue{" + "curNumScans:" + queue.size() + ", dequedScans: " + dequedScans + ", isDone=" + isDone + '}';
    }
}

/**
 * MS2 scan reader thread that enqueues and buffers scans
 *
 * @author Adam
 */
class MS2ScanProducer extends Thread {

    private MS2ScanReader reader;
    private MS2ScanQueue theQueue;
    private volatile boolean doRun = false;
    private int totalScans;
    private boolean isDone = false;
    private static final Logger logger = Logger.getLogger(MS2ScanProducer.class.getName());

    MS2ScanProducer(MS2ScanReader reader, MS2ScanQueue scanQueue) {
        this.reader = reader;
        doRun = true;
        theQueue = scanQueue;
        totalScans = 0;
    }

    void stopProducer() {
        doRun = false;
    }

    int getNumScansIdx() {
        return reader.getNumScansIdx();
    }

    public boolean isDoRun() {
        return doRun;
    }

    public boolean isIsDone() {
        return isDone;
    }

    @Override
    public String toString() {
        return "MS2ScanProducer{doRun=" + doRun + ", totalScans=" + totalScans + ", isDone=" + isDone + '}';
    }

    @Override
    public void run() {
        while (doRun && reader.hasNext()) {
            final MS2Scan scan = reader.next();
            theQueue.enqueue(scan);
            ++totalScans;
        }

        logger.log(Level.INFO, "Producer: no more scans, produced: " + totalScans);

        //signal queue it is done
        theQueue.setIsDone();

        isDone = true;
        logger.log(Level.INFO, "Producer done");

    }
}
