package com.wms.repository;

import com.wms.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LocationRepository extends JpaRepository<Location, Long> {
    List<Location> findByWarehouseId(Long warehouseId);
    boolean existsByCode(String code);
    Optional<Location> findByCode(String code);
}
