package com.wms.service;

import com.wms.entity.Inventory;
import com.wms.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis 库存门控（选做A：出库高并发防超卖的第一道闸）
 *
 * 定位：Redis 不是权威库存，只是「高性能前置门控」——高并发下先用 Lua 原子脚本
 * 在内存里完成 检查+扣减，把库存不足的请求挡在数据库之外，减轻 DB 行锁竞争；
 * 数据库原子条件更新（InventoryRepository.deductStock）始终是最终正确性兜底。
 *
 * 关键语义：
 * 1. 预扣 = 检查并扣减（Lua 原子，Redis 单线程执行天然串行）；
 * 2. 预扣成功 ≠ 出库成功：DB 事务失败时调用方负责 revert() 补偿回滚；
 * 3. 未初始化（key 不存在）→ 从 DB 懒加载当前库存 SETNX 后重试一次；
 * 4. 可用性兜底（fail-open）：Redis 连接异常时预扣直接放行（返回 true），
 *    由 DB 原子条件更新兜底正确性——Redis 挂了只损失高并发拦截能力，不会超卖；
 * 5. 一致性维护：入库成功 increase() 同步镜像、服务启动 rebuildAll() 全量重建。
 *
 * 已知边界（记录于 NOTES.md §7）：revert 补偿本身失败会使 Redis 少扣（比 DB 低），
 * 影响是「少卖不超卖」（Redis 多挡掉一些本可成功的请求），由启动重建/懒加载自愈。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockService {

    private static final String KEY_PREFIX = "wms:stock:";
    private static final String SEP = ":";

    /**
     * Lua 原子预扣脚本：
     * - key 不存在   → 返回 -1（未初始化，调用方需懒加载后重试）
     * - 剩余 >= 需求 → DECRBY 后返回 1
     * - 剩余 < 需求  → 返回 0（库存不足）
     */
    private static final String PRE_DEDUCT_LUA = """
            local cur = redis.call('GET', KEYS[1])
            if cur == false then
                return -1
            end
            local qty = tonumber(ARGV[1])
            if tonumber(cur) >= qty then
                redis.call('DECRBY', KEYS[1], qty)
                return 1
            end
            return 0
            """;

    private final StringRedisTemplate redisTemplate;
    private final InventoryRepository inventoryRepository;

    private static String key(Long productId, String locationCode) {
        return KEY_PREFIX + productId + SEP + locationCode;
    }

    /**
     * 预扣库存。返回 true=预扣成功（或 Redis 不可用已降级放行）；false=库存不足（拒绝出库）。
     *
     * @throws BusinessException 不抛——库存不足与降级都由返回值表达，业务层据此决策。
     */
    public boolean preDeduct(Long productId, String locationCode, int quantity) {
        String key = key(productId, locationCode);
        try {
            Long result = executePreDeduct(key, quantity);
            if (result == null) {
                // 脚本执行异常（理论不发生）：保守按降级处理，交由 DB 兜底
                log.warn("Redis 预扣脚本无返回，降级放行: key={}", key);
                return true;
            }
            if (result == -1L) {
                // 未初始化：从 DB 读当前库存 SETNX（并发下只有第一个成功），重试一次
                initFromDb(key, productId, locationCode);
                Long retry = executePreDeduct(key, quantity);
                return retry != null && retry == 1L;
            }
            return result == 1L;
        } catch (Exception e) {
            // fail-open：Redis 不可用（连接失败/超时）→ 跳过预扣，正确性交给 DB 原子扣减兜底
            log.warn("Redis 预扣不可用，降级为纯 DB 扣减（fail-open）: key={}, err={}", key, e.getMessage());
            return true;
        }
    }

    private Long executePreDeduct(String key, int quantity) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(PRE_DEDUCT_LUA, Long.class);
        return redisTemplate.execute(script, List.of(key), String.valueOf(quantity));
    }

    /**
     * 补偿回滚：DB 事务失败时把预扣的数量加回（幂等性由调用方保证——每行至多补偿一次）。
     * 补偿本身失败不抛异常（日志告警），Redis 侧短暂少扣，由启动重建/懒加载自愈。
     */
    public void revert(Long productId, String locationCode, int quantity) {
        try {
            redisTemplate.opsForValue().increment(key(productId, locationCode), quantity);
            log.info("Redis 预扣补偿回滚: productId={}, location={}, qty={}", productId, locationCode, quantity);
        } catch (Exception e) {
            log.warn("Redis 补偿回滚失败（将由启动重建/懒加载自愈）: productId={}, location={}, err={}",
                    productId, locationCode, e.getMessage());
        }
    }

    /**
     * 入库成功后的镜像同步（DB 已累加完成后再调用）：
     * - key 不存在 → 直接以 DB 当前值（已含本次入库）初始化，不再累加；
     * - key 已存在 → INCRBY quantity 保持镜像。
     *
     * 注意：不能在"set 底数后又无条件 INCRBY"——那会在初始化路径重复累加一次
     * （曾导致镜像比 DB 多一个 quantity，并发测试 Redis=200 vs DB=100 暴露）。
     */
    public void increase(Long productId, String locationCode, int quantity) {
        try {
            String key = key(productId, locationCode);
            Boolean existed = redisTemplate.hasKey(key);
            if (Boolean.FALSE.equals(existed)) {
                int dbQty = inventoryRepository.findByProductIdAndLocationCode(productId, locationCode)
                        .map(Inventory::getQuantity)
                        .orElse(quantity);
                redisTemplate.opsForValue().set(key, String.valueOf(dbQty));
            } else {
                redisTemplate.opsForValue().increment(key, quantity);
            }
        } catch (Exception e) {
            log.warn("Redis 入库镜像同步失败（不影响 DB，稍后由重建自愈）: productId={}, location={}, err={}",
                    productId, locationCode, e.getMessage());
        }
    }

    /** 懒加载：从 DB 读当前库存初始化 key（SETNX，并发安全） */
    private void initFromDb(String key, Long productId, String locationCode) {
        int dbQty = inventoryRepository.findByProductIdAndLocationCode(productId, locationCode)
                .map(Inventory::getQuantity)
                .orElse(0);
        redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(dbQty));
    }

    /** 服务启动全量重建：以 DB 库存为权威覆盖 Redis（对账兜底） */
    public void rebuildAll() {
        try {
            List<Inventory> all = inventoryRepository.findAll();
            for (Inventory inv : all) {
                redisTemplate.opsForValue().set(
                        key(inv.getProductId(), inv.getLocationCode()),
                        String.valueOf(inv.getQuantity()));
            }
            log.info("Redis 库存全量重建完成: {} 行", all.size());
        } catch (Exception e) {
            log.warn("Redis 库存全量重建失败（fail-open，不影响业务正确性）: {}", e.getMessage());
        }
    }

    /** 删除 key（测试清理用） */
    public void deleteKey(Long productId, String locationCode) {
        try {
            redisTemplate.delete(key(productId, locationCode));
        } catch (Exception ignored) {
            // 测试清理兜底
        }
    }
}
