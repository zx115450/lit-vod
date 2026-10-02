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
 * 转码失败的延迟重试拓扑。
 *
 * <p>可重试失败不立刻回到 {@code vod.procedure}，而是按「当前尝试次数 × 5 秒」
 * 投入对应的等待队列。等待队列没有消费者，到期后经死信交换机回到业务队列。
 * 每个延迟一档一个队列，避免不同 TTL 挤在同一队列里被队头挡住。
 * 次数用尽后进入 {@code vod.procedure.dlq}，该队列没有消费者，也不会再死信回业务队列。
 */
public final class ProcedureRetry {

    public static final String EXCHANGE_NAME = "vod.retry";

    /** 次数用尽后的死信交换机。队列不再指回业务队列。 */
    public static final String DEAD_EXCHANGE = "vod.dead";

    public static final String DLQ_NAME = "vod.procedure.dlq";

    public static final String DLQ_ROUTING_KEY = "vod.procedure.dlq";

    /** 每一档延迟的步长：第 n 次失败等待 n × 5 秒。 */
    public static final int DELAY_STEP_MILLIS = 5_000;

    private ProcedureRetry() {
    }

    /**
     * @param attempt 本次失败时的尝试次数（从 1 起）；小于 1 时按第 1 次计算
     */
    public static int delayMillis(int attempt) {
        int safeAttempt = Math.max(attempt, 1);
        return Math.multiplyExact(safeAttempt, DELAY_STEP_MILLIS);
    }

    public static String queueName(int delayMillis) {
        return "vod.procedure.retry." + delayMillis + "ms";
    }

    public static String routingKey(int attempt) {
        return queueName(delayMillis(attempt));
    }

    /**
     * 只为还能再试的次数建等待队列：attempt = 1 .. maxAttempts-1。
     * 次数用尽后不再入队。
     */
    public static Declarables declarables(int maxAttempts) {
        int max = maxAttempts <= 0 ? 3 : maxAttempts;
        DirectExchange exchange = new DirectExchange(EXCHANGE_NAME);
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(exchange);
        for (int attempt = 1; attempt < max; attempt++) {
            int delayMs = delayMillis(attempt);
            String name = queueName(delayMs);
            Queue queue = QueueBuilder.durable(name)
                    .ttl(delayMs)
                    .deadLetterExchange(RabbitConfig.EXCHANGE_NAME)
                    .deadLetterRoutingKey(RabbitConfig.ROUTING_KEY)
                    .build();
            declarables.add(queue);
            declarables.add(BindingBuilder.bind(queue).to(exchange).with(name));
        }
        DirectExchange deadExchange = new DirectExchange(DEAD_EXCHANGE);
        Queue deadQueue = QueueBuilder.durable(DLQ_NAME).build();
        declarables.add(deadExchange);
        declarables.add(deadQueue);
        declarables.add(BindingBuilder.bind(deadQueue).to(deadExchange).with(DLQ_ROUTING_KEY));
        return new Declarables(declarables);
    }
}
