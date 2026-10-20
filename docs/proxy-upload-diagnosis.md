# 代理上传方向异常排查结论

- 报告日期：2026-10-20
- 影响范围：反向代理（`com.nexhome.module.proxy`）**上传/请求体方向**；HTTPS 前端（HTTP/2）几乎不可用，明文 HTTP/1.1 前端正常
- 现场证据：`nexhome-memdiag-2026-10-20T120006897Z.json`、内存诊断页截图、LibreSpeed/OpenList 实测截图
- 当前状态：三个缺陷（A/B/C）与顺带发现的定界缺陷均已落地修复；13 项针对性回归全通过，高 RTT 吞吐与慢后端内存上界均有实测数据背书；**线上已部署验证：OpenList 上传 3.28 → 约 100 MB/s，LibreSpeed 经代理上传 0.01 → 990 Mbit/s**

---

## 1. 现象（用户实测）

| 场景 | 下载 | 上传（修复前） | 上传（修复后） |
|---|---|---|---|
| 经代理访问 OpenList（HTTPS → h2） | 40~60 MB/s | **约 3.28 MB/s**（9.50 GB ISO 长时间停在"上传中"） | **约 100 MB/s** |
| 经代理访问 LibreSpeed（`speedtt.pi.mmxx.fun`，HTTPS → h2） | 818 / 656 / 641 Mbit/s | **0.00 / 0.01 Mbit/s** | **990 Mbit/s**（同次下载 605） |
| 直连 LibreSpeed（`pi.mmxx.fun:27701`，无代理） | 1004 Mbit/s | 1028 Mbit/s | — |

同一后端直连两端均正常，问题确定在代理数据面；测试过程中容器出现"堆内存高频 GC"。
修复后代理上传（990）已追平直连（1028），与下载不再存在数量级差异。

---

## 2. 关于"高频 GC"：是症状，不是根因

从 `memdiag` JSON 取到的采样数据（采样区间为压测进行中）：

- GC 增量：**2~4 次 / 10 秒**，`gcMs` **3~14 ms**；进程累计 160 次 / 927 ms
- JVM 堆 56.0 MB / 已提交 151.1 MB / 上限 **199.2 MB**（`-XX:MaxRAMPercentage=40.0`，容器 512 MB）
- NIO 直接缓冲 0.4 MB / 容量 0.4 MB（27 个），`-XX:MaxDirectMemorySize=64m`
- 分配器：`PooledByteBufAllocator(directByDefault=true, 2 arena × 2 子池, 4 MB chunk)`

即：**GC 频率与耗时都不足以解释"上传为 0"**。真正发生的是——请求体在代理内被无界堆积（见 §4 缺陷 B），池化直内存缓冲被占满后触发分配失败/回收压力，表现为堆内存抖动与 GC 次数上升。**修好数据面，GC 现象随之消失；反过来先调 GC/堆参数不会改善上传。**

---

## 3. 复现环境（可重跑）

脚手架均在 `D:\tmp\nexhome-upbench\`：

```
node bench.mjs  <sizeMB> <h1|h2|both>   # 上传吞吐对比；RTT_MS=40 走延迟中继，H2WIN=N 覆盖流控窗口
node regress.mjs                        # 16 项针对性回归（报告 regress-report.json）
node mem.mjs  <streams> <mbEach>        # 慢后端停顿下的内存上界，H2WIN=N 对比不同窗口
node sp-dl.mjs                          # 线上下载分诊：direct / proxy-h1 / proxy-h2；SP_SIZE SP_WIN SP_CONNS
node dl-local.mjs                       # 本地下载复现：ONLY=h1,h2,h2x GRAD=1,2,4 ROUNDS=3 SEC=6 JVM_ARGS
java -cp target/nexhome.jar AlpnProbe.java   # 验证站点 frontProtocol 对 ALPN 的实际影响（需 data/certs/task-9001 证书）
```

- mock 后端 `127.0.0.1:19001`：统计收到的请求体字节数，收满才响应
- 启动 `target/nexhome.jar`（每次运行重建 `data/` 以获得初始密码）
- 面板 `18090` 登录 → 开启代理（HTTP 18080 / HTTPS 18443）→ 建站点 `up.test → http://127.0.0.1:19001`
- 同一后端、同一台机器，只改**前端协议**：h1 直连 18080 明文；h2 走 18080 的 h2c 预面（与线上 HTTPS+ALPN 协商到 h2 的路径同一条 `ProxyHandler(isH2=true)`）
- 30 秒 STALL 看门狗 + 每秒后端收字节采样，结果写 `result.json`

