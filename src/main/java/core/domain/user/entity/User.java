package core.domain.user.entity;


import core.domain.user.dto.UserUpdateDTO;
import core.global.enums.Sex;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "SEX", nullable = false)
    private Sex sex;

    @Column(name = "age", nullable = false)
    private Integer age;

    @Column(name = "nationality")
    private String nationality;

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

    @Column(name = "email", nullable = false)
    private String email;


    @Builder
    public User(String name, Sex sex, Integer age, String nationality,
                String introduction, String visitPurpose, String languages,
                String hobby, String provider, String socialId, String email) {
        this.name = name;
        this.sex = sex;
        this.age = age;
        this.nationality = nationality;
        this.introduction = introduction;
        this.visitPurpose = visitPurpose;
        this.languages = languages;
        this.hobby = hobby;
        this.provider = provider;
        this.socialId = socialId;
        this.email = email;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }


    public void updateProfile(UserUpdateDTO dto) {
        if (dto.getName() != null && !dto.getName().trim().isEmpty()) {
            this.name = dto.getName().trim();
        }
        if (dto.getSex() != null) {
            this.sex = dto.getSex();
        }
        if (dto.getAge() != null) {
            this.age = dto.getAge();
        }
        if (dto.getNationality() != null && !dto.getNationality().trim().isEmpty()) {
            this.nationality = dto.getNationality().trim();
        }
        if (dto.getIntroduction() != null && !dto.getIntroduction().trim().isEmpty()) {
            this.introduction = dto.getIntroduction().trim();
        }
        if (dto.getVisitPurpose() != null && !dto.getVisitPurpose().trim().isEmpty()) {
            this.visitPurpose = dto.getVisitPurpose().trim();
        }
        if (dto.getLanguages() != null && !dto.getLanguages().trim().isEmpty()) {
            this.languages = dto.getLanguages().trim();
        }
        if (dto.getHobby() != null && !dto.getHobby().trim().isEmpty()) {
            this.hobby = dto.getHobby().trim();
        }

        this.updatedAt = Instant.now(); // 수정 시각 갱신
    }

}