# pagecacheIssue

用于模拟并对比以下 3 类高风险行为对文件增长和 page cache 的影响：
- `deploy-log`：持续追加部署日志（类似 `publish_version.log`）
- `temp-leak`：持续创建临时文件且不删除
- `rolling-log`：按大小滚动日志（无总量上限）
## 1. 打包
```bash
mvn -DskipTests package
```
产物：
- `target/pagecache-diagnosis-lab-1.0.0-jar-with-dependencies.jar`
## 2. 运行全部场景
```bash
java -jar target/pagecache-diagnosis-lab-1.0.0-jar-with-dependencies.jar --scenes=all --durationSeconds=20 --writeKbPerStep=64 --intervalMs=100 --baseDir=./pagecache-diagnosis-output
```
## 3. 仅运行单个场景
```bash
java -jar target/pagecache-diagnosis-lab-1.0.0-jar-with-dependencies.jar --scenes=deploy-log --durationSeconds=30
java -jar target/pagecache-diagnosis-lab-1.0.0-jar-with-dependencies.jar --scenes=temp-leak --durationSeconds=30
java -jar target/pagecache-diagnosis-lab-1.0.0-jar-with-dependencies.jar --scenes=rolling-log --durationSeconds=30
```

## 4. 输出结果
运行结束后会输出：
- 每个场景的新增文件数、目录增长量
- Linux 下 `/proc/meminfo` 中 `Cached` 的变化
- 总结排序（谁的增长最大）
  并在 `--baseDir` 下生成：
- `diagnosis-report.txt`
## 5. 建议
- 请在 Linux 目标机运行，才能看到 `Cached` 指标。
- 为了避免占用过多磁盘，可先用较小参数试跑（如 `durationSeconds=10`）。





下面给你一份**线上判责清单**，按顺序执行即可。目标是明确：到底是“日志持续写”还是“临时文件泄漏”主导了 page cache。

## 1) 先看全局缓存趋势（1分钟一采样）

```bash
watch -n 60 "date; cat /proc/meminfo | rg '^(MemTotal|MemFree|Buffers|Cached|SReclaimable|Shmem):'"
```

重点看：
- `Cached` 持续上涨且不回落
- `Shmem` 是否异常（排除共享内存影响）

## 2) 定位 Java 进程

```bash
jps -l
# 或
ps -ef | rg 'java|knowledge|mwa|swa'
```

记下目标 PID（下文用 `$PID`）。

## 3) 看该进程“打开了哪些文件”最多

```bash
lsof -p $PID | awk '{print $9}' | rg -v '^$' | sort | uniq -c | sort -nr | head -n 50
```

重点关注是否出现大量：
- `publish_version.log`
- `logs/*.log`
- `/tmp/temp*.json|md|html|png`

## 4) 直接统计可疑目录的容量与文件数

```bash
du -sh ./logs ./logs/third_app /tmp 2>/dev/null
```

```bash
find /tmp -maxdepth 1 -type f | rg 'temp.*\.(json|md|html|png)$' | wc -l
```

```bash
find /tmp -maxdepth 1 -type f | rg 'temp.*\.(json|md|html|png)$' | sed -n '1,20p'
```

如果 `/tmp` 同类临时文件持续增长，基本可以锁定泄漏链路。

## 5) 看日志目录增长速度（每分钟）

```bash
watch -n 60 "date; du -sh ./logs ./logs/third_app 2>/dev/null; ls -lt ./logs/third_app/*/*/logs/publish_version.log 2>/dev/null | head"
```

如果 `publish_version.log` 持续单调增长且无切分/清理，就是强信号。

## 6) 关联代码风险点（你项目里已命中）

- 临时 JSON 未清理：
    - `wa-engine-module/wa-knowledge-establish/src/main/java/com/ztesoft/knowledge/invoker/service/FileEstablishTaskService.java`
    - `wa-engine-module/wa-knowledge-establish/src/main/java/com/ztesoft/knowledge/face/FileConvertMdPdfComponent.java`
- 部署日志持续追加：
    - `wa-manager-module/wa-deploy/src/main/java/com/ztesoft/knowledge/deploy/helper/DeploymentLogHelper.java`
- 常规日志滚动缺少总量上限：
    - `conf_kf/logback.xml`

## 7) 快速判责规则（实战版）

- **规则A（临时文件泄漏）**：`/tmp` 同模式文件数持续上涨 + 容量持续上涨
- **规则B（日志主导）**：`logs` 或 `logs/third_app` 容量上涨最快，且增长主要来自 `.log`
- **规则C（混合）**：A/B 同时命中，按“增长速度更快”的目录先治理

## 8) 建议阈值（便于告警）

- `/tmp` 可疑文件每小时新增 > 5k：告警
- `publish_version.log` 单文件 > 500MB：告警
- `logs` 总量 > 磁盘 20%：告警
- `Cached` 连续 30 分钟上涨且无回落：告警

---

你可以先跑第 1~5 步，把输出贴我。我可以直接帮你做“**谁是主因 + 修复优先级**”的最终判断。