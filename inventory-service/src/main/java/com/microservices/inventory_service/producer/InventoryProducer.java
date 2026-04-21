package com.microservices.inventory_service.producer;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.microservices.inventory_service.DTO.StockUpdateEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InventoryProducer {
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange.name}")
    private String exchangeName;
    @Value("${rabbitmq.routing.key}")
    private String routingKey;

    private static final Logger logger = LoggerFactory.getLogger(InventoryProducer.class);
    public void sendMessage(StockUpdateEvent event){
        logger.info("Sending stock update event to RabbitMQ: {}", event);
        rabbitTemplate.convertAndSend(exchangeName, routingKey, event);
    }
}
