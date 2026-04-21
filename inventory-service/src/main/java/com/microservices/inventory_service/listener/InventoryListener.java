package com.microservices.inventory_service.listener;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.microservices.inventory_service.DTO.StockUpdateEvent;
import com.microservices.inventory_service.model.Inventory;
import com.microservices.inventory_service.repository.InventoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryListener {

    private final InventoryRepository inventoryRepository;

    @RabbitListener(queues = "${rabbitmq.queue.name}")
    @Transactional
    public void handleStockUpdateEvent(StockUpdateEvent event) {
        log.info("📥 [RabbitMQ] Nhận yêu cầu cập nhật DB cho SKU: {} | Số lượng trừ: {}", 
                 event.getSkuCode(), event.getQuantity());
        try {
            // 1. Tìm sản phẩm trong DB
            Inventory inventoryDb = inventoryRepository.findBySkuCode(event.getSkuCode())
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy SKU: " + event.getSkuCode()));

            // 2. Trừ số lượng thực tế
            int newQuantity = inventoryDb.getQuantity() - event.getQuantity();
            
            // Đảm bảo không bị âm dưới DB
            if (newQuantity < 0) {
                log.error("❌ Lỗi dữ liệu: Số lượng DB không đủ để trừ cho {}", event.getSkuCode());
                return;
            }

            // 3. Lưu lại
            inventoryDb.setQuantity(newQuantity);
            inventoryRepository.save(inventoryDb);

            log.info("✅ Đã cập nhật thành công DB cho {}. Tồn kho mới: {}", event.getSkuCode(), newQuantity);

        } catch (Exception e) {
            log.error("❌ Lỗi trong quá trình cập nhật DB từ RabbitMQ: {}", e.getMessage());
            // Tương lai: Có thể ném tin nhắn vào Dead Letter Queue (DLQ) ở đây
        }
    }
}
