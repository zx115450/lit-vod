package com.example.vod.common.messaging;

import com.rabbitmq.client.ConnectionFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * VOD 转码任务队列配置，api 与 worker 共用同一组声明。
 *
 * <p>队列：vod.procedure，使用直连交换机 vod.direct，绑定键相同。
 * Worker 在第 09 步实现消费。
 */
@Configuration
public class RabbitConfig {

    public static final String QUEUE_NAME = "vod.procedure";
    public static final String EXCHANGE_NAME = "vod.direct";
    public static final String ROUTING_KEY = "vod.procedure";

    @Bean
    public Queue procedureQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public DirectExchange vodDirectExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }

    @Bean
    public Binding procedureBinding(Queue procedureQueue, DirectExchange vodDirectExchange) {
        return BindingBuilder.bind(procedureQueue)
                .to(vodDirectExchange)
                .with(ROUTING_KEY);
    }

    /**
     * 消息体以 JSON 形式在 MQ 中传输，便于 Worker 直接反序列化。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
