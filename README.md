# pagecacheIssue MVP

复现 **Undertow 文件上传临时文件句柄泄漏导致 Linux page cache 暴涨** 问题的最小可复现项目。

## 问题描述

Spring Boot + Undertow 处理 `multipart/form-data` 上传时，会把上传内容落到临时文件。如果业务代码通过 `MultipartFile.getInputStream()` 获取流后**没有关闭**，对应的文件描述符（FD）将一直保留。即使临时文件已被删除，Linux 内核仍会保留该 inode 的 page cache，导致容器/进程内存（`cache`）持续上涨。

## 项目结构

```
pagecacheIssue/
├── pom.xml                                    # Spring Boot 2.7 + Undertow
├── src/main/resources/application.properties  # 无限制上传大小，模拟生产放大问题
├── src/main/java/com/example/pagecache/
│   ├── PageCacheIssueApplication.java         # 启动类
│   ├── controller/
│   │   ├── UploadController.java              # 上传接口（故意不关闭流）
│   │   └── UploadFixedController.java         # 修复后的上传接口（try-with-resources）
│   └── util/Md5Util.java                      # MD5 计算（不关闭传入的流）
├── upload_stress_test.py                      # 压测泄漏接口 /upload
└── upload_stress_test_fixed.py                # 压测修复接口 /upload-fixed
```

## 复现步骤

### 1. 编译并启动服务

```bash
mvn clean package -DskipTests
java -jar target/pagecache-issue-1.0.0.jar
```

### 2. 对比压测

本项目提供两个接口，方便直观对比**泄漏**与**正常**的差异。

#### 2.1 压测泄漏接口 `/upload`（FD 会持续上涨）

```bash
pip install requests
python upload_stress_test.py
```

#### 2.2 压测修复接口 `/upload-fixed`（FD 保持稳定）

```bash
python upload_stress_test_fixed.py
```

默认参数：
- 并发数：`50`
- 总请求数：`5000`
- 单个文件大小：`512 KB`

可通过环境变量调整：
```bash
export CONCURRENCY=100
export TOTAL_REQUESTS=10000
export FILE_SIZE_KB=1024
python upload_stress_test.py        # 或 upload_stress_test_fixed.py
```

### 3. 查询进程 PID

先找到 Java 服务的进程 ID：

```bash
# 根据进程名查找
pgrep -f pagecache-issue

# 或根据端口查找（本服务默认 8080）
ss -tlnp | grep 8080

# 或查看所有 Java 进程
ps aux | grep java

# 如果服务跑在容器内，容器里通常只有一个 Java 进程，直接看 1 号或 java 进程即可
pidof java
```

记录下 PID（例如 `12345`），后续命令中的 `<pid>` 替换为该数字。

### 4. 观察现象

在 Linux 环境下（或容器内），执行以下命令持续观察：

```bash
# 持续观察 FD 总数（每 2 秒刷新）
watch -n 2 'ls /proc/<pid>/fd 2>/dev/null | wc -l'

# 持续观察 Undertow 相关 FD 数量
watch -n 2 'ls -l /proc/<pid>/fd | grep undertow | wc -l'

# 持续观察 FD 对应的 pos 偏移量（验证是真实磁盘文件）
watch -n 2 'find /proc/<pid>/fdinfo -type f -exec awk '"'"'/^pos:/ {sum+=$2; count++} END {print "Count:", count; print "Total GB:", sum/1024/1024/1024; print "Avg KB:", sum/count/1024}'"'"' {} +'

# 持续观察 cgroup 内存统计（cache 部分会显著上涨）
watch -n 2 'cat /sys/fs/cgroup/memory/memory.stat | grep -E "cache|rss|active_file"'

# 持续观察进程内存占用（MB）
watch -n 2 'cat /proc/<pid>/status | grep -E "VmRSS|VmSize"'

# 持续观察已删除但仍被占用的文件（deleted）
watch -n 2 'ls -l /proc/<pid>/fd | grep deleted'
```

如果系统没有 `watch` 命令，可用 `while` 循环代替（自动兼容 cgroup v1 / v2，内存格式化为 MB）：

