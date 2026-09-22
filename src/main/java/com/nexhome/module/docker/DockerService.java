package com.nexhome.module.docker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.web.WebServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Docker 容器观测服务（只读）。
 * <p>
 * 通过挂载的 docker.sock 或 DOCKER_HOST 直连 Docker Engine API，提供：
 * 容器列表（含实时内存/CPU、磁盘占用、容器 IP、暴露端口、Compose 归属）、
 * 容器详情（启动命令、环境变量、挂载、网络）、Compose 项目分组、整体概况。
 * <p>
 * 注意：stats 单次采样耗时 1~2 秒，属于慢速 IO，全部在 HTTP 请求线程内按需拉取并使用
 * 独立虚拟线程并行化，绝不占用 {@link com.nexhome.core.Tasks} 共享调度池（避免饥饿），
 * 也不注册任何周期性 Docker 轮询任务。
 */
public final class DockerService {

    /** 容器 id / 名称合法性校验（拼入 Daemon 请求行，防注入） */
    private static final String ID_PATTERN = "^[a-zA-Z0-9][a-zA-Z0-9_.-]*$";

    private static final DockerClient CLIENT = DockerClient.create();

    /** 首次连接成功仅记录一次日志 */
    private static volatile boolean loggedConnected = false;

    private DockerService() {
    }

    /** 注册 REST 接口 */
    public static void registerRoutes() {
        WebServer.route("GET", "/api/docker/overview", ctx -> ctx.ok(overview()));
        WebServer.route("GET", "/api/docker/containers", ctx ->
                ctx.ok(containers("1".equals(ctx.query("stats")))));
        WebServer.route("GET", "/api/docker/containers/{id}", ctx ->
                ctx.ok(detail(checkId(ctx.param("id")))));
        WebServer.route("GET", "/api/docker/compose", ctx -> ctx.ok(compose()));
    }

    // ---------- 数据组装 ----------

