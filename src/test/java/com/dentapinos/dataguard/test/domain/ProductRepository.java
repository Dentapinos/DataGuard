package com.dentapinos.dataguard.test.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByCategoryId(Long categoryId);
    List<Product> findByUserId(Long userId);
    Optional<Product> findByName(String name);


    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category LEFT JOIN FETCH p.user LEFT JOIN FETCH p.productsOrders po LEFT JOIN FETCH po.order WHERE p.id = :id")
    Optional<Product> findByIdWithCategoryAndUserAndOrders(@Param("id") Long id);

    // --- НОВЫЙ МЕТОД: ---
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category LEFT JOIN FETCH p.productsOrders po LEFT JOIN FETCH po.order WHERE p.name = :name")
    Optional<Product> findByNameWithProductsOrders(@Param("name") String name);

    Optional<Product> findByNameAndCategory_Name(String name, String categoryName);
}
