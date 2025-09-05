package core.domain.user.entity;

import core.domain.user.dto.UserUpdateDTO;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;


@Entity
@Table(name = "users",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"provider", "social_id"}),
                @UniqueConstraint(columnNames = {"email"})
        })
@Getter
@NoArgsConstructor
@Setter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "first_name", nullable = true)
    private String firstName;

    @Column(name = "last_name", nullable = true)
    private String lastName;

    @Column(name = "SEX", nullable = true)
    private String sex;

    @Column(name = "birth_date", nullable = true)
    private String birthdate;

    @Column(name = "nationality", nullable = true)
    private String country;

    @Column(name = "introduction", length = 40, nullable = true)
    private String introduction;

    @Column(name = "visit_purpose", length = 40, nullable = true)
    private String purpose;

    @Column(name = "languages", nullable = true)
    private String language;

    @Column(name = "translate_language", nullable = true)
    private String translateLanguage;

    @Column(name = "hobby", nullable = true)
    private String hobby;

    @Column(name = "created_at", nullable = true)
    private Instant createdAt;

    @Column(name = "updated_at",nullable = true)
    private Instant updatedAt;

    @Column(name = "provider", nullable = true)
    private String provider;

    @Column(name = "social_id", nullable = true)
    private String socialId;

    @Column(name = "email", nullable = true)
    private String email;
    @Column(name = "password", nullable = true)
    private String password;

    @Column(name = "agreed_to_push_notification")
    private boolean agreedToPushNotification = false;

    @Column(name = "agreed_to_terms")
    private boolean agreedToTerms = false;
    @Builder
    public User(String firstName,
                String lastName,
                String sex,
                String birthdate,
                String country,
                String introduction,
                String purpose,
                String language,
                String hobby,
                String provider,
                String socialId,
                String email) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.sex = sex;
        this.birthdate = birthdate;
        this.country = country;
        this.introduction = introduction;
        this.purpose = purpose;
        this.language = language;
        this.hobby = hobby;
        this.provider = provider;
        this.socialId = socialId;
        this.email = email;
    }

    public void updateProfile(UserUpdateDTO dto) {
        if (notBlank(dto.getFirstname())) this.firstName = dto.getFirstname().trim();
        if (notBlank(dto.getLastname()))  this.lastName  = dto.getLastname().trim();

        if (dto.getGender() != null) this.sex = dto.getGender();
        if (dto.getBirthday() != null) this.birthdate = dto.getBirthday();

        if (notBlank(dto.getCountry())) this.country = dto.getCountry().trim();
        if (notBlank(dto.getIntroduction())) this.introduction = dto.getIntroduction().trim();
        if (notBlank(dto.getPurpose())) this.purpose = dto.getPurpose().trim();

        if (dto.getLanguage() != null && !dto.getLanguage().isEmpty()) {
            this.language = String.join(",", dto.getLanguage());
        }
        if (dto.getHobby() != null && !dto.getHobby().isEmpty()) {
            this.hobby = String.join(",", dto.getHobby());
        }


        this.updatedAt = Instant.now();
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}