    /** Docker 整体概况：连接状态 + 版本 + 容器/镜像统计 + 磁盘占用 */
    private static Map<String, Object> overview() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("host", CLIENT.describe);
        try {
            JsonObject v = JsonUtils.parse(CLIENT.get("/version"));
            r.put("connected", true);
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("version", JsonUtils.str(v, "Version"));
            version.put("apiVersion", JsonUtils.str(v, "ApiVersion"));
            version.put("os", JsonUtils.str(v, "Os"));
            version.put("arch", JsonUtils.str(v, "Arch"));
            r.put("version", version);
            if (!loggedConnected) {
                loggedConnected = true;
                Logs.info(Logs.DOCKER, "Docker 已连接: " + CLIENT.describe
                        + " (v" + JsonUtils.str(v, "Version") + ")");
            }
        } catch (Exception e) {
            r.put("connected", false);
            r.put("error", friendly(e));
            return r;
        }
        try {
            JsonObject i = JsonUtils.parse(CLIENT.get("/info"));
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("containers", optLong(i, "Containers"));
            info.put("running", optLong(i, "ContainersRunning"));
            info.put("paused", optLong(i, "ContainersPaused"));
            info.put("stopped", optLong(i, "ContainersStopped"));
            info.put("images", optLong(i, "Images"));
            info.put("memTotal", optLong(i, "MemTotal"));
            info.put("os", JsonUtils.str(i, "OperatingSystem"));
            info.put("kernel", JsonUtils.str(i, "KernelVersion"));
            r.put("info", info);
        } catch (Exception ignored) {
        }
        try {
            JsonObject d = JsonParser.parseString(CLIENT.get("/system/df")).getAsJsonObject();
            Map<String, Object> df = new LinkedHashMap<>();
            JsonArray images = optArray(d, "Images");
            long imgSize = 0;
            for (JsonElement el : images) imgSize += optLong(el.getAsJsonObject(), "Size");
            df.put("imagesCount", images.size());
            df.put("imagesSize", imgSize);
            JsonArray containers = optArray(d, "Containers");
            long cSize = 0;
            for (JsonElement el : containers) cSize += optLong(el.getAsJsonObject(), "SizeRootFs");
            df.put("containersSize", cSize);
            JsonArray volumes = optArray(d, "Volumes");
            long vSize = 0;
            for (JsonElement el : volumes) {
                JsonObject u = optObj(el.getAsJsonObject(), "UsageData");
                if (u != null) vSize += optLong(u, "Size");
            }
            df.put("volumesCount", volumes.size());
            df.put("volumesSize", vSize);
            r.put("df", df);
        } catch (Exception ignored) {
        }
        return r;
    }

    /** 容器列表：all=1 全量 + size=1 磁盘占用；withStats 时并行补充实时内存/CPU */
    private static List<JsonObject> containers(boolean withStats) throws IOException {
        JsonArray arr = JsonParser.parseString(
                CLIENT.get("/containers/json?all=1&size=1")).getAsJsonArray();
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement el : arr) list.add(brief(el.getAsJsonObject()));
        if (withStats) fetchStats(list);
        list.sort((a, b) -> Long.compare(optLong(b, "created"), optLong(a, "created")));
        return list;
    }

    /** 列表项精简组装：名称/镜像/状态/IP/端口/磁盘/Compose 归属 */
    private static JsonObject brief(JsonObject c) {
        JsonObject o = new JsonObject();
        String id = JsonUtils.str(c, "Id");
        o.addProperty("id", id);
        o.addProperty("shortId", id.substring(0, Math.min(12, id.length())));
        JsonArray names = optArray(c, "Names");
        String name = names.size() > 0 ? names.get(0).getAsString() : "";
        o.addProperty("name", name.startsWith("/") ? name.substring(1) : name);
        o.addProperty("image", JsonUtils.str(c, "Image"));
        o.addProperty("command", JsonUtils.str(c, "Command"));
        o.addProperty("state", JsonUtils.str(c, "State"));
        o.addProperty("status", JsonUtils.str(c, "Status"));
        o.addProperty("created", optLong(c, "Created"));

        JsonObject labels = optObj(c, "Labels");
        o.addProperty("composeProject", JsonUtils.str(labels, "com.docker.compose.project"));
        o.addProperty("composeService", JsonUtils.str(labels, "com.docker.compose.service"));

        // 容器内 IP：NetworkSettings.Networks 取第一个有地址的网络（通常为主网络）
        String ip = "", network = "";
        JsonObject ns = optObj(c, "NetworkSettings");
        JsonObject networks = ns == null ? null : optObj(ns, "Networks");
        if (networks != null) {
            for (Map.Entry<String, JsonElement> e : networks.entrySet()) {
                String a = JsonUtils.str(e.getValue().getAsJsonObject(), "IPAddress");
                if (!a.isEmpty()) {
                    ip = a;
                    network = e.getKey();
                    break;
                }
            }
        }
        o.addProperty("ip", ip);
        o.addProperty("network", network);

        // 暴露端口：PublicPort 存在表示已映射到宿主机
        JsonArray ps = new JsonArray();
        for (JsonElement el : optArray(c, "Ports")) {
            JsonObject p = el.getAsJsonObject();
            JsonObject np = new JsonObject();
            np.addProperty("privatePort", optLong(p, "PrivatePort"));
            if (p.has("PublicPort") && !p.get("PublicPort").isJsonNull()) {
                np.addProperty("publicPort", optLong(p, "PublicPort"));
                np.addProperty("ip", JsonUtils.str(p, "IP"));
            }
            np.addProperty("type", JsonUtils.str(p, "Type"));
            ps.add(np);
        }
        o.add("ports", ps);

        // 磁盘占用：SizeRw=可写层，SizeRootFs=含镜像层的总占用
        o.addProperty("sizeRw", optLong(c, "SizeRw"));
        o.addProperty("sizeRootFs", optLong(c, "SizeRootFs"));
        return o;
    }

    /** 并行拉取运行中容器的实时 stats（虚拟线程，互不占用共享调度池） */
    private static void fetchStats(List<JsonObject> containers) {
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JsonObject>> futures = new ArrayList<>();
            for (JsonObject c : containers) {
                if (!"running".equals(JsonUtils.str(c, "state"))) continue;
                String id = JsonUtils.str(c, "id");
                futures.add(pool.submit(() -> stats(id)));
            }
            for (Future<JsonObject> f : futures) {
                try {
                    JsonObject s = f.get(20, TimeUnit.SECONDS);
                    if (s != null) byId.put(JsonUtils.str(s, "id"), s);
                } catch (Exception ignored) {
                    // 单容器 stats 失败不影响整体列表
                }
            }
        }
        for (JsonObject c : containers) {
            JsonObject s = byId.get(JsonUtils.str(c, "id"));
            if (s != null) {
                copy(s, c, "memUsed");
                copy(s, c, "memLimit");
                copy(s, c, "cpuPercent");
                copy(s, c, "netRx");
                copy(s, c, "netTx");
                copy(s, c, "pids");
            }
        }
    }

    /** 单容器实时资源（内存/CPU/网络/进程数），失败返回 null */
    private static JsonObject stats(String id) {
        try {
            JsonObject s = JsonUtils.parse(CLIENT.get("/containers/" + id + "/stats?stream=false"));
            JsonObject o = new JsonObject();
            o.addProperty("id", id);

            // 内存：usage 需扣除缓存页（cgroup v1: stats.cache / total_inactive_file，v2: inactive_file）
            JsonObject m = optObj(s, "memory_stats");
            JsonObject ms = m == null ? null : optObj(m, "stats");
            long usage = m == null ? 0 : optLong(m, "usage");
            long cache = ms == null ? 0 : Math.max(Math.max(optLong(ms, "inactive_file"),
                    optLong(ms, "total_inactive_file")), optLong(ms, "cache"));
            long memUsed = Math.max(0, usage - cache);
            long memLimit = m == null ? 0 : optLong(m, "limit");
            o.addProperty("memUsed", memUsed);
            o.addProperty("memLimit", memLimit);

            // CPU：与 precpu 采样差值折算百分比
            JsonObject cs = optObj(s, "cpu_stats");
            JsonObject ps2 = optObj(s, "precpu_stats");
            double cpuPercent = 0;
            int online = 0;
            if (cs != null) {
                JsonObject cu = optObj(cs, "cpu_usage");
                long cpuTotal = cu == null ? 0 : optLong(cu, "total_usage");
                long sysTotal = optLong(cs, "system_cpu_usage");
                long preCpu = 0, preSys = 0;
                if (ps2 != null) {
                    JsonObject pcu = optObj(ps2, "cpu_usage");
                    preCpu = pcu == null ? 0 : optLong(pcu, "total_usage");
                    preSys = optLong(ps2, "system_cpu_usage");
                }
                long cpuDelta = cpuTotal - preCpu;
                long sysDelta = sysTotal - preSys;
                online = (int) optLong(cs, "online_cpus");
                if (online <= 0 && cu != null && cu.has("percpu_usage") && cu.get("percpu_usage").isJsonArray()) {
                    online = cu.getAsJsonArray("percpu_usage").size();
                }
                if (sysDelta > 0 && cpuDelta >= 0) {
                    cpuPercent = cpuDelta * 100.0 * Math.max(1, online) / sysDelta;
                }
            }
            o.addProperty("cpuPercent", Math.round(cpuPercent * 10) / 10.0);
            o.addProperty("cpus", online);

            // 网络收发汇总（所有网卡）
            long rx = 0, tx = 0;
            JsonObject nets = optObj(s, "networks");
            if (nets != null) {
                for (Map.Entry<String, JsonElement> e : nets.entrySet()) {
                    JsonObject nw = e.getValue().getAsJsonObject();
                    rx += optLong(nw, "rx_bytes");
                    tx += optLong(nw, "tx_bytes");
                }
            }
            o.addProperty("netRx", rx);
            o.addProperty("netTx", tx);
            JsonObject pids = optObj(s, "pids_stats");
            o.addProperty("pids", optLong(pids, "current"));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** 容器详情：inspect 基础信息 + 启动命令 + 挂载 + 网络 + 端口映射 + 实时资源 */
    private static JsonObject detail(String id) throws IOException {
        JsonObject c = JsonUtils.parse(CLIENT.get("/containers/" + id + "/json"));
        JsonObject config = optObj(c, "Config");
        JsonObject host = optObj(c, "HostConfig");
        JsonObject state = optObj(c, "State");
        JsonObject ns = optObj(c, "NetworkSettings");

        JsonObject o = new JsonObject();
        o.addProperty("id", JsonUtils.str(c, "Id"));
        String name = JsonUtils.str(c, "Name");
        o.addProperty("name", name.startsWith("/") ? name.substring(1) : name);
        o.addProperty("image", config == null ? "" : JsonUtils.str(config, "Image"));
        o.addProperty("created", JsonUtils.str(c, "Created"));
        if (state != null) {
            o.addProperty("state", JsonUtils.str(state, "Status"));
            o.addProperty("running", JsonUtils.bool(state, "Running", false));
            o.addProperty("startedAt", JsonUtils.str(state, "StartedAt"));
            o.addProperty("finishedAt", JsonUtils.str(state, "FinishedAt"));
            o.addProperty("exitCode", optLong(state, "ExitCode"));
        }
        o.addProperty("restartCount", optLong(c, "RestartCount"));

        // 启动命令：entrypoint + cmd（等价 docker inspect 的完整命令行）
        String entrypoint = join(config == null ? null : optArray(config, "Entrypoint"));
        String cmd = join(config == null ? null : optArray(config, "Cmd"));
        o.addProperty("entrypoint", entrypoint);
        o.addProperty("cmd", cmd);
        o.addProperty("command", (entrypoint + " " + cmd).trim());
        o.addProperty("workingDir", config == null ? "" : JsonUtils.str(config, "WorkingDir"));

        // 环境变量
        JsonArray env = new JsonArray();
        if (config != null) {
            for (JsonElement el : optArray(config, "Env")) env.add(el.getAsString());
        }
        o.add("env", env);

        // 资源限制与重启策略
        if (host != null) {
            o.addProperty("memoryLimit", optLong(host, "Memory"));
            o.addProperty("nanoCpus", optLong(host, "NanoCpus"));
            o.addProperty("networkMode", JsonUtils.str(host, "NetworkMode"));
            JsonObject rp = optObj(host, "RestartPolicy");
            o.addProperty("restartPolicy", rp == null ? "" : JsonUtils.str(rp, "Name"));
        }

        // 挂载（bind / volume）
        JsonArray mounts = new JsonArray();
        for (JsonElement el : optArray(c, "Mounts")) {
            JsonObject m = el.getAsJsonObject();
            JsonObject nm = new JsonObject();
            nm.addProperty("type", JsonUtils.str(m, "Type"));
            nm.addProperty("source", JsonUtils.str(m, "Source"));
            nm.addProperty("destination", JsonUtils.str(m, "Destination"));
            nm.addProperty("mode", JsonUtils.str(m, "Mode"));
            mounts.add(nm);
        }
        o.add("mounts", mounts);

        // 网络：各网络内的 IP / 网关 / MAC
        JsonArray networks = new JsonArray();
        JsonObject nets = ns == null ? null : optObj(ns, "Networks");
        if (nets != null) {
            for (Map.Entry<String, JsonElement> e : nets.entrySet()) {
                JsonObject nw = e.getValue().getAsJsonObject();
                JsonObject n = new JsonObject();
                n.addProperty("name", e.getKey());
                n.addProperty("ip", JsonUtils.str(nw, "IPAddress"));
                n.addProperty("gateway", JsonUtils.str(nw, "Gateway"));
                n.addProperty("mac", JsonUtils.str(nw, "MacAddress"));
                networks.add(n);
            }
        }
        o.add("networks", networks);

        // 端口映射：NetworkSettings.Ports（"80/tcp" -> [{HostIp,HostPort}]，null 表示未映射）
        JsonArray mappings = new JsonArray();
        JsonObject ports = ns == null ? null : optObj(ns, "Ports");
        if (ports != null) {
            for (Map.Entry<String, JsonElement> e : ports.entrySet()) {
                if (e.getValue().isJsonNull()) {
                    JsonObject x = new JsonObject();
                    x.addProperty("containerPort", e.getKey());
                    mappings.add(x);
                    continue;
                }
                for (JsonElement b : e.getValue().getAsJsonArray()) {
                    JsonObject bo = b.getAsJsonObject();
                    JsonObject x = new JsonObject();
                    x.addProperty("containerPort", e.getKey());
                    x.addProperty("hostIp", JsonUtils.str(bo, "HostIp"));
                    x.addProperty("hostPort", JsonUtils.str(bo, "HostPort"));
                    mappings.add(x);
                }
            }
        }
        o.add("portMappings", mappings);

        // Compose 归属
        JsonObject labels = config == null ? null : optObj(config, "Labels");
        o.addProperty("composeProject", JsonUtils.str(labels, "com.docker.compose.project"));
        o.addProperty("composeService", JsonUtils.str(labels, "com.docker.compose.service"));
        o.addProperty("composeWorkdir", JsonUtils.str(labels, "com.docker.compose.workingdir"));
        o.addProperty("composeFiles", JsonUtils.str(labels, "com.docker.compose.config-files"));

        // 运行中容器补充实时资源
        if (JsonUtils.bool(o, "running", false)) {
            JsonObject s = stats(id);
            if (s != null) {
                copy(s, o, "memUsed");
                copy(s, o, "memLimit");
                copy(s, o, "cpuPercent");
                copy(s, o, "cpus");
                copy(s, o, "netRx");
                copy(s, o, "netTx");
                copy(s, o, "pids");
            }
        }
        return o;
    }

    /** Compose 项目列表：按 com.docker.compose.project 标签对全量容器分组 */
    private static List<JsonObject> compose() throws IOException {
        JsonArray arr = JsonParser.parseString(CLIENT.get("/containers/json?all=1")).getAsJsonArray();
        Map<String, JsonObject> projects = new LinkedHashMap<>();
        for (JsonElement el : arr) {
            JsonObject c = el.getAsJsonObject();
            JsonObject labels = optObj(c, "Labels");
            String project = JsonUtils.str(labels, "com.docker.compose.project");
            if (project.isBlank()) continue;
            JsonObject p = projects.computeIfAbsent(project, k -> {
                JsonObject np = new JsonObject();
                np.addProperty("name", k);
                np.addProperty("workdir", JsonUtils.str(labels, "com.docker.compose.workingdir"));
                np.addProperty("configFiles", JsonUtils.str(labels, "com.docker.compose.config-files"));
                np.addProperty("containers", 0);
                np.addProperty("running", 0);
                np.add("services", new JsonArray());
                np.add("images", new JsonArray());
                return np;
            });
            p.addProperty("containers", optLong(p, "containers") + 1);
            if ("running".equals(JsonUtils.str(c, "State"))) {
                p.addProperty("running", optLong(p, "running") + 1);
            }
            addUnique(optArray(p, "services"), JsonUtils.str(labels, "com.docker.compose.service"));
            addUnique(optArray(p, "images"), JsonUtils.str(c, "Image"));
        }
        return new ArrayList<>(projects.values());
    }

    // ---------- 工具方法 ----------

    private static String checkId(String id) {
        if (id == null || !id.matches(ID_PATTERN)) throw new IllegalArgumentException("非法的容器标识");
        return id;
    }

    /** 连接失败转为对用户友好的提示 */
    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        if (m.startsWith("DOCKER_HOST 配置无效")) {
            return m + "；正确格式：unix:///var/run/docker.sock 或 tcp://host:2375";
        }
        if (m.contains("Unsupported address type") || m.contains("UnsupportedAddressType")) {
            // 理论上不再出现（已改用 SocketChannel），保留兜底：极老运行时不支持 UDS
            return "当前 Java 运行时不支持 Unix Domain Socket（" + m + "），"
                    + "请升级运行时或改用环境变量 DOCKER_HOST=tcp://host:2375";
        }
        if (m.contains("No such file") || m.contains("Connection refused")
                || m.contains("connect failed") || m.contains("SocketException")
                || m.contains("Permission denied")) {
            return "无法连接 Docker Daemon（" + m + "），请检查 docker.sock 挂载/读写权限或 DOCKER_HOST 配置";
        }
        return m;
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) to.add(key, from.get(key));
    }

    /** 数组元素去重追加（按字符串值） */
    private static void addUnique(JsonArray arr, String value) {
        if (value.isBlank()) return;
        for (JsonElement el : arr) {
            if (el.getAsString().equals(value)) return;
        }
        arr.add(value);
    }

    private static String join(JsonArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : arr) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(el.getAsString());
        }
        return sb.toString();
    }

    private static long optLong(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && !o.get(key).isJsonNull()
                    ? o.get(key).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static JsonObject optObj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject()
                ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray optArray(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray()
                ? o.getAsJsonArray(key) : new JsonArray();
    }
}
