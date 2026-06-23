package com.dentapinos.dataguard.test.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность с @OneToOne связью (сторона владельца).
 * Пример: UserProfile -> User
 */
@Entity
@Table(name = "user_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String bio;

    private String avatarUrl;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Override
    public String toString() {
        return "UserProfile{" +
                "id=" + id +
                ", bio='" + bio + '\'' +
                ", avatarUrl='" + avatarUrl + '\'' +
                ", user=" + user +
                '}';
    }
}
