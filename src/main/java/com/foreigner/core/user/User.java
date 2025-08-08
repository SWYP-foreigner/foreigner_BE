package com.foreigner.core.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "User") // 원문 테이블명 유지(예약어 가능성 높아 백틱 필요할 수 있음)
@Getter
@NoArgsConstructor
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "SEX", nullable = false)
    private Sex sex; // 원문 ENUM 오류로 문자열 enum으로 보정

    @Column(name = "age", nullable = false)
    private Integer age;

    @Column(name = "nationality")
    private String nationality; // 원문 ENUM 값 미상 → 문자열로 보관 (확실하지 않음)

    @Column(name = "introduction", length = 40, nullable = false)
    private String introduction;

    @Column(name = "visit_purpose", length = 40)
    private String visitPurpose;

    @Column(name = "languages")
    private String languages;

    @Column(name = "hobby")
    private String hobby;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "Provider", nullable = false)
    private String provider;

    @Column(name = "social_id", nullable = false)
    private String socialId;

    @Column(name = "email", nullable = false) // 원문 "email." 오타 보정
    private String email;
}

