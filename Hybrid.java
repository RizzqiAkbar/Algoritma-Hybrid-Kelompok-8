import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Hybrid {

    // Konfigurasi Infrastruktur
    private static final int NUM_VMS = 10;
    private static final double COST_PER_SEC = 3.0; // Biaya pemrosesan per detik pada VM

    public static void main(String[] args) {
        // 1. Matikan seluruh log internal CloudSim agar output bersih
        Log.disable();

        // 2. Temukan folder Datasets dan Results
        File datasetsDir = resolveDatasetsDirectory();
        File resultsDir = resolveResultsDirectory();

        if (!resultsDir.exists()) {
            resultsDir.mkdirs();
        }

        // Tentukan apakah user memasukkan file tertentu atau menjalankan seluruh dataset 100 - 1000
        List<File> targetDatasets = new ArrayList<>();
        if (args != null && args.length > 0 && !args[0].isBlank() && !args[0].equalsIgnoreCase("all")) {
            File custom = resolveFile(args[0]);
            if (custom.exists()) {
                targetDatasets.add(custom);
            } else {
                System.err.println("File dataset tidak ditemukan: " + custom.getAbsolutePath());
                System.exit(1);
            }
        } else {
            // Ambil semua file GoCJ_Dataset_*.json dan urutkan secara numerik (100 -> 1000)
            File[] files = datasetsDir.listFiles((dir, name) -> name.matches("GoCJ_Dataset_\\d+\\.json"));
            if (files != null && files.length > 0) {
                Arrays.sort(files, Comparator.comparingInt(Hybrid::extractTaskCountFromFileName));
                targetDatasets.addAll(Arrays.asList(files));
            } else {
                System.err.println("Tidak ada file dataset GoCJ di direktori: " + datasetsDir.getAbsolutePath());
                System.exit(1);
            }
        }

        System.out.println("=========================================================================================");
        System.out.println("SIMULASI BATCH HYBRID TASK SCHEDULING (CLOUDSIM)");
        System.out.println("Algoritma    : 1) Hybrid Min-Min + GA vs 2) Hybrid Max-Min + ACO");
        System.out.println("Jumlah VM    : " + NUM_VMS + " (Heterogeneous Tiers: 500 - 2500 MIPS)");
        System.out.println("Total Dataset: " + targetDatasets.size() + " berkas (100 s.d. 1000 Task)");
        System.out.println("Folder Hasil : " + resultsDir.getAbsolutePath());
        System.out.println("=========================================================================================\n");

        File csvFile = new File(resultsDir, "hasil_simulasi_hybrid.csv");
        File txtFile = new File(resultsDir, "ringkasan_simulasi.txt");

        List<ComparisonRecord> records = new ArrayList<>();

        try (PrintWriter csvWriter = new PrintWriter(new FileWriter(csvFile, false));
             PrintWriter txtWriter = new PrintWriter(new FileWriter(txtFile, false))) {

            // Header CSV
            csvWriter.println("Dataset,N_Task,Algoritma,Makespan_s,Throughput_tps,Degree_of_Imbalance,Total_Cost_USD,Resource_Utilization_pct,Algo_Execution_Time_ms");

            txtWriter.println("=========================================================================================");
            txtWriter.println("LAPORAN RINGKASAN SIMULASI BATCH HYBRID TASK SCHEDULING (CLOUDSIM)");
            txtWriter.println("Jumlah VM    : " + NUM_VMS);
            txtWriter.println("Tanggal Uji  : " + new Date());
            txtWriter.println("=========================================================================================\n");

            int datasetIndex = 1;
            for (File datasetFile : targetDatasets) {
                int taskCount = extractTaskCountFromFileName(datasetFile);
                long[] cloudletLengths = loadGoCJDataset(datasetFile);
                long dynamicSeed = generateDynamicSeed();
                Random rand = new Random(dynamicSeed);
                int[] vmMips = generateVmMips(NUM_VMS, rand);

                System.out.printf("[%d/%d] Menjalankan Dataset: %-22s (Tasks: %d)...%n",
                        datasetIndex, targetDatasets.size(), datasetFile.getName(), cloudletLengths.length);

                // Simulasi 1: Hybrid Min-Min + GA
                MetricsResult minMinGaResult = runSimulation("Hybrid Min-Min + GA", cloudletLengths, vmMips, dynamicSeed);

                // Simulasi 2: Hybrid Max-Min + ACO
                MetricsResult maxMinAcoResult = runSimulation("Hybrid Max-Min + ACO", cloudletLengths, vmMips, dynamicSeed);

                // Catat ke CSV
                writeCsvRow(csvWriter, datasetFile.getName(), taskCount, minMinGaResult);
                writeCsvRow(csvWriter, datasetFile.getName(), taskCount, maxMinAcoResult);
                csvWriter.flush();

                records.add(new ComparisonRecord(taskCount, datasetFile.getName(), minMinGaResult, maxMinAcoResult));

                // Log ringkasan konsol
                System.out.printf("    -> Makespan: GA = %10.2fs | ACO = %10.2fs (Selisih: %+7.2fs)%n",
                        minMinGaResult.makespan, maxMinAcoResult.makespan, (maxMinAcoResult.makespan - minMinGaResult.makespan));
                System.out.printf("    -> DoI     : GA = %10.4f  | ACO = %10.4f %n",
                        minMinGaResult.degreeOfImbalance, maxMinAcoResult.degreeOfImbalance);
                System.out.printf("    -> Util    : GA = %9.2f%%  | ACO = %9.2f%%%n",
                        minMinGaResult.resourceUtilization, maxMinAcoResult.resourceUtilization);
                System.out.printf("    -> Runtime : GA = %9.2fms | ACO = %9.2fms%n%n",
                        minMinGaResult.algoRuntimeMs, maxMinAcoResult.algoRuntimeMs);

                datasetIndex++;
            }

            // Tampilkan Tabel Rekapitulasi Lengkap di Konsol & Simpan di TXT
            printSummaryTable(records, txtWriter);

            System.out.println("=========================================================================================");
            System.out.println("Simulasi Selesai! Seluruh hasil berhasil disimpan di folder Results:");
            System.out.println("1. CSV Data    : " + csvFile.getAbsolutePath());
            System.out.println("2. Laporan TXT : " + txtFile.getAbsolutePath());
            System.out.println("=========================================================================================");

        } catch (IOException e) {
            System.err.println("Gagal menulis file hasil: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void writeCsvRow(PrintWriter pw, String datasetName, int nTask, MetricsResult r) {
        pw.printf(Locale.US, "%s,%d,%s,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f%n",
                datasetName, nTask, r.algoName, r.makespan, r.throughput,
                r.degreeOfImbalance, r.totalCost, r.resourceUtilization, r.algoRuntimeMs);
    }

    private static void printSummaryTable(List<ComparisonRecord> records, PrintWriter txtWriter) {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================================================================================================\n");
        sb.append(String.format("%-8s | %-20s | %-20s | %-16s | %-16s | %-16s%n",
                "N Tasks", "Makespan (GA vs ACO)", "DoI (GA vs ACO)", "Util % (GA/ACO)", "Cost (GA vs ACO)", "Algo Time (ms)"));
        sb.append("------------------------------------------------------------------------------------------------------------------------\n");

        for (ComparisonRecord rec : records) {
            MetricsResult ga = rec.ga;
            MetricsResult aco = rec.aco;
            String makespanStr = String.format(Locale.US, "%.1fs / %.1fs", ga.makespan, aco.makespan);
            String doiStr = String.format(Locale.US, "%.3f / %.3f", ga.degreeOfImbalance, aco.degreeOfImbalance);
            String utilStr = String.format(Locale.US, "%.1f%% / %.1f%%", ga.resourceUtilization, aco.resourceUtilization);
            String costStr = String.format(Locale.US, "$%.0f / $%.0f", ga.totalCost, aco.totalCost);
            String timeStr = String.format(Locale.US, "%.1f / %.1f", ga.algoRuntimeMs, aco.algoRuntimeMs);

            sb.append(String.format(Locale.US, "%-8d | %-20s | %-20s | %-16s | %-16s | %-16s%n",
                    rec.nTasks, makespanStr, doiStr, utilStr, costStr, timeStr));
        }
        sb.append("========================================================================================================================\n");

        String tableStr = sb.toString();
        System.out.println(tableStr);
        if (txtWriter != null) {
            txtWriter.println(tableStr);
        }
    }

    private static int extractTaskCountFromFileName(File file) {
        Matcher m = Pattern.compile("GoCJ_Dataset_(\\d+)\\.json").matcher(file.getName());
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private static File resolveDatasetsDirectory() {
        File dir = new File("Datasets");
        if (dir.exists() && dir.isDirectory()) return dir;

        File alt = new File("SOKA/Hybrid/Code/Datasets");
        if (alt.exists() && alt.isDirectory()) return alt;

        return dir;
    }

    private static File resolveResultsDirectory() {
        File dir = new File("Results");
        if (dir.exists() && dir.isDirectory()) return dir;

        File alt = new File("SOKA/Hybrid/Code/Results");
        if (alt.getParentFile() != null && alt.getParentFile().exists()) return alt;

        return dir;
    }

    private static File resolveFile(String pathStr) {
        File f = new File(pathStr);
        if (f.exists()) return f;

        File insideDatasets = new File("Datasets", pathStr);
        if (insideDatasets.exists()) return insideDatasets;

        File insideCodeDatasets = new File("SOKA/Hybrid/Code/Datasets", pathStr);
        if (insideCodeDatasets.exists()) return insideCodeDatasets;

        File insideCode = new File("SOKA/Hybrid/Code", pathStr);
        if (insideCode.exists()) return insideCode;

        return f;
    }

    private static long[] loadGoCJDataset(File file) {
        List<Long> lengths = new ArrayList<>();
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Pattern pattern = Pattern.compile("\"length_mi\"\\s*:\\s*(\\d+)");
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                lengths.add(Long.parseLong(matcher.group(1)));
            }

            if (lengths.isEmpty()) {
                throw new IOException("Tidak ditemukan data 'length_mi' dalam file: " + file.getAbsolutePath());
            }

            long[] result = new long[lengths.size()];
            for (int i = 0; i < lengths.size(); i++) {
                result[i] = lengths.get(i);
            }
            return result;
        } catch (IOException e) {
            System.err.println("Gagal membaca file dataset GoCJ: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static long generateDynamicSeed() {
        SecureRandom secureRandom = new SecureRandom();
        return secureRandom.nextLong() ^ System.nanoTime();
    }

    private static int[] generateVmMips(int count, Random rand) {
        int[] mips = new int[count];
        int[] tiers = {500, 1000, 1500, 2000, 2500}; // Heterogenitas kapasitas VM
        for (int i = 0; i < count; i++) {
            mips[i] = tiers[rand.nextInt(tiers.length)];
        }
        return mips;
    }

    private static MetricsResult runSimulation(String algorithmName, long[] cloudletLengths, int[] vmMips, long seed) {
        try {
            int numUser = 1;
            Calendar calendar = Calendar.getInstance();
            CloudSim.init(numUser, calendar, false);

            Datacenter datacenter = createDatacenter("Datacenter_0", NUM_VMS);
            DatacenterBroker broker = new DatacenterBroker("Broker_" + algorithmName.replaceAll("[^a-zA-Z0-9_]", ""));
            int brokerId = broker.getId();

            // Buat daftar VM
            List<Vm> vmList = new ArrayList<>();
            for (int i = 0; i < NUM_VMS; i++) {
                Vm vm = new Vm(i, brokerId, vmMips[i], 1, 2048, 10000, 10000, "Xen", new CloudletSchedulerSpaceShared());
                vmList.add(vm);
            }
            broker.submitVmList(vmList);

            // Buat daftar Cloudlet dari dataset GoCJ
            List<Cloudlet> cloudletList = new ArrayList<>();
            UtilizationModel utilizationModel = new UtilizationModelFull();
            for (int i = 0; i < cloudletLengths.length; i++) {
                Cloudlet cloudlet = new Cloudlet(i, cloudletLengths[i], 1, 300, 300, utilizationModel, utilizationModel, utilizationModel);
                cloudlet.setUserId(brokerId);
                cloudletList.add(cloudlet);
            }
            broker.submitCloudletList(cloudletList);

            // Bangun matriks Execution Time (ET[task][vm])
            int numTasks = cloudletList.size();
            int numVms = vmList.size();
            double[][] execTimeMatrix = new double[numTasks][numVms];
            for (int i = 0; i < numTasks; i++) {
                for (int j = 0; j < numVms; j++) {
                    execTimeMatrix[i][j] = (double) cloudletLengths[i] / vmMips[j];
                }
            }

            // Eksekusi Algoritma Penjadwalan Hybrid
            long startTimeAlgo = System.nanoTime();
            int[] bestMapping;
            if (algorithmName.contains("Min-Min")) {
                bestMapping = scheduleHybridMinMinGA(execTimeMatrix, new Random(seed));
            } else {
                bestMapping = scheduleHybridMaxMinACO(execTimeMatrix, new Random(seed));
            }
            long endTimeAlgo = System.nanoTime();
            double algoRuntimeMs = (endTimeAlgo - startTimeAlgo) / 1_000_000.0;

            // Terapkan hasil pemetaan ke CloudSim Broker
            for (int i = 0; i < numTasks; i++) {
                broker.bindCloudletToVm(cloudletList.get(i).getCloudletId(), vmList.get(bestMapping[i]).getId());
            }

            // Jalankan simulasi CloudSim
            CloudSim.startSimulation();
            List<Cloudlet> finishedList = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();

            // Hitung seluruh metrik evaluasi
            return calculateMetrics(algorithmName, finishedList, numVms, algoRuntimeMs);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // =========================================================================
    // ALGORITMA 1: HYBRID MIN-MIN + GENETIC ALGORITHM (GA)
    // =========================================================================
    private static int[] scheduleHybridMinMinGA(double[][] et, Random rand) {
        int numTasks = et.length;
        int numVms = et[0].length;

        int popSize = 30;
        int maxGenerations = 50;
        double crossoverRate = 0.8;
        double mutationRate = 0.05;

        // Tahap 1: Min-Min solusi elit
        int[] minMinSolution = runMinMin(et);

        // Tahap 2: Populasi awal
        int[][] population = new int[popSize][numTasks];
        population[0] = Arrays.copyOf(minMinSolution, numTasks);
        for (int p = 1; p < popSize; p++) {
            for (int t = 0; t < numTasks; t++) {
                population[p][t] = rand.nextInt(numVms);
            }
        }

        int[] globalBest = Arrays.copyOf(minMinSolution, numTasks);
        double globalBestMakespan = evaluateMakespan(globalBest, et);

        // Tahap 3: Evolusi
        for (int gen = 0; gen < maxGenerations; gen++) {
            int[][] newPopulation = new int[popSize][numTasks];
            newPopulation[0] = Arrays.copyOf(globalBest, numTasks);

            for (int p = 1; p < popSize; p++) {
                int[] parent1 = tournamentSelection(population, et, rand);
                int[] parent2 = tournamentSelection(population, et, rand);

                int[] child = Arrays.copyOf(parent1, numTasks);
                if (rand.nextDouble() < crossoverRate) {
                    int crossPoint = rand.nextInt(numTasks);
                    for (int t = crossPoint; t < numTasks; t++) {
                        child[t] = parent2[t];
                    }
                }
                for (int t = 0; t < numTasks; t++) {
                    if (rand.nextDouble() < mutationRate) {
                        child[t] = rand.nextInt(numVms);
                    }
                }
                newPopulation[p] = child;

                double childMakespan = evaluateMakespan(child, et);
                if (childMakespan < globalBestMakespan) {
                    globalBestMakespan = childMakespan;
                    globalBest = Arrays.copyOf(child, numTasks);
                }
            }
            population = newPopulation;
        }
        return globalBest;
    }

    private static int[] runMinMin(double[][] et) {
        int numTasks = et.length;
        int numVms = et[0].length;
        int[] mapping = new int[numTasks];
        boolean[] assigned = new boolean[numTasks];
        double[] vmReadyTime = new double[numVms];

        for (int count = 0; count < numTasks; count++) {
            int bestTask = -1;
            int bestVm = -1;
            double minCompletionTime = Double.MAX_VALUE;

            for (int t = 0; t < numTasks; t++) {
                if (assigned[t]) continue;
                for (int v = 0; v < numVms; v++) {
                    double ct = et[t][v] + vmReadyTime[v];
                    if (ct < minCompletionTime) {
                        minCompletionTime = ct;
                        bestTask = t;
                        bestVm = v;
                    }
                }
            }
            mapping[bestTask] = bestVm;
            assigned[bestTask] = true;
            vmReadyTime[bestVm] += et[bestTask][bestVm];
        }
        return mapping;
    }

    private static int[] tournamentSelection(int[][] population, double[][] et, Random rand) {
        int tournamentSize = 3;
        int[] best = null;
        double bestMakespan = Double.MAX_VALUE;
        for (int i = 0; i < tournamentSize; i++) {
            int[] candidate = population[rand.nextInt(population.length)];
            double ms = evaluateMakespan(candidate, et);
            if (ms < bestMakespan) {
                bestMakespan = ms;
                best = candidate;
            }
        }
        return best;
    }

    // =========================================================================
    // ALGORITMA 2: HYBRID MAX-MIN + ANT COLONY OPTIMIZATION (ACO)
    // =========================================================================
    private static int[] scheduleHybridMaxMinACO(double[][] et, Random rand) {
        int numTasks = et.length;
        int numVms = et[0].length;

        int numAnts = 25;
        int maxIterations = 50;
        double alpha = 1.0;
        double beta = 2.0;
        double rho = 0.2;
        double q = 100.0;

        int[] maxMinSolution = runMaxMin(et);
        int[] globalBest = Arrays.copyOf(maxMinSolution, numTasks);
        double globalBestMakespan = evaluateMakespan(globalBest, et);

        double[][] pheromone = new double[numTasks][numVms];
        for (int t = 0; t < numTasks; t++) {
            Arrays.fill(pheromone[t], 1.0);
            pheromone[t][maxMinSolution[t]] += 2.0;
        }

        double[][] heuristic = new double[numTasks][numVms];
        for (int t = 0; t < numTasks; t++) {
            for (int v = 0; v < numVms; v++) {
                heuristic[t][v] = 1.0 / (et[t][v] + 1e-6);
            }
        }

        for (int iter = 0; iter < maxIterations; iter++) {
            int[][] antSolutions = new int[numAnts][numTasks];
            double[] antMakespans = new double[numAnts];

            for (int k = 0; k < numAnts; k++) {
                for (int t = 0; t < numTasks; t++) {
                    antSolutions[k][t] = selectVmByProbability(t, pheromone, heuristic, alpha, beta, rand);
                }
                antMakespans[k] = evaluateMakespan(antSolutions[k], et);

                if (antMakespans[k] < globalBestMakespan) {
                    globalBestMakespan = antMakespans[k];
                    globalBest = Arrays.copyOf(antSolutions[k], numTasks);
                }
            }

            for (int t = 0; t < numTasks; t++) {
                for (int v = 0; v < numVms; v++) {
                    pheromone[t][v] *= (1.0 - rho);
                    if (pheromone[t][v] < 0.01) pheromone[t][v] = 0.01;
                }
            }

            double deposit = q / globalBestMakespan;
            for (int t = 0; t < numTasks; t++) {
                pheromone[t][globalBest[t]] += deposit;
            }
        }
        return globalBest;
    }

    private static int[] runMaxMin(double[][] et) {
        int numTasks = et.length;
        int numVms = et[0].length;
        int[] mapping = new int[numTasks];
        boolean[] assigned = new boolean[numTasks];
        double[] vmReadyTime = new double[numVms];

        for (int count = 0; count < numTasks; count++) {
            int selectedTask = -1;
            int selectedVm = -1;
            double maxOfMinCompletionTime = -1.0;

            for (int t = 0; t < numTasks; t++) {
                if (assigned[t]) continue;

                int bestVmForTask = -1;
                double minCt = Double.MAX_VALUE;
                for (int v = 0; v < numVms; v++) {
                    double ct = et[t][v] + vmReadyTime[v];
                    if (ct < minCt) {
                        minCt = ct;
                        bestVmForTask = v;
                    }
                }

                if (minCt > maxOfMinCompletionTime) {
                    maxOfMinCompletionTime = minCt;
                    selectedTask = t;
                    selectedVm = bestVmForTask;
                }
            }
            mapping[selectedTask] = selectedVm;
            assigned[selectedTask] = true;
            vmReadyTime[selectedVm] += et[selectedTask][selectedVm];
        }
        return mapping;
    }

    private static int selectVmByProbability(int task, double[][] pheromone, double[][] heuristic,
                                             double alpha, double beta, Random rand) {
        int numVms = pheromone[0].length;
        double[] probs = new double[numVms];
        double sum = 0.0;

        for (int v = 0; v < numVms; v++) {
            probs[v] = Math.pow(pheromone[task][v], alpha) * Math.pow(heuristic[task][v], beta);
            sum += probs[v];
        }

        double r = rand.nextDouble() * sum;
        double cumulative = 0.0;
        for (int v = 0; v < numVms; v++) {
            cumulative += probs[v];
            if (r <= cumulative) {
                return v;
            }
        }
        return numVms - 1;
    }

    private static double evaluateMakespan(int[] mapping, double[][] et) {
        int numVms = et[0].length;
        double[] vmLoad = new double[numVms];
        for (int t = 0; t < mapping.length; t++) {
            vmLoad[mapping[t]] += et[t][mapping[t]];
        }
        double maxLoad = 0.0;
        for (double load : vmLoad) {
            if (load > maxLoad) maxLoad = load;
        }
        return maxLoad;
    }

    private static MetricsResult calculateMetrics(String algoName, List<Cloudlet> list, int numVms, double algoRuntimeMs) {
        double makespan = 0.0;
        double totalExecutionTime = 0.0;
        double[] vmActiveTime = new double[numVms];

        for (Cloudlet c : list) {
            double finishTime = c.getFinishTime();
            double execTime = c.getActualCPUTime();
            int vmId = c.getVmId();

            if (finishTime > makespan) {
                makespan = finishTime;
            }
            totalExecutionTime += execTime;
            vmActiveTime[vmId] += execTime;
        }

        double throughput = list.size() / makespan;

        double tMax = Double.MIN_VALUE;
        double tMin = Double.MAX_VALUE;
        double sumVmTime = 0.0;
        for (double t : vmActiveTime) {
            if (t > tMax) tMax = t;
            if (t < tMin) tMin = t;
            sumVmTime += t;
        }
        double tAvg = sumVmTime / numVms;
        double degreeOfImbalance = (tMax - tMin) / (tAvg + 1e-9);

        double totalCost = totalExecutionTime * COST_PER_SEC;
        double resourceUtilization = (totalExecutionTime / ((makespan * numVms) + 1e-9)) * 100.0;

        return new MetricsResult(algoName, makespan, throughput, degreeOfImbalance, totalCost, resourceUtilization, algoRuntimeMs);
    }

    private static Datacenter createDatacenter(String name, int numHosts) throws Exception {
        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            List<Pe> peList = new ArrayList<>();
            peList.add(new Pe(0, new PeProvisionerSimple(100000)));
            hostList.add(new Host(i, new RamProvisionerSimple(32768), new BwProvisionerSimple(100000),
                    1000000, peList, new VmSchedulerTimeShared(peList)));
        }
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, COST_PER_SEC, 0.05, 0.001, 0.0);
        return new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), new LinkedList<>(), 0);
    }

    static class MetricsResult {
        String algoName;
        double makespan;
        double throughput;
        double degreeOfImbalance;
        double totalCost;
        double resourceUtilization;
        double algoRuntimeMs;

        MetricsResult(String algoName, double makespan, double throughput, double degreeOfImbalance,
                      double totalCost, double resourceUtilization, double algoRuntimeMs) {
            this.algoName = algoName;
            this.makespan = makespan;
            this.throughput = throughput;
            this.degreeOfImbalance = degreeOfImbalance;
            this.totalCost = totalCost;
            this.resourceUtilization = resourceUtilization;
            this.algoRuntimeMs = algoRuntimeMs;
        }
    }

    static class ComparisonRecord {
        int nTasks;
        String datasetName;
        MetricsResult ga;
        MetricsResult aco;

        ComparisonRecord(int nTasks, String datasetName, MetricsResult ga, MetricsResult aco) {
            this.nTasks = nTasks;
            this.datasetName = datasetName;
            this.ga = ga;
            this.aco = aco;
        }
    }
}
