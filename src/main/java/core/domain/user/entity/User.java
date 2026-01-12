package core.domain.user.entity;

import core.domain.usernotificationsetting.entity.UserNotificationSetting;
import core.domain.notification.entity.Notification;
import core.global.enums.Role;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"provider", "social_id"}),
                @UniqueConstraint(columnNames = {"email"})
        })
@Getter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

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

    @Column(name = "introduction", length = 70)
    private String introduction;

    @Column(name = "visit_purpose", length = 40)
    private String purpose;

    @Column(name = "languages", nullable = true)
    private String language;

    @Column(name = "translate_language", nullable = true)
    private String translateLanguage;

    @Column(name = "hobby", nullable = true)
    private String hobby;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "provider", nullable = true)
    private String provider;

    @Column(name = "social_id", nullable = true)
    private String socialId;

    @Column(name = "email", nullable = true)
    private String email;

    @Column(name = "password", nullable = true)
    private String password;

    @Column(name = "is_new_user")
    private boolean isNewUser =true;

    @Column(name = "agreed_to_push_notification")
    private boolean agreedToPushNotification = false;

    @Column(name = "agreed_to_terms")
    private boolean agreedToTerms = false;
    @Column(name = "apple_refresh_token")
    private String appleRefreshToken;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "residence", length = 50)
    private String residence;


    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 20)
    private Role userRole = Role.VISITOR;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Notification> notifications = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserNotificationSetting> notificationSettings = new ArrayList<>();

    // [추가 1] 활동 포인트 (Method B 구현용)
    // 접속, 채팅, 좋아요 등을 할 때마다 쌓이는 점수 -> '친절한 유저' 판단 기준
    @Column(name = "activity_point")
    private Long activityPoint = 0L;

    @Column(name = "visit_count")
    private Long visitCount = 0L;

    @Column(name = "reply_rate")
    private Double replyRate = 0.0;

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
                String email,String appleRefreshToken
            ,Instant createdAt) {
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
        this.appleRefreshToken = appleRefreshToken;
        this.createdAt = createdAt;

        this.updateRoleBasedOnProfile();
    }

    /**
     * [핵심 로직]
     * 프로필 필드 완성도에 따라 userRole을 VISITOR 또는 USER로 자동 변경합니다.
     * ADMIN 역할은 절대 변경하지 않습니다.
     * AI의 역할 또한 변경하지 않도록 추가
     */
    private void updateRoleBasedOnProfile() {
        if (this.userRole == Role.ADMIN || this.userRole == Role.AI) {
            return;
        }

        if (this.birthdate == null
                || this.purpose == null
                || this.introduction == null
                || this.language == null
                || this.hobby == null
                || this.sex == null
                || this.country == null){
            this.userRole = Role.VISITOR;
        } else {
            this.userRole = Role.USER;
        }
    }


    public void updateFirstName(String firstName) {
        if (notBlank(firstName)) this.firstName = firstName.trim();
        touchUpdatedAt();
    }

    public void updateLastName(String lastName) {
        if (notBlank(lastName)) this.lastName = lastName.trim();
        touchUpdatedAt();
    }

    public void updateCountry(String country) {
        if (notBlank(country)) this.country = country.trim();
        touchUpdatedAt();
        updateRoleBasedOnProfile();
    }

    public void updateTranslateLanguage(String translateLanguage) {
        if (notBlank(translateLanguage)) this.translateLanguage = translateLanguage;
        touchUpdatedAt();
    }


    public void updateSex(String sex) {
        if (sex != null) this.sex = sex;
        updateRoleBasedOnProfile();
        touchUpdatedAt();
    }

    public void updateBirthdate(String birthdate) {
        if (birthdate != null) this.birthdate = birthdate;
        updateRoleBasedOnProfile();
        touchUpdatedAt();
    }

    public void updateIntroduction(String introduction) {
        if (notBlank(introduction)) this.introduction = introduction.trim();
        updateRoleBasedOnProfile();
        touchUpdatedAt();
    }

    public void updatePurpose(String purpose) {
        if (notBlank(purpose)) this.purpose = purpose.trim();
        updateRoleBasedOnProfile();
        touchUpdatedAt();
    }

    public void updateLanguage(String language) {
        if (notBlank(language)) this.language = language;
        updateRoleBasedOnProfile();
        touchUpdatedAt();
    }

    public void updateHobby(String hobby) {
        if (notBlank(hobby)) this.hobby = hobby;
        updateRoleBasedOnProfile();
        touchUpdatedAt();
    }

    public void updateGender(String s) {
        this.sex = s;
        updateRoleBasedOnProfile();
        touchUpdatedAt();
    }


    public void updatePassword(String password) {
        if (notBlank(password)) this.password = password;
        touchUpdatedAt();
    }

    public void updateEmail(String email) {
        if (notBlank(email)) this.email = email;
        touchUpdatedAt();
    }

    public void updateIsNewUser(boolean isNewUser) {
        this.isNewUser = isNewUser;
        touchUpdatedAt();
    }

    public void updateAgreedToPushNotification(boolean agreed) {
        this.agreedToPushNotification = agreed;
        touchUpdatedAt();
    }

    public void updateAgreedToTerms(boolean agreed) {
        this.agreedToTerms = agreed;
        touchUpdatedAt();
    }

    public void updateProvider(String provider) {
        if (notBlank(provider)) this.provider = provider;
        touchUpdatedAt();
    }

    public void updateSocialId(String socialId) {
        if (notBlank(socialId)) this.socialId = socialId;
        touchUpdatedAt();
    }
    public void updateLastSeenAt() {
        this.lastSeenAt = Instant.now();
    }
    public void updateCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void updateUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    public void updateResidence(String residence) {
        if (notBlank(residence)) this.residence = residence.trim();
        touchUpdatedAt();
    }


    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }


    public void changeUserRole(Role role) {
        this.userRole = role;
    }
    /**
     * [알고리즘용] 신규 유저 판단 로직
     * DB의 isNewUser(프로필 설정 여부)와 다르게,
     * 가입한 지 특정 일수(예: 7일)가 지났는지를 판단합니다.
     */
    public boolean isJoinedWithinDays(int days) {
        if (this.createdAt == null) return false;
        Instant threshold = Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);

        return this.createdAt.isAfter(threshold);
    }

    /**
     * [알고리즘용] 유저 활동 점수 (Method B)
     * 응답률 데이터 부재로 인해 활동 포인트와 방문 횟수를 정규화하여 반환
     */
    public double getEngagementScore() {
        // 예: 활동 포인트 최대 1000점, 방문 횟수 가중치 등 (서비스 규모에 따라 튜닝 필요)
        double pointScore = (this.activityPoint != null) ? this.activityPoint * 0.5 : 0;
        double visitScore = (this.visitCount != null) ? this.visitCount * 2.0 : 0;
        return pointScore + visitScore;
    }
    public void incrementVisitCount() {
        if (this.visitCount == null) {
            this.visitCount = 0L;
        }
        this.visitCount++;
        touchUpdatedAt(); // 수정 시간 갱신
    }

    /**
     * [비즈니스 로직] 활동 포인트 적립
     * @param points 적립할 점수 (예: 채팅 5점, 좋아요 10점 등)
     */
    public void addActivityPoint(Long points) {
        if (points == null || points <= 0) {
            return; // 0 이하의 점수는 무시
        }

        if (this.activityPoint == null) {
            this.activityPoint = 0L;
        }
        this.activityPoint += points;
        touchUpdatedAt(); // 수정 시간 갱신
    }
}