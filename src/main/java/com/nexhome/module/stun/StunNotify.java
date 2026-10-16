package com.nexhome.module.stun;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.nexhome.core.Database;
import com.nexhome.core.Logs;
import com.nexhome.module.notify.NotifyService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * STUN 穿透结果通知：穿透成功 / 外网映射地址变化时，把结果按任务勾选的渠道投递到
 * <b>统一告警通知中心</b>（渠道在「告警通知」页集中维护，任务只选择用哪些渠道，可多选）。
 * <p>
 * 配置存于 {@code stun_task}：{@code notify_enabled}（开关）、{@code notify_channels}
 * （勾选的渠道 id JSON 数组，如 {@code [1,3]}）。
 * <p>
 * 触发点与 {@link StunWebhook}、{@link StunEsaSync} 完全一致（{@link StunRunner} 的映射地址
 * 落库联动），事件标识为 {@code stun.punched} / {@code stun.mapped_changed}（手动试发为
 * {@code stun.notify_test}），可在通知渠道的 Webhook 模板中以 {@code ${event}} 引用。
 * 投递在 {@link NotifyService} 的独立线程串行执行，
 * 渠道被删除或停用时跳过并记日志，<b>不影响穿透与保活</b>；每次投递写入 {@code notify_log}，
 * 可在「告警通知」页的最近发送记录中查看结果。
 */
final class StunNotify {

    /** 映射地址变化事件（与 StunWebhook 的事件标识保持一致） */
    private static final String EVENT_CHANGED = "stun.mapped_changed";
    /** 手动试发事件（验证渠道勾选是否可用，结果同样落 notify_log） */
    private static final String EVENT_TEST = "stun.notify_test";

    private StunNotify() {
    }

    /** 按任务勾选的渠道推送穿透结果；未启用或未勾选渠道时静默返回 */
    static void trigger(Map<String, Object> task, String event) {
        if (intVal(task, "notify_enabled") != 1) return;
        String name = str(task, "name");
        List<Long> ids = parseChannelIds(str(task, "notify_channels"));
        if (ids.isEmpty()) {
            Logs.warn(Logs.STUN, "任务[" + name + "] 已启用穿透通知但未选择通知渠道，本次未投递");
            return;
        }
        NotifyService.broadcastTo(ids, event, title(event, name), message(task, event));
    }

    /**
     * 手动试发一次（任务列表操作列的「通知」按钮）：按当前勾选渠道投递一条测试通知，返回投递渠道数。
     * 投递在 {@link NotifyService} 的独立线程异步执行，本方法只做勾选校验，结果由调用方提示用户去
     * 「告警通知」页查看发送记录。
     */
    static int testSend(Map<String, Object> task) {
        List<Long> ids = parseChannelIds(str(task, "notify_channels"));
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("任务未勾选通知渠道，请先在编辑表单中勾选至少一个渠道");
        }
        String name = str(task, "name");
        NotifyService.broadcastTo(ids, EVENT_TEST, title(EVENT_TEST, name), message(task, EVENT_TEST));
        return ids.size();
    }

    private static String title(String event, String taskName) {
        if (EVENT_CHANGED.equals(event)) return "穿透映射地址变化：" + taskName;
        if (EVENT_TEST.equals(event)) return "穿透通知测试：" + taskName;
        return "穿透成功：" + taskName;
    }

    /** 正文按「字段: 值」逐行组织，邮件渠道会渲染为对齐的两列表格（见 NotifyService） */
    private static String message(Map<String, Object> task, String event) {
        String mapped = str(task, "mapped_addr");
        StringBuilder sb = new StringBuilder()
                .append("任务: ").append(str(task, "name")).append('\n')
                .append("协议: ").append(str(task, "protocol")).append('\n')
                .append("内网目标: ").append(str(task, "target_ip")).append(':').append(str(task, "target_port")).append('\n')
                .append("外网映射地址: ").append(mapped.isBlank() ? "无" : mapped).append('\n');
        String nat = str(task, "nat_type");
        if (!nat.isBlank()) sb.append("NAT类型: ").append(nat).append('\n');
        String punchedAt = str(task, "punched_at");
        if (!punchedAt.isBlank()) sb.append("穿透时间: ").append(punchedAt).append('\n');
        sb.append("事件: ").append(switch (event) {
            case EVENT_CHANGED -> "外网映射地址已变更，后续外网访问请以此地址为准";
            case EVENT_TEST -> "这是一条测试通知，渠道勾选与投递链路均正常";
            default -> "首次穿透成功";
        }).append('\n').append("时间: ").append(Database.now());
        return sb.toString();
    }

    /** 解析渠道 id 列表（存储为 JSON 数字数组，如 {@code [1,3]}；非法项丢弃，不影响其余渠道） */
    static List<Long> parseChannelIds(String json) {
        List<Long> ids = new ArrayList<>();
        String text = json == null ? "" : json.trim();
        if (text.isEmpty()) return ids;
        JsonElement el;
        try {
            el = JsonParser.parseString(text);
        } catch (Exception e) {
            Logs.warn(Logs.STUN, "解析穿透通知渠道配置失败: " + e.getMessage());
            return ids;
        }
        if (!el.isJsonArray()) return ids;
        for (JsonElement item : el.getAsJsonArray()) {
            try {
                long id = Long.parseLong(item.getAsString().trim());
                if (id > 0 && !ids.contains(id)) ids.add(id);
            } catch (Exception ignored) {
                // 非数字项（如对象/空值）丢弃
            }
        }
        return ids;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static int intVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }
}
