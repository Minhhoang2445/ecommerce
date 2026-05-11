package com.microservices.inventory_service.service;

import com.microservices.inventory_service.DTO.StockUpdateEvent;
import com.microservices.inventory_service.model.Inventory;
import com.microservices.inventory_service.producer.InventoryProducer; // Import bưu tá của bạn
import com.microservices.inventory_service.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j 
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    
    // SỬA Ở ĐÂY: Dùng Producer xịn xò của chúng ta thay vì RabbitTemplate thô
    private final InventoryProducer inventoryProducer;

// public boolean isInStock(String skuCode, Integer quantity) {
//     return inventoryRepository.existsBySkuCodeAndQuantityIsGreaterThanEqual(skuCode, quantity);
    // }

    public boolean deductInventory(String skuCode, Integer quantity) {
        String cacheKey = "inventory:skucode:" + skuCode;
        String lockKey = "lock:refill:" + skuCode;

        // BƯỚC 1: Trừ thử trên RAM
        Long currentStock = redisTemplate.opsForValue().decrement(cacheKey, quantity);

        if (currentStock != null && currentStock >= 0) {
            log.info("✅ Mua thành công {} {}. Tồn kho RAM còn: {}", quantity, skuCode, currentStock);
            // VỊ TRÍ 1: Trừ RAM thành công -> Bắn tin nhắn cho DB trừ ngay!
            inventoryProducer.sendMessage(new StockUpdateEvent(skuCode, quantity));
            return true;
        }

        // Cộng trả lại nếu lỡ trừ lố xuống âm
        if (currentStock != null && currentStock < 0) {
            redisTemplate.opsForValue().increment(cacheKey, quantity);
        }

        // BƯỚC 2: Xử lý Lock khi thiếu hàng
        log.info("⚠️ Cache miss hoặc thiếu hàng cho {}. Tiến hành lấy Lock...", skuCode);
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                
                // DOUBLE-CHECK
                String stockInRedisStr = redisTemplate.opsForValue().get(cacheKey);
                if (stockInRedisStr != null) {
                    long stockInRedis = Long.parseLong(stockInRedisStr);
                    if (stockInRedis >= quantity) {
                        redisTemplate.opsForValue().decrement(cacheKey, quantity);
                        log.info("✅ (Double-check) Mua thành công {} {}.", quantity, skuCode);
                        // VỊ TRÍ 2: Hưởng sái RAM của người khác nạp -> Cũng phải bắn tin nhắn cho DB!
                        inventoryProducer.sendMessage(new StockUpdateEvent(skuCode, quantity));
                        return true;
                    }
                }

                // Chắc chắn thiếu hàng rồi, phải xuống DB
                log.info("🔍 Truy vấn Database để tìm thêm hàng cho {}", skuCode);
                Inventory inventoryDb = inventoryRepository.findBySkuCode(skuCode)
                        .orElseThrow(() -> new RuntimeException("Không tìm thấy sản phẩm: " + skuCode));

                if (inventoryDb.getQuantity() >= quantity) {
                    int stockLeftToCache = inventoryDb.getQuantity() - quantity;
                    redisTemplate.opsForValue().set(cacheKey, String.valueOf(stockLeftToCache));
                    
                    log.info("🚀 Đã nạp từ DB lên Redis. Chừa lại trên RAM: {}", stockLeftToCache);
                    // VỊ TRÍ 3: Móc từ DB lên chia chác thành công -> Bắn tin nhắn cho DB trừ đi phần đã lấy!
                    inventoryProducer.sendMessage(new StockUpdateEvent(skuCode, quantity));
                    
                    return true;
                } else {
                    log.warn("❌ HẾT HÀNG TOÀN DIỆN cho {}", skuCode);
                    redisTemplate.opsForValue().set(cacheKey, "0");
                    return false;
                }
            } else {
                log.info("⏳ Đang chờ người khác nạp hàng...");
                Thread.sleep(100);
                return deductInventory(skuCode, quantity);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}