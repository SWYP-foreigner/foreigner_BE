package core.domain.user.entity;


import core.domain.chat.entity.ChatParticipant;
import core.domain.user.dto.UserUpdateDTO;
import core.global.enums.Sex;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

import java.time.LocalDate;
import java.time.LocalDateTime;



@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
@Setter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "first_name", nullable = true)
    private String firstName;

    @Column(name = "last_name", nullable = true)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "SEX", nullable = true)
    private Sex sex;

    @Column(name = "birth_date", nullable = true)
    private LocalDate birthDate;

    @Column(name = "nationality", nullable = true)
    private String nationality;

    @Column(name = "introduction", length = 40, nullable = true)
    private String introduction;

    @Column(name = "visit_purpose", length = 40, nullable = true)
    private String visitPurpose;

    @Column(name = "languages", nullable = true)
    private String languages;

    @Column(name = "hobby", nullable = true)
    private String hobby;

    @Column(name = "created_at", nullable = true)
    private Instant createdAt;

    @Column(name = "updated_at",nullable = true)
    private Instant updatedAt;

    @Column(name = "Provider", nullable = true)
    private String provider;

    @Column(name = "social_id", nullable = true)
    private String socialId;

    @Column(name = "email", nullable = true)
    private String email;


    @Column(name = "profile_image_url")
    private String profileImageUrl; // NCP S3 업로드 결과 URL 저장


    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChatParticipant> chatParticipants;

    @Builder
    public User(String firstName,
                String lastName,
                Sex sex,
                LocalDate birthDate,
                String nationality,
                String introduction,
                String visitPurpose,
                String languages,
                String hobby,
                String provider,
                String socialId,
                String email,
                String profileImageUrl) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.sex = sex;
        this.birthDate = birthDate;
        this.nationality = nationality;
        this.introduction = introduction;
        this.visitPurpose = visitPurpose;
        this.languages = languages;
        this.hobby = hobby;
        this.provider = provider;
        this.socialId = socialId;
        this.email = email;
        this.profileImageUrl = profileImageUrl;
    }

    public void updateProfile(UserUpdateDTO dto) {
        // 문자열은 trim 후 비어있으면 무시
        if (notBlank(dto.getFirstname())) this.firstName = dto.getFirstname().trim();
        if (notBlank(dto.getLastname()))  this.lastName  = dto.getLastname().trim();

        if (dto.getSex() != null) this.sex = dto.getSex();
        if (dto.getBirthDate() != null) this.birthDate = dto.getBirthDate();

        if (notBlank(dto.getNationality()))   this.nationality   = dto.getNationality().trim();
        if (notBlank(dto.getIntroduction()))  this.introduction  = dto.getIntroduction().trim();
        if (notBlank(dto.getVisitPurpose()))  this.visitPurpose  = dto.getVisitPurpose().trim();
        if (notBlank(dto.getLanguages()))     this.languages     = dto.getLanguages().trim();
        if (notBlank(dto.getHobby()))         this.hobby         = dto.getHobby().trim();

        // 이미지 URL(또는 업로드 후 받은 공개 URL) 반영
        if (notBlank(dto.getProfileImageUrl())) {
            this.profileImageUrl = dto.getProfileImageUrl().trim();
        }
        // updatedAt은 @PreUpdate로 자동 갱신되지만,
        // 트랜잭션 내 즉시 값이 필요하면 아래 한 줄을 유지해도 됩니다.
        this.updatedAt = Instant.now();
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
        this.updatedAt = Instant.now();

    }
}
