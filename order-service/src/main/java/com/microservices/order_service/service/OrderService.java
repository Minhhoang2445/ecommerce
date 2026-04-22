package com.microservices.order_service.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.microservices.order_service.DTO.OrderRequest;
import com.microservices.order_service.client.InventoryClient;
import com.microservices.order_service.model.Order;
import com.microservices.order_service.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;

    public void placeOrder(OrderRequest orderRequest) {
        var isInStock = inventoryClient.deductInventory(orderRequest.skuCode(), orderRequest.quantity());

        if (!isInStock) {
            throw new RuntimeException("Product is not in stock");
        }
        else{
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());
        order.setPrice(orderRequest.price());
        order.setQuantity(orderRequest.quantity());
        order.setSkuCode(orderRequest.skuCode());

        orderRepository.save(order);
    }
    }
}
