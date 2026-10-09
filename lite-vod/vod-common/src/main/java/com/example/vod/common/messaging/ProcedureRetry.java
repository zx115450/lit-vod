package com.example.vod.common.messaging;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 转码失败的延迟重试拓扑（声明交换机 / 队列 / 绑定关系）。
 *
 * <p>消息路径示意（以 maxAttempts=3 为例）：
 * <pre>
 *   失败且还能重试
 *     → vod.retry（按 routingKey 进对应等待队列）
 *     → 等待队列睡满 TTL（无人消费）
 *     → 死信回到业务交换机 → vod.procedure（再次被 Worker 消费）
 *
 *   失败且次数用尽
 *     → vod.dead → vod.procedure.dlq（停放，不再回流）
 * </pre>
 *
 * <p>可重试失败不立刻回到 {@code vod.procedure}，而是按「当前尝试次数 × 5 秒」
 * 投入对应的等待队列。每个延迟一档一个队列，避免不同 TTL 挤在同一队列里被队头挡住。
 */
public final class ProcedureRetry {

    /** 延迟重试专用交换机：Worker 失败后把消息发到这里，再按 routingKey 进等待队列。 */
    public static final String EXCHANGE_NAME = "vod.retry";

    /** 次数用尽后的死信交换机；消息进 DLQ 后不会再回到业务队列。 */
    public static final String DEAD_EXCHANGE = "vod.dead";

    /** 最终死信队列名：无人消费，用于人工排查 / 监控告警。 */
    public static final String DLQ_NAME = "vod.procedure.dlq";

    /** 发往死信交换机时使用的 routingKey（与 DLQ 绑定键一致）。 */
    public static final String DLQ_ROUTING_KEY = "vod.procedure.dlq";

    /** 每一档延迟的步长：第 n 次失败等待 n × 5 秒（如 attempt=1 → 5s，attempt=2 → 10s）。 */
    public static final int DELAY_STEP_MILLIS = 5_000;

    private ProcedureRetry() {
    }

    /**
     * 根据尝试次数计算应等待的毫秒数。
     *
     * @param attempt 本次失败时的尝试次数（从 1 起）；小于 1 时按第 1 次计算
     */
    public static int delayMillis(int attempt) {
        int safeAttempt = Math.max(attempt, 1);
        return Math.multiplyExact(safeAttempt, DELAY_STEP_MILLIS);
    }

    /** 等待队列名：vod.procedure.retry.{延迟毫秒}ms，例如 vod.procedure.retry.5000ms。 */
    public static String queueName(int delayMillis) {
        return "vod.procedure.retry." + delayMillis + "ms";
    }

    /**
     * 发往 {@link #EXCHANGE_NAME} 时用的 routingKey。
     * 与等待队列名相同，Direct 交换机按键精确路由到对应 TTL 队列。
     */
    public static String routingKey(int attempt) {
        return queueName(delayMillis(attempt));
    }

    /**
     * 声明整套重试拓扑，供 Spring AMQP 启动时自动创建。
     *
     * <p>只为「还能再试」的次数建等待队列：attempt = 1 .. maxAttempts-1。
     * 例如 maxAttempts=3 时只建 5s、10s 两档；第 3 次失败走 DLQ，不再建等待队列。
     *
     * @param maxAttempts 最大尝试次数；≤0 时默认按 3
     */
    public static Declarables declarables(int maxAttempts) {
        int max = maxAttempts <= 0 ? 3 : maxAttempts;

        // ---------- 1) 延迟重试：交换机 + 多档 TTL 等待队列 ----------
        DirectExchange exchange = new DirectExchange(EXCHANGE_NAME);
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(exchange);

        // attempt=1 → 5s 队列；attempt=2 → 10s 队列；…；不到 maxAttempts 那一次
        for (int attempt = 1; attempt < max; attempt++) {
            int delayMs = delayMillis(attempt);
            String name = queueName(delayMs);

            // 等待队列：没有消费者；消息到期后靠「死信」弹回业务队列
            Queue queue = QueueBuilder.durable(name)
                    .ttl(delayMs) // 消息在队列里最多存活 delayMs，到期触发死信
                    .deadLetterExchange(RabbitConfig.EXCHANGE_NAME)   // 死信发到业务交换机
                    .deadLetterRoutingKey(RabbitConfig.ROUTING_KEY) // 再进 vod.procedure
                    .build();
            declarables.add(queue);
            // routingKey 用队列名本身：发到 vod.retry + 该 key → 精确进这一档等待队列
            declarables.add(BindingBuilder.bind(queue).to(exchange).with(name));
        }

        // ---------- 2) 次数用尽：死信交换机 + DLQ（不再指回业务队列）----------
        DirectExchange deadExchange = new DirectExchange(DEAD_EXCHANGE);
        Queue deadQueue = QueueBuilder.durable(DLQ_NAME).build();
        declarables.add(deadExchange);
        declarables.add(deadQueue);
        declarables.add(BindingBuilder.bind(deadQueue).to(deadExchange).with(DLQ_ROUTING_KEY));

        // Declarables：把上面交换机/队列/绑定打包成一个 Bean，Spring 启动时一并声明
        return new Declarables(declarables);
    }
}
