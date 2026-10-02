package com.example.vod.common.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcedureRetryTest {

    @Test
    void delayIsAttemptTimesFiveSeconds() {
        assertEquals(5_000, ProcedureRetry.delayMillis(1));
        assertEquals(10_000, ProcedureRetry.delayMillis(2));
        assertEquals(15_000, ProcedureRetry.delayMillis(3));
        assertEquals(5_000, ProcedureRetry.delayMillis(0));
        assertEquals("vod.procedure.retry.5000ms", ProcedureRetry.routingKey(1));
        assertEquals("vod.procedure.retry.10000ms", ProcedureRetry.routingKey(2));
    }

    @Test
    void declaresOneHoldingQueuePerRetryableAttempt() {
        List<Declarable> declarables = ProcedureRetry.declarables(3).getDeclarables().stream().toList();

        List<DirectExchange> exchanges = ofType(declarables, DirectExchange.class);
        assertEquals(2, exchanges.size());
        assertEquals(ProcedureRetry.EXCHANGE_NAME, exchanges.get(0).getName());
        assertEquals(ProcedureRetry.DEAD_EXCHANGE, exchanges.get(1).getName());

        List<Queue> queues = ofType(declarables, Queue.class);
        assertEquals(3, queues.size());
        assertHoldingQueue(queues.get(0), 5_000);
        assertHoldingQueue(queues.get(1), 10_000);
        assertDeadQueue(queues.get(2));

        List<Binding> bindings = ofType(declarables, Binding.class);
        assertEquals(3, bindings.size());
        assertEquals(ProcedureRetry.queueName(5_000), bindings.get(0).getRoutingKey());
        assertEquals(ProcedureRetry.EXCHANGE_NAME, bindings.get(0).getExchange());
        assertEquals(ProcedureRetry.queueName(10_000), bindings.get(1).getRoutingKey());
        assertEquals(ProcedureRetry.DLQ_ROUTING_KEY, bindings.get(2).getRoutingKey());
        assertEquals(ProcedureRetry.DEAD_EXCHANGE, bindings.get(2).getExchange());
    }

    @Test
    void deadLetterQueueRemainsWhenRetriesDisabled() {
        List<Queue> queues = ofType(ProcedureRetry.declarables(1).getDeclarables().stream().toList(), Queue.class);
        assertEquals(1, queues.size());
        assertDeadQueue(queues.get(0));
    }

    private static void assertHoldingQueue(Queue queue, int delayMs) {
        assertEquals(ProcedureRetry.queueName(delayMs), queue.getName());
        assertTrue(queue.isDurable());
        assertEquals(delayMs, queue.getArguments().get("x-message-ttl"));
        assertEquals(RabbitConfig.EXCHANGE_NAME, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(RabbitConfig.ROUTING_KEY, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    private static void assertDeadQueue(Queue queue) {
        assertEquals(ProcedureRetry.DLQ_NAME, queue.getName());
        assertTrue(queue.isDurable());
        Map<String, Object> args = queue.getArguments();
        assertFalse(args != null && args.containsKey("x-message-ttl"));
        assertFalse(args != null && args.containsKey("x-dead-letter-exchange"));
    }

    private static <T> List<T> ofType(List<Declarable> declarables, Class<T> type) {
        return declarables.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
