package com.wms.service;

import com.wms.common.BusinessException;
import com.wms.dto.ProductCreateRequest;
import com.wms.dto.ProductResponse;
import com.wms.dto.ProductUpdateRequest;
import com.wms.entity.Product;
import com.wms.repository.InboundOrderItemRepository;
import com.wms.repository.InventoryRepository;
import com.wms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品管理 Service — 参考实现
 * 展示了：参数校验、异常处理、事务管理、DTO 转换
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InboundOrderItemRepository inboundOrderItemRepository;

    public List<ProductResponse> list(String keyword) {
        List<Product> products = productRepository.search(keyword);
        return products.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ProductResponse getById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "商品不存在"));
        return toResponse(product);
    }

    @Transactional
    public ProductResponse create(ProductCreateRequest request) {
        if (productRepository.existsBySku(request.getSku())) {
            throw new BusinessException("SKU已存在: " + request.getSku());
        }
        Product product = Product.builder()
                .name(request.getName())
                .sku(request.getSku())
                .unit(request.getUnit() != null ? request.getUnit() : "个")
                .build();
        product = productRepository.save(product);
        log.info("创建商品成功: id={}, sku={}", product.getId(), product.getSku());
        return toResponse(product);
    }

    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "商品不存在"));
        product.setName(request.getName());
        if (request.getUnit() != null) {
            product.setUnit(request.getUnit());
        }
        product = productRepository.save(product);
        return toResponse(product);
    }

    /**
     * 删除商品（任务 3 修复）。
     *
     * 安全策略：默认校验关联数据（库存 / 入库单明细）——
     * 有关联时返回 400 提示关联数量，由前端二次确认后带 force=true 重试；
     * force=true 时在同一事务内级联清理关联库存与入库单明细后再删除商品，
     * 避免「商品删了、库存/明细成为孤立脏数据」（预埋 Bug 根因）。
     */
    @Transactional
    public void delete(Long id, boolean force) {
        if (!productRepository.existsById(id)) {
            throw new BusinessException(404, "商品不存在");
        }
        long inventoryCount = inventoryRepository.countByProductId(id);
        long itemCount = inboundOrderItemRepository.countByProductId(id);

        if ((inventoryCount > 0 || itemCount > 0) && !force) {
            throw new BusinessException(400,
                    "该商品存在库存 " + inventoryCount + " 条、入库明细 " + itemCount
                            + " 条，删除将同时清理关联数据，请确认后重试");
        }
        if (inventoryCount > 0) {
            inventoryRepository.deleteByProductId(id);
        }
        if (itemCount > 0) {
            inboundOrderItemRepository.deleteByProductId(id);
        }
        productRepository.deleteById(id);
        log.info("删除商品: id={}, force={}, 清理库存={}条/明细={}条", id, force, inventoryCount, itemCount);
    }

    private ProductResponse toResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .sku(product.getSku())
                .unit(product.getUnit())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
