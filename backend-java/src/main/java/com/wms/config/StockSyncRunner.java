package com.wms.config;

import com.wms.service.RedisStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时全量重建 Redis 库存镜像（选做A）
 *
 * 以 DB 库存为权威覆盖 Redis key，修复 Redis 侧可能存在的偏差
 * （如补偿回滚失败的残留、旧 dump 恢复的过期数据）。
 * Redis 不可用时仅告警不阻断启动（fail-open，正确性始终由 DB 保证）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockSyncRunner implements ApplicationRunner {

    private final RedisStockService redisStockService;

    @Override
    public void run(ApplicationArguments args) {
        redisStockService.rebuildAll();
    }
}