> 复现过程中修正的两个脚手架缺陷（不影响结论，但影响读数）：
> 1. `req.write()` 返回 `false` 表示"已入队但请暂停"，字节**已经接受**；早期 pump 未计入该块，在触发 `drain` 后重复发送同一块（曾测出后端收到 5.4 GB 的假象）。
> 2. Node 的 h2 客户端不会自动补 `content-length`，需显式带上（浏览器定长上传也会带）。
>
> 另：原先的 81 项全量 E2E 脚手架位于 `target/proxy-e2e/`（不入库），本轮取 jar 时已被构建产物目录清理抹掉，因此改用 13 项针对性回归（`regress.mjs`，只盖本次改动的风险面）。

### 修复前实测（回归版本）

```
h1-upload : 32 MB →  446 ms / 71.7 MB/s ，后端收到 32.0 MB
h2-upload : 32 MB → 11272 ms /  2.8 MB/s ，后端只收到 0.1 MB，
            每秒采样恒为 0.1 → 0.1 → 0.1 ...（后端整段时间没拿到任何请求体）
```

**2.8 MB/s + 后端 0.1 MB** 与用户报告的 **3.28 MB/s + LibreSpeed 0.00 Mbit/s** 一一对应。

---

## 4. 根因

### 缺陷 A（决定性）：h2 前端的请求体永不 flush

引入自提交 `15e8b83 优化代理性能`（2026-10-19 22:25）。该提交把上传方向从"每块 `writeAndFlush`"改为"每读批次 `write` + 在 `channelReadComplete` 里统一 flush"，以降低系统调用次数；但统一 flush 的那段代码带了 `!isH2` 守卫：

`ProxyHandler.channelReadComplete()`（修复前）

```java
Tx t = tx;
if (!isH2 && t != null && !t.done.get() && t.backendReady && ...) {
    t.backend.flush();                 // ← h2 永远进不来
    if (t.backend.isWritable()) ctx.read();
}
if (!isH2 && t != null && !t.done.get() && t.requestBodyDone && ...) {
    ctx.read();                        // ← h2 的续读泵同样被掐断
}
```

而 `handleContent()` 明确把 flush 交给了上面这段：

```java
} else if (tx.backendReady && tx.backend != null) {
    tx.backend.write(c.retain());      // 只 write，不 flush
    if (c instanceof LastHttpContent) {
        tx.backend.flush();            // 全请求只有收尾这一次 flush
        tx.requestBodyDone = true;
    }
}
```

后果：

1. 前端只要被 ALPN/预面判为 **h2**（`SslStore` 服务端 ALPN 通告 `h2, http/1.1`，Chrome 必然选中 h2），请求体除 `LastHttpContent` 那一次外**永远不会刷到 socket**——字节全压在回源通道的 `ChannelOutboundBuffer` 里。
2. 后端等不到完整请求体 → 不产生响应；只有客户端把整个 body 发完（触发 `LastHttpContent`）才有机会成功，而大文件/长上传永远到不了那一步，或到达前已被超时/内存压垮。
3. 上传越大、时间越长，堆积越多 → 直接对接 §2 的内存/GC 现象。

下载方向不受影响，因为 `BackendRelay.channelReadComplete()` 的 flush **没有** `!isH2` 守卫：

```java
if (headSeen && tx != null) tx.front.flush();   // 两协议都刷 → 下载 40~60 MB/s 正常
```

这正好解释了"下载生龙活虎、上传几乎为 0"这种单侧症状组合。

### 缺陷 B：h2 前端没有背压，缓冲无界

`beginRelay()` 同样只在 h1 关自动读：

```java
if (!isH2) ctx.channel().config().setAutoRead(false);
...
if (!isH2) { ctx.read(); }
```

h1 靠 `autoRead=false` + 显式 `ctx.read()` 形成"后端不可写就停止拉取"的停等泵；h2 保持 `autoRead=true`，于是：

