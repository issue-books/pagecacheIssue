package com.example;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * page cache 问题定位实验程序。
 *
 * <p>用途：把几个高风险模式单独跑起来，对比每个模式造成的文件增长和系统缓存变化，帮助快速判断最可能的根因。</p>
 */
public class PageCacheDiagnosisApplication {

    private static final String SCENE_DEPLOY_LOG = "deploy-log";
    private static final String SCENE_TEMP_LEAK = "temp-leak";
    private static final String SCENE_ROLLING_LOG = "rolling-log";
    private static final List<String> ALL_SCENES = Arrays.asList(
            SCENE_DEPLOY_LOG, SCENE_TEMP_LEAK, SCENE_ROLLING_LOG
    );

    private static final Random RANDOM = new Random();

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        Files.createDirectories(config.baseDir);

        System.out.println("== pagecache-diagnosis-lab ==");
        System.out.println("工作目录: " + config.baseDir.toAbsolutePath());
        System.out.println("场景: " + config.scenes);
        System.out.println("每个场景时长(秒): " + config.durationSeconds);
        System.out.println("每次写入(KB): " + config.writeKbPerStep);
        System.out.println("写入间隔(ms): " + config.intervalMs);
        System.out.println();

        List<SceneResult> results = new ArrayList<SceneResult>();
        for (String scene : config.scenes) {
            SceneResult result = runScene(scene, config);
            results.add(result);
        }

