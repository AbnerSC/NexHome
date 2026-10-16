package com.nexhome.module.stun;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nexhome.core.Database;
import com.nexhome.core.JsonUtils;
import com.nexhome.core.Logs;
import com.nexhome.module.ddns.AliyunClient;
import com.nexhome.module.provider.ProviderConfigService;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * STUN 穿透结果同步阿里云 ESA（边缘安全加速）：把外网映射 IP 写入站点解析记录的记录值、
 * 把映射端口写入回源规则的回源 HTTPS 端口（等价于独立小工具 stun2esa 的 ESAUtil，改造为
 * 凭证引用服务商配置 {@code provider_config}、记录与规则由前端从线上列表勾选、只更新不新增）。
 * <p>
 * 配置存于 {@code stun_task}：{@code esa_sync_enabled}（开关）、{@code provider_config_id}
 * （AK/SK 来源）、{@code esa_site_id}（站点）、{@code esa_records}/{@code esa_rules}
 * （勾选快照 JSON 数组，元素 {@code {"id":123,"name":"*.edge.example.com"}}）。
 * <p>
 * <b>定位策略</b>：按 id 为主，id 在线上已不存在（记录被删除重建）时按名称回退匹配并刷新
 * 存储的 id；名称也匹配不到则该项记失败——本功能只更新已有配置，绝不新增或删除云端记录。
 * <p>
 * <b>写云端的最小化原则</b>：{@code UpdateRecord} 把线上 {@code Type/Ttl/Proxied} 原样回传，
 * 只改 {@code Data.Value}；{@code UpdateOriginRule} 的 {@code OriginScheme} 沿用规则现值，
 * 只改 {@code OriginHttpsPort}；现值已与目标一致时跳过写入（省配额、避免站点配置反复变更）。
 * UDP 任务的映射端口对 HTTPS 回源无意义，故只同步解析记录、不动回源规则。
 * <p>
 * 同步失败仅记日志与 {@code esa_sync_status}（供前端展示与手动重试），不影响穿透主流程；
 * 在独立线程串行执行，避免占用全局调度池拖慢保活导致 CGNAT 映射超时回收
 * （与 {@code StunWebhook}、公网入站验证同理：调度池仅 3 线程，被慢 HTTP 占用会延迟保活）。
 */
final class StunEsaSync {

