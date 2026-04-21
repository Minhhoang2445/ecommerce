package com.microservices.product_service.DTO;

import java.math.BigDecimal;

public record ProductRequest(String id, String name, String description, BigDecimal price) {
}