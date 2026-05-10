package com.sooktin.backend.global;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


@Slf4j
@Configuration
@EnableRabbit
@Profile("!local")
@ConditionalOnProperty(name = "spring.messaging.in-memory", havingValue = "false", matchIfMissing = true)
public class RabbitConfig {
    private static final String CHAT_QUEUE = "chat.queue";
    private static final String CHAT_EXCHANGE = "chat.exchange";
    private static final String ROUTING_KEY = "room.*";

    @Value("${spring.rabbitmq.host:localhost}")
    private String rabbitHost;

    @Value("${spring.rabbitmq.username:guest}")
    private String username;

    @Value("${spring.rabbitmq.password:guest}")
    private String password;

    @Value("${spring.rabbitmq.port:5672}")
    private int port;



    @Bean
    public Queue chatQueue() {
        // 기본 durable 큐로 단순화
        return new Queue(CHAT_QUEUE, true);
    }

    @Bean
    public TopicExchange chatExchange() {
        return new TopicExchange(CHAT_EXCHANGE, true, false);
    }

    @Bean
    public Binding chatBinding(Queue chatQueue, TopicExchange chatExchange) {
        return BindingBuilder.bind(chatQueue).to(chatExchange).with(ROUTING_KEY);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setPrefetchCount(5);  // t2.micro에 적합한 낮은 prefetch 값
        factory.setConcurrentConsumers(1);  // 제한된 리소스에서는 소비자 수 제한
        factory.setMaxConcurrentConsumers(2);
        factory.setDefaultRequeueRejected(false);  // 처리 실패 메시지 재큐잉 방지
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                // 메시지 전송 실패 처리
                log.error("Message delivery failed: {}", cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            // 메시지가 큐에 도달하지 못한 경우 처리
            log.error("Message returned: {}", returned.getMessage());
        });
        return rabbitTemplate;
    }

    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        connectionFactory.setHost(rabbitHost);
        connectionFactory.setUsername(username);
        connectionFactory.setPassword(password);
        connectionFactory.setPort(port);

        // 연결 관련 설정 최적화
        connectionFactory.setRequestedHeartBeat(30);  // 30초마다 하트비트 체크
        connectionFactory.setConnectionTimeout(5000);  // 5초 연결 타임아웃
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);

        // RabbitMQ Java 클라이언트의 자동 복구 설정
        // Spring AMQP 2.0 이상에서는 RabbitMQ 클라이언트의 내장 복구 메커니즘을 활용합니다
        connectionFactory.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(true);
        connectionFactory.getRabbitConnectionFactory().setNetworkRecoveryInterval(5000); // 5초 간격으로 복구 시도

        return connectionFactory;
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
