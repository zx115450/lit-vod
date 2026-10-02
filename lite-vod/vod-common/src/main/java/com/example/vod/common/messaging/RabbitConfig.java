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
 * VOD 任务队列配置，api 与 worker 共用同一组声明。
 *
 * <p>队列：
 * <ul>
 *   <li>{@code vod.procedure} — VIDEO 转码（FFmpeg）</li>
 *   <li>{@code vod.document.split} — DOCUMENT 切章（与转码隔离）</li>
 *   <li>{@code vod.image.thumbnail} — IMAGE 缩略图（与转码隔离）</li>
 * </ul>
 * 交换机均为直连 {@code vod.direct}。失败延迟重试见 {@link ProcedureRetry}（仅 procedure）。
 */
@Configuration
public class RabbitConfig {

    public static final String QUEUE_NAME = "vod.procedure";
    public static final String EXCHANGE_NAME = "vod.direct";
    public static final String ROUTING_KEY = "vod.procedure";

    public static final String DOCUMENT_SPLIT_QUEUE = "vod.document.split";
    public static final String DOCUMENT_SPLIT_ROUTING_KEY = "vod.document.split";

    public static final String IMAGE_THUMBNAIL_QUEUE = "vod.image.thumbnail";
    public static final String IMAGE_THUMBNAIL_ROUTING_KEY = "vod.image.thumbnail";

    @Bean
    public Queue procedureQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Queue documentSplitQueue() {
        return new Queue(DOCUMENT_SPLIT_QUEUE, true);
    }

    @Bean
    public Queue imageThumbnailQueue() {
        return new Queue(IMAGE_THUMBNAIL_QUEUE, true);
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

    @Bean
    public Binding documentSplitBinding(Queue documentSplitQueue, DirectExchange vodDirectExchange) {
        return BindingBuilder.bind(documentSplitQueue)
                .to(vodDirectExchange)
                .with(DOCUMENT_SPLIT_ROUTING_KEY);
    }

    @Bean
    public Binding imageThumbnailBinding(Queue imageThumbnailQueue, DirectExchange vodDirectExchange) {
        return BindingBuilder.bind(imageThumbnailQueue)
                .to(vodDirectExchange)
                .with(IMAGE_THUMBNAIL_ROUTING_KEY);
    }

    /**
     * 消息体以 JSON 形式在 MQ 中传输，便于 Worker 直接反序列化。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