- 回源建连/TLS 握手期间到达的请求体全部进 `tx.pendingBody`，**无上限**；
- 缺陷 A 让后端出站缓冲只进不出，两者叠加 → 单个大上传可把数十 MB 体压进池化直内存/堆缓冲。

### 缺陷 C：h2 流控窗口 64 KB 封顶上传速率（已实测确认，非推算）

Netty `Http2FrameCodecBuilder.forServer()` 的本地窗口取 RFC 基线 **65535 字节**，且 h2 的 `WINDOW_UPDATE` **只在 `AbstractHttp2StreamChannel.beginRead()` 里发出**（已核对 netty-codec-http2 4.2.19 源码 `beginRead → updateLocalWindowIfNeeded`）。h2 窗口是信用制，稳态吞吐 ≈ **窗口 / RTT**：

\[
\frac{65536\ \text{B}}{20\ \text{ms}} = 3.28\ \text{MB/s}
\]

与用户 OpenList 实测 **3.28 M/s** 完全吻合。为验证这条（环回 RTT≈0 测不出上限），在 bench 里加了一个**应用层延迟 TCP 中继**（`RTT_MS=40` → 每方向 20ms，client → 18085 → 18080），16MB PUT、后端收满、均返回 200：

| h2 本地窗口 | 往返 40ms | 往返 80ms |
|---|---|---|
| 65535（Netty 默认） | **0.6 MB/s**（16MB 用 26.8 秒） | — |
| 512 KB | 6.8 MB/s | 4.3 MB/s |
| 1 MB | 10.9 MB/s | — |
| 2 MB | 18.1 MB/s | — |

速率随窗口近线性、随 RTT 反比衰减 → 「窗口/RTT」成立。修完 A/B 之后，**高 RTT 链路的单流上传仍会被这条硬上限钉住**（LibreSpeed 从 0 抬到十几 Mbit/s，仍远低于直连 1028 Mbit/s），必须靠抬高窗口解决。

> 注：实测 0.6 MB/s 低于理论 1.6 MB/s，因为停等泵每次只放开「本批次已消费字节」对应的信用，有效信用不足满窗；这不影响「窗口是硬上限」的结论。

---

## 5. 修复方案与进度

| # | 缺陷 | 改动 | 状态 |
|---|---|---|---|
| 1 | A | `channelReadComplete()` 的两处 `!isH2` 守卫去掉，h1/h2 共用"批次 flush + 按需续读"泵 | **已落地** |
| 2 | B | `beginRelay()` 对 h2 同样 `setAutoRead(false)` + 显式 `ctx.read()`（h2 下即"不 read 就不放开窗口"的真背压） | **已落地** |
| 3 | C | 前端 h2 本地流控窗口默认抬到 **1 MB**（`ProxyCenter.installHttp2`，`-Dnx.proxy.h2StreamWin` 可调，0 或 65535 回退 Netty 默认）；连接级窗口由 Netty 按“基线 + 2×增量”自动放大 | **已落地 + 已 A/B** |
| 4 | 定界 | h2 前端无 `content-length` 时回源声明 chunked（见 §7） | **已落地** |
| 5 | 下载 | 站点级前端协议开关 `frontProtocol`（h2 / http1，ALPN 只通告 http/1.1） | **已落地 + ALPN 实测** |
| 6 | 回归 | `regress.mjs` 16 项：GET 无体、定长/chunked 上传、h1 流水线、h2 同连接 4 流并发、h2 并发下载、后端连不上、上传中途断开、frontProtocol 往返 | **16/16 通过** |

已落地改动的复现验证（`node bench.mjs 64 both`，同机环回）：

```
h1-upload : 64 MB → 550 ms / 116.4 MB/s ，后端 64 MB
h2-upload : 64 MB → 772 ms /  82.9 MB/s ，后端 64 MB ，status 200 resp "ok:67108864"
            （缺陷 A/B 修复前：11272 ms / 2.8 MB/s / 后端 0.1 MB）

高 RTT（延迟中继 RTT≈40ms）h2-upload 64 MB：14.2 MB/s（4496 ms）
            （窗口 64KB 同场景：0.6 MB/s）→ 约 23 倍
```

线上部署后的实测回测（修复后）：

