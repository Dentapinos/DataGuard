package com.dentapinos.dataguard.test.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductOrderRepository extends JpaRepository<ProductOrder, Long> {

    Optional<ProductOrder> findByOrder_OrderNumberAndProduct_Name(String orderNumber, String productName);

}
