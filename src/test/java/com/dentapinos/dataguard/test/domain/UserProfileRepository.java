package com.dentapinos.dataguard.test.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    
    Optional<UserProfile> findByUserId(Long userId);
    
    @Query("SELECT up FROM UserProfile up LEFT JOIN FETCH up.user WHERE up.user.id = :userId")
    Optional<UserProfile> findByUserIdWithUser(@Param("userId") Long userId);
    
    @Query("SELECT up FROM UserProfile up LEFT JOIN FETCH up.user WHERE up.user.username = :username")
    Optional<UserProfile> findByUsernameWithUser(@Param("username") String username);

    Optional<UserProfile> findByUserUsername(String username);
}