        printSummary(results);
        writeReport(config.baseDir.resolve("diagnosis-report.txt"), config, results);
    }

    private static SceneResult runScene(String scene, Config config) throws Exception {
        System.out.println("---- 开始场景: " + scene + " ----");
        Path sceneDir = config.baseDir.resolve(scene + "-" + System.currentTimeMillis());
        Files.createDirectories(sceneDir);

        Snapshot before = Snapshot.capture(sceneDir);
        long start = System.currentTimeMillis();
        long endTime = start + config.durationSeconds * 1000L;

        if (SCENE_DEPLOY_LOG.equals(scene)) {
            runDeployLogScenario(sceneDir, endTime, config);
        } else if (SCENE_TEMP_LEAK.equals(scene)) {
            runTempLeakScenario(sceneDir, endTime, config);
        } else if (SCENE_ROLLING_LOG.equals(scene)) {
            runRollingLogScenario(sceneDir, endTime, config);
        } else {
            throw new IllegalArgumentException("未知场景: " + scene);
        }

        Snapshot after = Snapshot.capture(sceneDir);
        SceneResult result = SceneResult.from(scene, sceneDir, before, after, start, System.currentTimeMillis());
        printSceneResult(result);
        return result;
    }

    /**
     * 模拟部署日志持续追加（类似 publish_version.log）。
     */
    private static void runDeployLogScenario(Path sceneDir, long endTime, Config config) throws Exception {
        Path logFile = sceneDir.resolve(Paths.get("logs", "third_app", "demoApp", "1001", "logs", "publish_version.log"));
        Files.createDirectories(logFile.getParent());

        while (System.currentTimeMillis() < endTime) {
            String payload = now() + " [EXECUTE_SCRIPT] " + randomPayload(config.writeKbPerStep) + System.lineSeparator();
            Files.write(logFile, payload.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Thread.sleep(config.intervalMs);
        }
    }

    /**
     * 模拟临时文件创建后不删除。
     */
    private static void runTempLeakScenario(Path sceneDir, long endTime, Config config) throws Exception {
        Path tempDir = sceneDir.resolve("tmp");
        Files.createDirectories(tempDir);

        while (System.currentTimeMillis() < endTime) {
            Path tmp = tempDir.resolve("temp_" + System.currentTimeMillis() + "_" + RANDOM.nextInt(10000) + ".json");
            String payload = "{\"data\":\"" + randomPayload(config.writeKbPerStep) + "\"}";
            Files.write(tmp, payload.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            Thread.sleep(config.intervalMs);
        }
    }

    /**
     * 模拟按大小滚动但缺少总量上限的日志模式。
     */
    private static void runRollingLogScenario(Path sceneDir, long endTime, Config config) throws Exception {
        Path logDir = sceneDir.resolve("logs");
        Files.createDirectories(logDir);

        long maxFileBytes = config.rollingMaxFileMb * 1024L * 1024L;
        int currentIndex = 0;
        Path current = logDir.resolve("app." + dateOnly() + "." + currentIndex + ".log");
        Files.createFile(current);

        while (System.currentTimeMillis() < endTime) {
            String line = now() + " INFO " + randomPayload(config.writeKbPerStep) + System.lineSeparator();
            Files.write(current, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            if (Files.size(current) >= maxFileBytes) {
                currentIndex++;
                current = logDir.resolve("app." + dateOnly() + "." + currentIndex + ".log");
                if (!Files.exists(current)) {
                    Files.createFile(current);
                }
            }
            Thread.sleep(config.intervalMs);
        }
    }

    private static void printSceneResult(SceneResult r) {
        System.out.println("场景完成: " + r.scene);
        System.out.println("目录: " + r.sceneDir.toAbsolutePath());
        System.out.println("耗时: " + r.durationMs + " ms");
        System.out.println("新增文件数: " + r.fileCountDelta);
        System.out.println("新增目录大小: " + formatBytes(r.bytesDelta));
        if (r.cachedKbBefore >= 0 && r.cachedKbAfter >= 0) {
            long delta = r.cachedKbAfter - r.cachedKbBefore;
            System.out.println("Linux Cached变化: " + delta + " KB (before=" + r.cachedKbBefore + ", after=" + r.cachedKbAfter + ")");
        } else {
            System.out.println("Linux Cached变化: 当前系统不可采集(/proc/meminfo不可用)");
        }
        System.out.println();
    }

    private static void printSummary(List<SceneResult> results) {
        System.out.println("==== 综合结论 ====");
        List<SceneResult> sorted = new ArrayList<SceneResult>(results);
        Collections.sort(sorted, new Comparator<SceneResult>() {
            @Override
            public int compare(SceneResult o1, SceneResult o2) {
                return Long.compare(o2.bytesDelta, o1.bytesDelta);
            }
        });

        for (int i = 0; i < sorted.size(); i++) {
            SceneResult r = sorted.get(i);
            System.out.println((i + 1) + ". " + r.scene + " -> 大小增量 " + formatBytes(r.bytesDelta)
                    + ", 文件增量 " + r.fileCountDelta);
        }
        if (!sorted.isEmpty()) {
            System.out.println("最可疑场景: " + sorted.get(0).scene);
        }
        System.out.println();
    }

    private static void writeReport(Path reportPath, Config config, List<SceneResult> results) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(reportPath,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), StandardCharsets.UTF_8))) {
            writer.write("pagecache-diagnosis-lab 报告");
            writer.newLine();
            writer.write("运行时间: " + now());
            writer.newLine();
            writer.write("基础目录: " + config.baseDir.toAbsolutePath());
            writer.newLine();
            writer.write("场景: " + config.scenes);
            writer.newLine();
            writer.write("参数: durationSeconds=" + config.durationSeconds + ", writeKbPerStep=" + config.writeKbPerStep
                    + ", intervalMs=" + config.intervalMs + ", rollingMaxFileMb=" + config.rollingMaxFileMb);
            writer.newLine();
            writer.newLine();

            for (SceneResult r : results) {
                writer.write("[" + r.scene + "]");
                writer.newLine();
                writer.write("sceneDir=" + r.sceneDir.toAbsolutePath());
                writer.newLine();
                writer.write("durationMs=" + r.durationMs);
                writer.newLine();
                writer.write("fileCountDelta=" + r.fileCountDelta);
                writer.newLine();
                writer.write("bytesDelta=" + r.bytesDelta + " (" + formatBytes(r.bytesDelta) + ")");
                writer.newLine();
                writer.write("cachedKbBefore=" + r.cachedKbBefore + ", cachedKbAfter=" + r.cachedKbAfter);
                writer.newLine();
                writer.newLine();
            }
        }
        System.out.println("报告已生成: " + reportPath.toAbsolutePath());
    }

    private static String randomPayload(int kb) {
        int len = Math.max(1, kb * 1024);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return sb.toString();
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
    }

    private static String dateOnly() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.2f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.2f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.2f GB", gb);
    }

    private static final class Config {
        private final Path baseDir;
        private final List<String> scenes;
        private final int durationSeconds;
        private final int writeKbPerStep;
        private final int intervalMs;
        private final int rollingMaxFileMb;

        private Config(Path baseDir, List<String> scenes, int durationSeconds, int writeKbPerStep,
                       int intervalMs, int rollingMaxFileMb) {
            this.baseDir = baseDir;
            this.scenes = scenes;
            this.durationSeconds = durationSeconds;
            this.writeKbPerStep = writeKbPerStep;
            this.intervalMs = intervalMs;
            this.rollingMaxFileMb = rollingMaxFileMb;
        }

        private static Config fromArgs(String[] args) {
            Map<String, String> kv = new LinkedHashMap<String, String>();
            for (String arg : args) {
                if (arg != null && arg.startsWith("--") && arg.contains("=")) {
                    int idx = arg.indexOf('=');
                    kv.put(arg.substring(2, idx), arg.substring(idx + 1));
                }
            }

            Path baseDir = Paths.get(kv.containsKey("baseDir") ? kv.get("baseDir") : "./pagecache-diagnosis-output");
            int durationSeconds = Integer.parseInt(kv.containsKey("durationSeconds") ? kv.get("durationSeconds") : "20");
            int writeKbPerStep = Integer.parseInt(kv.containsKey("writeKbPerStep") ? kv.get("writeKbPerStep") : "64");
            int intervalMs = Integer.parseInt(kv.containsKey("intervalMs") ? kv.get("intervalMs") : "100");
            int rollingMaxFileMb = Integer.parseInt(kv.containsKey("rollingMaxFileMb") ? kv.get("rollingMaxFileMb") : "10");
            String scenesArg = kv.containsKey("scenes") ? kv.get("scenes") : "all";

            List<String> scenes;
            if ("all".equalsIgnoreCase(scenesArg)) {
                scenes = new ArrayList<String>(ALL_SCENES);
            } else {
                scenes = new ArrayList<String>();
                for (String scene : scenesArg.split(",")) {
                    String s = scene.trim();
                    if (!ALL_SCENES.contains(s)) {
                        throw new IllegalArgumentException("不支持的scenes参数: " + s + "，可选: " + ALL_SCENES);
                    }
                    scenes.add(s);
                }
            }
            return new Config(baseDir, scenes, durationSeconds, writeKbPerStep, intervalMs, rollingMaxFileMb);
        }
    }

    private static final class Snapshot {
        private final long fileCount;
        private final long bytes;
        private final long cachedKb;

        private Snapshot(long fileCount, long bytes, long cachedKb) {
            this.fileCount = fileCount;
            this.bytes = bytes;
            this.cachedKb = cachedKb;
        }

        private static Snapshot capture(Path dir) throws IOException {
            if (!Files.exists(dir)) {
                return new Snapshot(0, 0, readLinuxCachedKb());
            }
            Counter counter = new Counter();
            FileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    counter.fileCount++;
                    counter.bytes += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            };
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, visitor);
            return new Snapshot(counter.fileCount, counter.bytes, readLinuxCachedKb());
        }
    }

    private static final class Counter {
        private long fileCount;
        private long bytes;
    }

    private static final class SceneResult {
        private final String scene;
        private final Path sceneDir;
        private final long durationMs;
        private final long fileCountDelta;
        private final long bytesDelta;
        private final long cachedKbBefore;
        private final long cachedKbAfter;

        private SceneResult(String scene, Path sceneDir, long durationMs, long fileCountDelta, long bytesDelta,
                            long cachedKbBefore, long cachedKbAfter) {
            this.scene = scene;
            this.sceneDir = sceneDir;
            this.durationMs = durationMs;
            this.fileCountDelta = fileCountDelta;
            this.bytesDelta = bytesDelta;
            this.cachedKbBefore = cachedKbBefore;
            this.cachedKbAfter = cachedKbAfter;
        }

        private static SceneResult from(String scene, Path sceneDir, Snapshot before, Snapshot after,
                                        long startMs, long endMs) {
            return new SceneResult(
                    scene,
                    sceneDir,
                    endMs - startMs,
                    after.fileCount - before.fileCount,
                    after.bytes - before.bytes,
                    before.cachedKb,
                    after.cachedKb
            );
        }
    }

    private static long readLinuxCachedKb() {
        Path memInfo = Paths.get("/proc/meminfo");
        if (!Files.exists(memInfo)) {
            return -1L;
        }
        try {
            List<String> lines = Files.readAllLines(memInfo, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("Cached:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]);
                    }
                }
            }
        } catch (Exception ignored) {
            return -1L;
        }
        return -1L;
    }
}
