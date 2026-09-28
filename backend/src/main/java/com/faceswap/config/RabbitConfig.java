package com.faceswap.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.faceswap.job.JobMessages.DEAD_LETTER_EXCHANGE;
import static com.faceswap.job.JobMessages.EVENTS_QUEUE;
import static com.faceswap.job.JobMessages.EVENT_ROUTING_KEY;
import static com.faceswap.job.JobMessages.EXCHANGE;
import static com.faceswap.job.JobMessages.JOBS_DEAD_QUEUE;
import static com.faceswap.job.JobMessages.JOBS_QUEUE;
import static com.faceswap.job.JobMessages.JOB_ROUTING_KEY;

@Configuration
public class RabbitConfig {

    @Bean
    DirectExchange exchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    // The worker declares the same queue with identical arguments; changing them here requires changing it there.
    @Bean
    Queue jobsQueue() {
        return QueueBuilder.durable(JOBS_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(JOBS_DEAD_QUEUE)
                .build();
    }

    @Bean
    Queue jobsDeadQueue() {
        return QueueBuilder.durable(JOBS_DEAD_QUEUE).build();
    }

    @Bean
    Queue eventsQueue() {
        return QueueBuilder.durable(EVENTS_QUEUE).build();
    }

    @Bean
    Binding jobsBinding() {
        return BindingBuilder.bind(jobsQueue()).to(exchange()).with(JOB_ROUTING_KEY);
    }

    @Bean
    Binding jobsDeadBinding() {
        return BindingBuilder.bind(jobsDeadQueue()).to(deadLetterExchange()).with(JOBS_DEAD_QUEUE);
    }

    @Bean
    Binding eventsBinding() {
        return BindingBuilder.bind(eventsQueue()).to(exchange()).with(EVENT_ROUTING_KEY);
    }

    @Bean
    MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
