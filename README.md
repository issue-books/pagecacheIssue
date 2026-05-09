# pagecacheIssue

用于模拟并对比以下 3 类高风险行为对文件增长和 page cache 的影响：
- `deploy-log`：持续追加部署日志（类似 `publish_version.log`）
- `temp-leak`：持续创建临时文件且不删除
- `rolling-log`：按大小滚动日志（无总量上限）

下面给你一份**线上判责清单**，按顺序执行即可。目标是明确：到底是“日志持续写”还是“临时文件泄漏”主导了 page cache。

## 先看全局缓存趋势（1分钟一采样）

```bash
watch -n 60 "date; cat /proc/meminfo | grep '^(MemTotal|MemFree|Buffers|Cached|SReclaimable|Shmem):'"
```

重点看：
- `Cached` 持续上涨且不回落
- `Shmem` 是否异常（排除共享内存影响）

## 定位 Java 进程

```bash
jps -l
# 或
ps -ef | grep 'java|knowledge|mwa|swa'
```

记下目标 PID（下文用 `$PID`）。

## 看该进程“打开了哪些文件”最多

```bash
lsof -p $PID | awk '{print $9}' | grep -v '^$' | sort | uniq -c | sort -nr | head -n 50
```


## 问题
~~~ bash
ls /proc/7/fd 2>/dev/null | wc -l
84240

ls -l /proc/7/fd | grep undertow | wc -l
82117
~~~

这组输出已经把**主因方向**缩得很小了，而且也能解释为什么你之前跑 `lsof` 会“等很久”。

## 1. `84240` 个 fd 意味着什么

对单个 JVM 进程来说，**8 万多个打开的文件描述符极不正常**。  
`lsof -p 7 | sort | uniq -c ...` 要对 **8 万多行**做排序统计，自然会慢。

所以：**不是命令坏了，是进程 fd 规模已经失控**。

## 2. `(deleted)` 的 Undertow 上传临时文件是什么

你看到的是典型形态：

`/data/nknowledge/fileTmp/undertowXXXXXXXXupload (deleted)`

含义是：

- 这是 **Undertow（Spring Boot 若用 Undertow 作为容器）处理 multipart 上传**时落在磁盘上的临时文件；
- 路径上标 **`(deleted)`** 表示：目录项已从文件系统删除，但**仍有 fd 没关**，内核还占着这个 inode（所以进程里还能看到路径 + deleted）；
- **大量 `(deleted)` 且 fd 数爆炸**，高度符合：**上传临时文件对应的流 / `MultipartFile` / `HttpServletRequest` 没有被完整消费并关闭**，或异常路径下没有走到释放逻辑，导致临时文件句柄泄漏。

这和“page cache 很大”可以同时存在：  
历史上这些文件被读写时会把数据留在 page cache；更关键的是 **fd + 未释放的已删文件** 本身就是严重资源问题（有时比单纯 cache 更致命）。

## 3. `xxx.logging.file_IS_UNDEFINED` 说明什么

这行说明 **logback 里引用了 `${xxx.logging.file}`，但运行时没配这个属性**，于是落成了一个字面文件名 `xxx.logging.file_IS_UNDEFINED`。  
这是**配置问题**，可能带来异常日志文件；但和你现在 **8 万 fd + undertow upload** 相比，**优先级通常低于上传句柄泄漏**。

## 结论:
1. Java 里「谁负责关」本来就容易混
   InputStream/OutputStream 实现了 Closeable：原则上 打开的一方 或 消费完的一方 要关，但很多库（HTTP 客户端、MinIO SDK、EasyExcel、ImageIO）文档写得不显眼，有人以为「框架会帮我关」。
   ImageIO.read(InputStream) 明确 不会 关闭入参流；MultipartFile.getInputStream() 每次可能对应 磁盘临时文件上的一个 fd，不关就等于 Undertow 临时文件一直挂着——这和「会不会用 FileInputStream」不是同一层面的直觉。
   所以问题不全是「不懂流」，而是 没把「每个 getInputStream() 对应什么资源」对齐到「必须 try-with-resources」。

2. MultipartFile 把「真实 fd」藏起来了
   业务代码里常见写法是「拿流 → 传给 A → 再传给 B」，看起来像普通 IO，实际上是：

背后可能是 Undertow 的临时文件；
同一次上传 还会 多次 getInputStream()（上传一次、算 MD5 又一次），任何一次没关都会放大成 fd 泄漏。
这类问题 静态分析也不总报，不压测 /proc/<pid>/fd 很难发现。

3. 工程上的常见原因（比「不懂流」更常见）
   复制粘贴：从「小文件、内存 Multipart」抄到「大文件、磁盘 Multipart」环境，风险完全不同。
   异常路径：try 里中途 return/throw，finally 没关流。
   「委托给 SDK」：以为 putObject(stream) 一定关流——多数会关，但 异常或二次读流 时仍可能留下边角。
   缺少统一规范：没有在评审里强制「凡 getInputStream() 必 try-with-resources 或必交给唯一一个会关闭的 API」。
4. 可以怎么从团队层面避免
   规范：所有 MultipartFile / getInputStream()：要么 try (InputStream in = file.getInputStream()) { ... }，要么 先 transferTo(File) 再只操作文件，避免多次从 Part 取流。
   上传 + 校验 MD5：尽量 一次读流（或信任存储端 etag），避免「上传后再读一遍算 MD5」这种双开流模式。
   CR/清单：涉及 multipart 的 PR 必查 fd、必看 finally 和异步里是否还握着 MultipartFile。
   简短结论：根因里当然有对流/API 契约不熟的情况，但更典型的是 MultipartFile + 多 SDK + 多次读流 把问题放大了；用 try-with-resources 作为默认写法、减少「同一 Part 多次 getInputStream()」，比单纯强调「多学 IO」更有效。