    /** 独立同步线程：ESA HTTP 调用可能耗时数秒到数十秒，不能占用全局调度池（详见类注释） */
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stun-esa-sync");
        t.setDaemon(true);
        return t;
    });

    /** 写操作失败后的重试间隔（毫秒），共尝试 2 次 */
    private static final long RETRY_DELAY_MS = 3_000;

    private StunEsaSync() {
    }

    /** 穿透成功 / 映射地址变化后的异步同步（任务未启用 ESA 同步时静默返回） */
    static void triggerAsync(Map<String, Object> task) {
        if (intVal(task, "esa_sync_enabled") != 1) return;
        final String name = str(task, "name");
        EXEC.submit(() -> {
            try {
                JsonObject status = sync(task);
                String result = JsonUtils.str(status, "result");
                if (result.startsWith("FAIL")) {
                    Logs.warn(Logs.STUN, "任务[" + name + "] ESA 同步失败: " + result);
                } else {
                    Logs.info(Logs.STUN, "任务[" + name + "] ESA 同步 " + result + " " + itemsBrief(status));
                }
            } catch (Exception e) {
                Logs.warn(Logs.STUN, "任务[" + name + "] ESA 同步异常: " + e);
            }
        });
    }

    /** 立即同步一次（手动触发，同步执行以便前端直接拿到明细结果） */
    static JsonObject syncNow(Map<String, Object> task) throws SQLException {
        return sync(task);
    }

    /** 执行一次同步并把结果写回 stun_task.esa_sync_status，返回结果对象（time/result/items） */
    private static JsonObject sync(Map<String, Object> task) {
        long taskId = num(task, "id");
        String taskName = str(task, "name");
        JsonArray items = new JsonArray();
        String result;
        try {
            String mapped = str(task, "mapped_addr").trim();
            int ci = mapped.lastIndexOf(':');
            if (mapped.isEmpty() || ci <= 0 || ci == mapped.length() - 1) {
                throw new IllegalStateException("无外网映射地址，请先启动穿透任务");
            }
            String ip = mapped.substring(0, ci);
            String port = mapped.substring(ci + 1);
            String siteId = str(task, "esa_site_id").trim();
            if (siteId.isEmpty()) throw new IllegalStateException("未选择 ESA 站点");
            long cfgId = num(task, "provider_config_id");
            if (cfgId <= 0) throw new IllegalStateException("未选择服务商凭证配置，请在任务表单中重新选择");
            String[] cred = ProviderConfigService.credentials(cfgId);
            if (cred[0].isBlank() || cred[1].isBlank()) throw new IllegalStateException("凭证配置的 AccessKey 为空");

            JsonArray pickedRecords = parsePicked(task, "esa_records");
            JsonArray pickedRules = parsePicked(task, "esa_rules");
            if (pickedRecords.isEmpty() && pickedRules.isEmpty()) {
                throw new IllegalStateException("未勾选任何解析记录或回源规则");
            }
            if (!pickedRecords.isEmpty()) {
                syncRecords(taskId, cred[0], cred[1], siteId, pickedRecords, ip, items);
            }
            if (!pickedRules.isEmpty()) {
                if ("UDP".equalsIgnoreCase(str(task, "protocol"))) {
                    items.add(item("rule", pickedRules.size() + " 条回源规则", "", port, "SKIP",
                            "UDP 任务的映射端口对 HTTPS 回源无意义，未改动回源规则"));
                } else {
                    syncRules(taskId, cred[0], cred[1], siteId, pickedRules, port, items);
                }
            }
            result = summarize(items);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            // 整体中断（凭证错误/列表拉取失败等）：已有明细时按部分成功上报，便于定位是哪一项之后断的
            result = items.size() > 0 ? "PARTIAL(中断: " + msg + ")" : "FAIL(" + msg + ")";
            Logs.warn(Logs.STUN, "任务[" + taskName + "] ESA 同步中断: " + msg);
        }
        JsonObject status = new JsonObject();
        status.addProperty("time", Database.now());
        status.addProperty("result", result);
        status.addProperty("mapped_addr", str(task, "mapped_addr"));
        status.add("items", items);
        saveStatus(taskId, status);
        return status;
    }

    // ---------- 解析记录 ----------

    /** 把映射 IP 写入勾选的 ESA 解析记录（仅 A/AAAA，单值、同地址族才覆盖，其余跳过并说明） */
    private static void syncRecords(long taskId, String ak, String sk, String siteId,
                                    JsonArray picked, String ip, JsonArray items) throws Exception {
        JsonArray online = AliyunClient.esaListAllRecords(ak, sk, siteId);
        for (JsonElement el : picked) {
            if (!el.isJsonObject()) continue;
            JsonObject p = el.getAsJsonObject();
            String name = JsonUtils.str(p, "name");
            JsonObject rec = locate(online, longOf(p, "id"), name, "RecordId", "RecordName");
            if (rec == null) {
                items.add(item("record", name, "", ip, "FAIL", "线上未找到该解析记录（本功能只更新不新增）"));
                continue;
            }
            String recordId = String.valueOf(longOf(rec, "RecordId"));
            String type = str(rec, "RecordType");
            String current = valueOf(rec);
            if (!type.isBlank() && !type.equalsIgnoreCase("A/AAAA")
                    && !type.equalsIgnoreCase("A") && !type.equalsIgnoreCase("AAAA")) {
                items.add(item("record", name, current, ip, "SKIP", "记录类型为 " + type + "，非地址记录不改"));
                continue;
            }
            if (current.contains(",")) {
                items.add(item("record", name, current, ip, "SKIP", "线上为多值记录，整体覆盖会丢失其余地址，请手工处理"));
                continue;
            }
            if (current.contains(":") != ip.contains(":")) {
                items.add(item("record", name, current, ip, "FAIL", "地址族不匹配（线上 "
                        + (current.contains(":") ? "IPv6" : "IPv4") + "，映射 " + (ip.contains(":") ? "IPv6" : "IPv4") + "）"));
                continue;
            }
            if (ip.equals(current)) {
                items.add(item("record", name, current, ip, "SKIP", "记录值无变化"));
                continue;
            }
            int ttl = intOf(rec, "Ttl", 1); // 线上缺失时按“自动”（1）传参
            boolean proxied = rec.has("Proxied") && !rec.get("Proxied").isJsonNull()
                    && rec.get("Proxied").getAsBoolean();
            final String recordType = type.isBlank() ? "A/AAAA" : type;
            Exception last = write(taskId, "esa_records", name, "RecordId", online, recordId,
                    id -> AliyunClient.esaUpdateRecordValue(ak, sk, id, recordType, ip, ttl, proxied));
            items.add(last == null
                    ? item("record", name, current, ip, "OK", "记录值已更新")
                    : item("record", name, current, ip, "FAIL", msgOf(last)));
        }
    }

    // ---------- 回源规则 ----------

    /** 把映射端口写入勾选的 ESA 回源规则 HTTPS 端口（跳过全局配置，协议沿用规则现值） */
    private static void syncRules(long taskId, String ak, String sk, String siteId,
                                  JsonArray picked, String port, JsonArray items) throws Exception {
        JsonArray online = AliyunClient.esaListOriginRules(ak, sk, siteId);
        for (JsonElement el : picked) {
            if (!el.isJsonObject()) continue;
            JsonObject p = el.getAsJsonObject();
            String name = JsonUtils.str(p, "name");
            JsonObject cfg = locate(online, longOf(p, "id"), name, "ConfigId", "RuleName");
            if (cfg == null) {
                items.add(item("rule", name, "", port, "FAIL", "线上未找到该回源规则"));
                continue;
            }
            if ("global".equalsIgnoreCase(str(cfg, "ConfigType"))) {
                items.add(item("rule", name, "", port, "SKIP", "全局回源配置不适用规则端口同步"));
                continue;
            }
            String configId = String.valueOf(longOf(cfg, "ConfigId"));
            String current = str(cfg, "OriginHttpsPort");
            if (port.equals(current)) {
                items.add(item("rule", name, current, port, "SKIP", "回源端口无变化"));
                continue;
            }
            String scheme = str(cfg, "OriginScheme");
            if (scheme.isBlank()) scheme = "https"; // 线上未返回协议时按本功能的语义固定 HTTPS，避免被改回默认值
            final String proto = scheme;
            Exception last = write(taskId, "esa_rules", name, "ConfigId", online, configId,
                    id -> AliyunClient.esaUpdateOriginRulePort(ak, sk, siteId, id, proto, port));
            items.add(last == null
                    ? item("rule", name, current, port, "OK", "回源 HTTPS 端口已更新")
                    : item("rule", name, current, port, "FAIL", msgOf(last)));
        }
    }

    /**
     * 执行一次写操作并在失败时重试（共 2 次）：首轮失败后按名称回退重定位（记录被删除重建会使
     * 快照 id 失效，换用新 id 重试并写回任务配置），等待 {@link #RETRY_DELAY_MS} 再试一次。
     *
     * @return null 表示成功，否则为末次异常
     */
    private static Exception write(long taskId, String column, String name, String idField,
                                   JsonArray online, String id, WriteOp op) {
        String currentId = id;
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (attempt > 0) {
                    // 名称回退：id 在线上已失效（删除重建）时换用新 id 重试，并把新 id 写回任务勾选快照
                    JsonObject byName = locate(online, 0, name, idField, nameField(idField));
                    if (byName != null) {
                        String freshId = String.valueOf(longOf(byName, idField));
                        if (!freshId.equals(currentId)) {
                            currentId = freshId;
                            refreshId(taskId, column, name, freshId);
                        }
                    }
                }
                op.run(currentId);
                return null;
            } catch (Exception e) {
                last = e;
                if (attempt == 0) {
                    Logs.warn(Logs.STUN, "ESA 写入失败(" + name + ")，"
                            + (RETRY_DELAY_MS / 1000) + " 秒后重试: " + msgOf(e));
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return ie;
                    }
                }
            }
        }
        return last;
    }

    /** 写操作：入参为本次实际使用的配置 id（解析记录 RecordId / 回源规则 ConfigId） */
    private interface WriteOp {
        void run(String id) throws Exception;
    }

    // ---------- 工具 ----------

    /** 解析勾选快照列（JSON 数组，元素 {id,name}），缺失或非法时返回空数组 */
    private static JsonArray parsePicked(Map<String, Object> task, String column) {
        String text = str(task, column).trim();
        if (text.isEmpty()) return new JsonArray();
        try {
            JsonElement el = JsonParser.parseString(text);
            return el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "解析 ESA 勾选配置失败(" + column + "): " + e.getMessage());
            return new JsonArray();
        }
    }

    /** 按 id 定位线上配置，id 未命中时按名称回退（应对记录删除重建后的 id 漂移） */
    private static JsonObject locate(JsonArray online, long id, String name, String idField, String nameField) {
        if (id > 0) {
            for (JsonElement el : online) {
                if (el.isJsonObject() && longOf(el.getAsJsonObject(), idField) == id) return el.getAsJsonObject();
            }
        }
        if (!name.isBlank()) {
            for (JsonElement el : online) {
                if (el.isJsonObject() && str(el.getAsJsonObject(), nameField).equalsIgnoreCase(name)) {
                    return el.getAsJsonObject();
                }
            }
        }
        return null;
    }

    /** 由 id 字段名推名称字段名（RecordId -> RecordName，ConfigId -> RuleName） */
    private static String nameField(String idField) {
        return "ConfigId".equals(idField) ? "RuleName" : "RecordName";
    }

    /** 取解析记录的当前记录值（ESA 的 Data.Value） */
    private static String valueOf(JsonObject rec) {
        if (rec.has("Data") && rec.get("Data").isJsonObject()) {
            return JsonUtils.str(rec.getAsJsonObject("Data"), "Value");
        }
        return "";
    }

    /** 把回退定位到的新 id 写回任务勾选快照，下次同步直接按新 id 命中 */
    private static void refreshId(long taskId, String column, String name, String newId) {
        try {
            Map<String, Object> row = Database.queryOne("SELECT " + column + " FROM stun_task WHERE id=?", taskId);
            if (row == null) return;
            JsonArray picked = parsePicked(row, column);
            boolean changed = false;
            for (JsonElement el : picked) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (JsonUtils.str(o, "name").equalsIgnoreCase(name)) {
                    o.addProperty("id", Long.parseLong(newId));
                    changed = true;
                }
            }
            if (changed) {
                Database.update("UPDATE stun_task SET " + column + "=? WHERE id=?",
                        JsonUtils.GSON.toJson(picked), taskId);
                Logs.info(Logs.STUN, "ESA 配置 id 已刷新(" + name + " -> " + newId + ")");
            }
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "刷新 ESA 配置 id 失败(" + name + "): " + e.getMessage());
        }
    }

    /** 汇总明细为结果文案：全部跳过=无变化，有写入=更新项数，有失败=PARTIAL/FAIL */
    private static String summarize(JsonArray items) {
        int ok = 0, fail = 0;
        for (JsonElement el : items) {
            String state = el.isJsonObject() ? JsonUtils.str(el.getAsJsonObject(), "state") : "";
            if ("OK".equals(state)) ok++;
            else if ("FAIL".equals(state)) fail++;
        }
        int total = items.size();
        if (total == 0) return "FAIL(无可同步项)";
        if (fail == 0) return ok > 0 ? "OK(更新 " + ok + " 项)" : "OK(无变化)";
        if (ok + fail < total || ok > 0) return "PARTIAL(成功 " + (total - fail) + "/" + total + ")";
        return "FAIL(" + total + " 项全部失败)";
    }

    /** 明细的一行摘要（写日志用，避免整段 JSON 刷屏） */
    private static String itemsBrief(JsonObject status) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : status.getAsJsonArray("items")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (sb.length() > 0) sb.append("; ");
            sb.append(JsonUtils.str(o, "kind")).append(':').append(JsonUtils.str(o, "name"))
                    .append('=').append(JsonUtils.str(o, "state"));
        }
        return sb.length() == 0 ? "(无明细)" : sb.toString();
    }

    /** 单条同步明细：kind=record|rule，state=OK|SKIP|FAIL */
    private static JsonObject item(String kind, String name, String from, String to, String state, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("name", name);
        o.addProperty("from", from);
        o.addProperty("to", to);
        o.addProperty("state", state);
        o.addProperty("msg", msg);
        return o;
    }

    private static void saveStatus(long taskId, JsonObject status) {
        if (taskId <= 0) return;
        try {
            Database.update("UPDATE stun_task SET esa_sync_status=? WHERE id=?",
                    JsonUtils.GSON.toJson(status), taskId);
        } catch (Exception e) {
            Logs.error(Logs.STUN, "记录 ESA 同步结果失败(task#" + taskId + "): " + e.getMessage());
        }
    }

    private static String msgOf(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static long longOf(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? Long.parseLong(o.get(k).getAsString().trim()) : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int intOf(JsonObject o, String k, int def) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? Integer.parseInt(o.get(k).getAsString().trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static int intVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }
}