- **OpenList**（HTTPS → h2，单流）：下载约 45 MB/s，**上传约 100 MB/s**（修复前 3.28 MB/s，约 30 倍）；上传已反超下载，代理不再是瓶颈；
- **LibreSpeed**（`speedtt.pi.mmxx.fun`，HTTPS → h2）：上传 **990 Mbit/s**（修复前 0.00/0.01）、下载 605 Mbit/s、Ping 2.10 ms / Jitter 0.36 ms；上传已追平直连的 1028 Mbit/s。

> 本地推断值（按 3.28 MB/s 反推 RTT≈20ms → 预期 20~40 MB/s）偏保守：OpenList 实际跑在局域网（Ping 2.1 ms），RTT 远小于 20ms，1MB 窗口下上限达数百 MB/s，故实测能到 100 MB/s。窗口抬升这个结论仍成立（低 RTT 下本就不受限，高 RTT 链路才需要它）。

选择"h2 也走 autoRead=false 停等泵"的依据（netty 4.2.19 源码核对）：

- `beginRead() → updateLocalWindowIfNeeded()`：窗口只在读时放开 → 停读即真背压，客户端被流控顶住，不在代理内堆积；
- `doBeginRead()` 遵守 `autoRead/readPending`，`allocHandle.continueReading()` 会正确收敛，不会空转；
- `readEOS` 只在 Http2Stream 状态转为 **CLOSED** 时经 `Http2MultiplexHandler.streamClosed()` 置位；服务端响应未发出前流处于 `HALF_CLOSED_REMOTE`，不会 CLOSED → 请求体发完后继续 `ctx.read()` **不会**触发 `closeIfReadEOSAndDrained() → closeForcibly()` 提前终止流；
- `Http2StreamFrameToHttpObjectCodec` 内部不主动 `read()`，不与本泵争抢读兴趣。

---

## 6. 风险与回滚

- **风险 1**：h2 流改停等泵后，若某条路径漏发 `ctx.read()` 会卡住上传。已核对的恢复触发点共 4 处：`beginRelay` 初始读、`openBackend` 就绪回调、`channelReadComplete`（两个分支）、`BackendRelay.channelWritabilityChanged`（后端由不可写转可写时 `tx.front.read()`）。回归已覆盖 GET/无体、定长、chunked、h1 流水线、h2 同连接多流、并发下载、后端建连失败、客户端中途断开（见 §5 第 5 行）。
- **风险 2（已量化）**：抬高窗口的代价是每个未被消费的流最多缓冲一个窗口量。用 `mem.mjs 8 16`（8 条连接各 16MB，共 128MB；后端故意挂起 8 秒不消费）实测 java 进程工作集增量：

  | h2 窗口 | 负载前 RSS | 峰值 RSS | 增量 |
  |---|---|---|---|
  | 64 KB | 408.3 MB | 447.5 MB | +39.2 MB |
  | 1 MB | 410.3 MB | 443.4 MB | +33.1 MB |
  | 2 MB | 401.8 MB | 448.0 MB | +46.2 MB |

  三档基本持平（差异小于噪声）——因为代理消费不受窗口限制时，回源出站缓冲的 64 KB 高水位先触发停读，流入缓冲远达不到一窗口满。**这说明内存是被缺陷 B 的背压钉住的，而非被窗口大小钉住**；也说明 128MB 上传只换来约 35 MB 工作集增长（若无背压会直按 128MB 堆积）。1 MB 窗口在容器 `MaxDirectMemorySize=64m` 下安全。
- 改动集中在 `ProxyHandler`（19+/11-）与 `ProxyCenter`（+19/-1）；均可单文件回滚。

---

## 7. 顺带发现的独立缺陷（已修）

**h2 前端请求不带 `content-length` 时，转发到 h1 后端无法定界请求体。**

`beginRelay()` 只在 h1 前端带 `Transfer-Encoding: chunked` 时给后端重新声明 chunked：

```java
if (HttpUtil.isTransferEncodingChunked(req)) HttpUtil.setTransferEncodingChunked(fwd, true);
```