```bash
PID=<pid>
while true; do
    echo "=== $(date '+%Y-%m-%d %H:%M:%S') ==="
    echo "FD total:    $(ls /proc/$PID/fd 2>/dev/null | wc -l)"
    echo "Undertow FD: $(ls -l /proc/$PID/fd 2>/dev/null | grep undertow | wc -l)"
    echo "Deleted FD:  $(ls -l /proc/$PID/fd 2>/dev/null | grep deleted | wc -l)"

    # VmRSS / VmSize (MB)
    awk '/VmRSS|VmSize/ {printf "%s: %.0f MB\n", $1, $2/1024}' /proc/$PID/status 2>/dev/null

    # cgroup v1
    if [ -f /sys/fs/cgroup/memory/memory.stat ]; then
        awk '
            /^cache /       {printf "cache:       %.1f MB\n", $2/1024/1024}
            /^rss /         {printf "rss:         %.1f MB\n", $2/1024/1024}
            /^active_file / {printf "active_file: %.1f MB\n", $2/1024/1024}
        ' /sys/fs/cgroup/memory/memory.stat
    fi

    # cgroup v2
    if [ -f /sys/fs/cgroup/memory.stat ]; then
        awk '
            /^file /        {printf "file(pagecache): %.1f MB\n", $2/1024/1024}
            /^anon /        {printf "anon(rss):       %.1f MB\n", $2/1024/1024}
        ' /sys/fs/cgroup/memory.stat
    fi

    echo ""
    sleep 2
done
```

### 5. 预期结果

- `ls /proc/<pid>/fd | wc -l` 持续增长，远超正常 Web 进程的数百量级。
- `ls -l /proc/<pid>/fd | grep undertow | wc -l` 占绝大多数。
- `memory.stat` 中 `cache` / `active_file` 随请求量线性上涨。
- 即使临时文件已被删除（`lsof +L1` 可见 deleted），page cache 仍无法回收。

## 根因代码

### UploadController.java

```java
@PostMapping("/upload")
public String upload(@RequestParam("file") MultipartFile file) {
    try {
        InputStream is = file.getInputStream();  // 获取流
        String md5 = Md5Util.md5Hex(is);         // 使用后未关闭
        return "upload success, md5=" + md5 + ", size=" + file.getSize();
    } catch (Exception e) {
        return "upload failed: " + e.getMessage();
    }
}
```

### Md5Util.java

```java
public static String md5Hex(InputStream inputStream) throws IOException {
    try {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] buffer = new byte[8192];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            md.update(buffer, 0, len);
        }
        return bytesToHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
    }
    // 未关闭 inputStream
}
```

## 修复方案

使用 `try-with-resources` 确保流在使用后被关闭：

```java
@PostMapping("/upload")
public String upload(@RequestParam("file") MultipartFile file) {
    try (InputStream is = file.getInputStream()) {
        String md5 = Md5Util.md5Hex(is);
        return "upload success, md5=" + md5 + ", size=" + file.getSize();
    } catch (Exception e) {
        return "upload failed: " + e.getMessage();
    }
}
```

如果第三方工具方法不关闭流，调用方必须负责关闭：

```java
try (InputStream is = file.getInputStream()) {
    String md5 = Md5Util.md5Hex(is);
}
```

## 总结

| 指标 | 异常表现 |
|------|----------|
| FD 总数 | 远超正常，持续增长 |
| Undertow FD | 占绝大多数（如 97%+） |
| `memory.usage_in_bytes` | 持续上涨 |
| `cache` / `active_file` | 占内存大头 |
| `rss` | 相对正常 |

## 对比效果

启动服务后，分别对两个接口压测，观察应用日志：

### 泄漏版 `/upload`

```
[UPLOAD] file=tmp1.bin, size=512KB, md5=..., md5Cost=8ms, fdBefore=42, fdAfter=43, fdLeak=1
[UPLOAD] file=tmp2.bin, size=512KB, md5=..., md5Cost=7ms, fdBefore=43, fdAfter=44, fdLeak=1
[UPLOAD] file=tmp3.bin, size=512KB, md5=..., md5Cost=9ms, fdBefore=44, fdAfter=45, fdLeak=1
...
```

**特征**：`fdLeak` 持续为 `1`（或更大），`fdAfter` 单调递增。

### 修复版 `/upload-fixed`

```
[UPLOAD-FIXED] file=tmp1.bin, size=512KB, md5=..., md5Cost=8ms, fdBefore=42, fdAfter=42, fdLeak=0
[UPLOAD-FIXED] file=tmp2.bin, size=512KB, md5=..., md5Cost=7ms, fdBefore=42, fdAfter=42, fdLeak=0
[UPLOAD-FIXED] file=tmp3.bin, size=512KB, md5=..., md5Cost=9ms, fdBefore=42, fdAfter=42, fdLeak=0
...
```

**特征**：`fdLeak` 始终为 `0`，`fdAfter` 保持稳定。

一句话：**不是 JVM 堆泄漏，而是文件句柄泄漏导致 page cache 无法回收。**
