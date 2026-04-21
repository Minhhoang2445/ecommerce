package com.microservices.inventory_service.DTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockUpdateEvent {
    private String skuCode;
    private Integer quantity;
}