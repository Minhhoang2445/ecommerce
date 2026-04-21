package com.microservices.inventory_service.service;

import com.microservices.inventory_service.model.Inventory; // Nhớ import entity của bạn
import com.microservices.inventory_service.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

    // Giữ nguyên hàm cũ để không break code hiện tại
    public boolean isInStock(String skuCode, Integer quantity) {
        return inventoryRepository.existsBySkuCodeAndQuantityIsGreaterThanEqual(skuCode, quantity);
    }

    public boolean deductInventory(String skuCode, Integer quantity) {
        String cacheKey = "inventory:skucode:" + skuCode;
        String lockKey = "lock:refill:" + skuCode;

        // BƯỚC 1: Trừ thử trên RAM (Truyền số lượng vào hàm decrement)
        Long currentStock = redisTemplate.opsForValue().decrement(cacheKey, quantity);

        if (currentStock != null && currentStock >= 0) {
            log.info("✅ Mua thành công {} {}. Tồn kho RAM còn: {}", quantity, skuCode, currentStock);
            return true;
        }

        // Nếu rớt xuống dưới 0 (tức là không đủ hàng), phải CỘNG TRẢ LẠI ngay để không làm sai lệch số
        if (currentStock != null && currentStock < 0) {
            redisTemplate.opsForValue().increment(cacheKey, quantity);
        }

        // BƯỚC 2: Hết hàng hoặc Cache Miss -> Tiến hành giành khóa
        log.info("⚠️ Cache miss hoặc thiếu hàng cho {}. Tiến hành lấy Lock...", skuCode);
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // Đợi tối đa 3s để có khóa. Khóa tự động nhả sau 10s.
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                
                // DOUBLE-CHECK: Kiểm tra lại xem trong lúc chờ, có ai đã nạp lên chưa
                String stockInRedisStr = redisTemplate.opsForValue().get(cacheKey);
                if (stockInRedisStr != null) {
                    long stockInRedis = Long.parseLong(stockInRedisStr);
                    if (stockInRedis >= quantity) {
                        // May quá có thằng khác nạp rồi, trừ luôn
                        redisTemplate.opsForValue().decrement(cacheKey, quantity);
                        return true;
                    }
                }

                // Chắc chắn thiếu hàng rồi, phải xuống DB
                log.info("🔍 Truy vấn Database để tìm thêm hàng cho {}", skuCode);
                
                // Lưu ý: Bạn cần có hàm findBySkuCode trong Repository trả về Object Inventory
                Inventory inventoryDb = inventoryRepository.findBySkuCode(skuCode)
                        .orElseThrow(() -> new RuntimeException("Không tìm thấy sản phẩm: " + skuCode));

                if (inventoryDb.getQuantity() >= quantity) {
                    // DB có đủ hàng! Lấy lên và trừ đi phần của mình
                    int stockLeftToCache = inventoryDb.getQuantity() - quantity;

                    // Nạp số lượng CÒN LẠI lên Redis để phục vụ anh em khác
                    redisTemplate.opsForValue().set(cacheKey, String.valueOf(stockLeftToCache));
                    log.info("🚀 Đã nạp từ DB lên Redis. Chừa lại trên RAM: {}", stockLeftToCache);
                    // Đáng lẽ ra phải update DB ở đây (hoặc bắn RabbitMQ để update DB).
                    // Tạm thời cho luồng pass qua.
                    return true;
                } else {
                    // DB cũng nhẵn túi
                    log.warn("❌ HẾT HÀNG TOÀN DIỆN cho {}", skuCode);
                    // Cập nhật Redis = 0 để các request sau khỏi chọc xuống DB vô ích
                    redisTemplate.opsForValue().set(cacheKey, "0");
                    return false;
                }
            } else {
                // Không lấy được khóa -> Có người khác đang nạp -> Đợi 100ms thử lại
                log.info("⏳ Đang chờ người khác nạp hàng...");
                Thread.sleep(100);
                return deductInventory(skuCode, quantity);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock(); // Dọn dẹp khóa
            }
        }
    }
}