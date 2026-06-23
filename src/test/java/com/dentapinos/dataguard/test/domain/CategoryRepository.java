package com.dentapinos.dataguard.test.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByName(String name);
    
    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.products WHERE c.name = :name")
    Optional<Category> findByNameWithProducts(String name);
}