h2 里请求体由 `END_STREAM` 定界，客户端常常不带 `content-length`（例如浏览器 `fetch(stream, {duplex:'half'})`）。此时转发出的 h1 请求**既无 CL 又无 TE**，后端只能按"连接关闭"定界 → 永远等不到结束 → 挂到读超时回 504。复现时（脚本未带 CL）后端在 30 秒内挂死即此路径。

正确做法（已落地）：h2 前端 + 无 `content-length` + 方法可能带体（非 GET/HEAD）→ 回源声明 `Transfer-Encoding: chunked`，由 `LastHttpContent` 写终止块：

```java
if (HttpUtil.isTransferEncodingChunked(req)) {
    HttpUtil.setTransferEncodingChunked(fwd, true);
} else if (isH2 && HttpUtil.getContentLength(req, -1) < 0
        && !HttpMethod.GET.equals(req.method()) && !HttpMethod.HEAD.equals(req.method())) {
    HttpUtil.setTransferEncodingChunked(fwd, true);
}
```

回归用例 C1 验证：h2 不带 CL 上传 8MB → 后端收到 `ok:8388608:te=chunked:cl=none`（原本挂死 30 秒，现 184 ms 完成）。常见触发者：浏览器流式上传（`fetch(stream, {duplex:'half'})`）与 rclone / AWS CLI 等不定长客户端。

---

## 8. 追加：下载方向的诊断（同轮排查）

现象：经代理 h2 下载 605 Mbit/s，直连 1004 Mbit/s（差 40%）。

### 8.1 分诊实验（线上真实链路，Node 客户端拉 `backend/garbage.php?ul=32MB`）

| 并发 | 直连 | 代理 h1 | 代理 h2（单连接 N 流） |
|---|---|---|---|
| 1 | 103.7 | 100.4 | **55.5** MB/s |
| 2 | 111.7 | 111.8 | **62.0** |
| 4 | 112.6 | 110.8 | **51.6** |
| 8 | 112.5 | 111.9 | **39.8**（不升反降） |

- **代理 h1 与直连基线持平** → 代理的 TLS / 回源 / 拷贝路径不是瓶颈；
- **h2 只能到一半且并发越高越差** → 卡在本应共享的维度上；
- 把客户端通告的**流级**窗口从 64KB 抬到 8MB，吞吐纹丝不动（55.3→55.5）→ 排除流级窗口；
- 本地环回对照（`dl-local.mjs`）在**干净基线**下：h2 单连接 1 流即可跑 **320~385 MB/s**（与 h1 持平甚至更高），把 Node 的 `maxSessionMemory` 从默认 16 抬到 256 无变化（385→388）→ **代理的 h2 下载路径本身没有吞吐缺陷**，也排除「连接级窗口卡住」这一说；但同机环回带宽无限、往返≈0，**根本复现不出线上那 40% 的差距**。

> 更正：本节初次排查时，本地梯度跑在「16 路 h1 狂拉」之后，基线被前置负载污染（当时记为 h2 1 流仅 107 MB/s），
> 并据此得出「瓶颈是客户端通告的 h2 连接级窗口、代理无法代劳」的结论。该结论**不成立，已撤回**。
> 线上 h2 与 h1 的差距成因**尚未定位**（候选：Chrome 按自身观测的 BDP 自调流级窗口，未涨到足以喂满带宽），
> 但「h1 经代理持平直连」是硬实测数据 → 直接提供开关让站点走 h1（见 8.4）。

### 8.2 试过的旋钮：`-Dnx.proxy.h2DlWm`（h2 子流出站写水位）→ 默认关闭

机理上值得试：`BackendRelay` 在 `front.channel().isWritable()` 为假时停掉后端读，而 h2 子流的可写性恢复条件是「写缓冲回落到低水位以下 且 父连接可写」（`AbstractHttp2StreamChannel` L277），Netty 默认 32K/64K 对 h2 出站偏敏感。

**实测无效，默认已回退为 0（不覆盖 Netty 默认）**。每轮前静默 8 秒取干净基线，3 轮取最优（单连接 1/2/4 流，MB/s）：

| 子流写水位（高） | 1 流 | 2 流 | 4 流 |
|---|---|---|---|
| 0（Netty 默认 32K/64K） | 348.0 / 321.9 | 330.6 / 321.8 | 252.6 / 251.9 |
| 1024 KB | 382.1 / 379.4 | 317.3 / 317.6 | 236.7 / 271.6 |

差异在 ±15% 内且**方向不一致**（1 流略升、2 流略降）。线上部署 1MB 后 Chrome 四次实测下载 **520 / 590 / 601 / 657 Mbit/s**，与未部署时的 605 无可测差异。旋钮保留，供受限带宽 / 高往返的下载链路自行试值。

### 8.3 未采纳的候选与理由

- **抬 `maxFrameSize`**：无效。DATA 帧上限受**接收方**通告的 `SETTINGS_MAX_FRAME_SIZE`（Chrome/Node 均 16384），服务端抬自己的通告只影响入站。
- **多后端连接并行拉同一响应**：只提升回源侧，不改变前端 h2 的窗口限制，无用。
- ~~站点级强制 http/1.1~~ → **已实现**，见 8.4。

### 8.4 已落地：站点级前端协议开关 `frontProtocol`

既然「h1 经代理 ≈ 直连、h2 经代理 ≈ 一半」是线上硬数据，就直接把选择权交给站点：

- **字段**：`advanced.frontProtocol`，取 `h2`（默认）或 `http1`；面板「高级配置」页签有对应下拉，`/api/proxy/defaults` 同步返回默认值（前后端一致）。
- **实现**：`SslStore.buildContext(taskId, h2)` 在 `http1` 时 ALPN **只通告 `http/1.1`**，客户端协商不到 h2 即回退 h1。`contexts` 缓存的 key 由「证书任务 ID」改为「ID + `|h2`/`|h1`」两套并存 —— 站点改开关后下次握手直接落到另一个 key，**无需重启、无需清证书缓存**。
- **判定范围**：`SslStore.frontH2(host, sites)` 遍历命中该域名的启用站点，**任一**关闭即关闭（同域名多站点时取保守的一侧）。
- **不受控于该项的部分**：明文端口的 h2c prior-knowledge 由客户端在握手前显式发起，探测到预面时还没有域名可判，因此只能保持原样；要彻底禁 h2 需用 HTTPS 监听（浏览器一定是 HTTPS）。
- **代价**：失去多路复用，浏览器对同一 origin 最多 6 条 h1 连接。**适合测速 / 大文件下载型站点**，小资源密集的普通站点保持 h2 更省连接。

验证（`AlpnProbe.java`，用真实的 `SslStore.buildContext` 起 TLS 服务，客户端像 Chrome 一样同时支持 h2/http/1.1）：

```
[client] 站点 frontProtocol=h2    -> ALPN 协商=h2
[client] 站点 frontProtocol=http1 -> ALPN 协商=http/1.1
```

配置面另有回归用例 K1/K2/K3（默认补 `h2`、显式 `http1` 原样读回、关了 h2 的站点仍能正常服务）。回归用例由 13 项增至 **16 项，全部通过**。

---

## 9. 结论一句话

**上传**：“优化代理性能”的批次 flush 改动只给 HTTP/1.1 前端接了泵，HTTPS 前端协商到的 HTTP/2 既不 flush 也不设背压——请求体卡在代理出站缓冲里（上传≈0）并把内存顶满（表现为高频 GC）；叠加 h2 默认 64 KB 流控窗口，高 RTT 链路的上传本身就被钉在 ~3.3 MB/s。四处均已修复并有实测背书：`ProxyHandler` 去 `!isH2` 守卫 + h2 同走停等泵（上传 2.8 → 68~83 MB/s）、`ProxyCenter` 抬 h2 本地窗口到 1 MB（40ms 往返下 0.6 → 10.9 MB/s）、h2 无 CL 时回源转 chunked；线上 OpenList 上传 3.28 → 100 MB/s、LibreSpeed 0.01 → 990 Mbit/s。

**下载**：代理的 h1 路径已持平直连，h2 路径差 35~40%；本地环回（干净基线下 h2 单流 320~385 MB/s）证明**代理 h2 下载代码没有缺陷**，差距出在客户端通告的 h2 流控窗口上，而代理无法代劳；水位旋钮（h2DlWm）实测无效已默认关闭。因此提供站点级 `frontProtocol=http1` 开关（ALPN 只通告 http/1.1）让带宽型站点拿满，已端到端验证 ALPN 确实回退。
