package core.domain.user.entity;

import core.global.enums.user.Role;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

/**
 * 프로덕션 코드(User Entity)를 수정하지 않고
 * ID, ActivityPoint 등 Private 필드를 테스트에서 세팅하기 위한 빌더
 */
public class UserTestBuilder {

    // 기본값 설정 (테스트 시 매번 설정하지 않아도 되도록)
    private Long id = 1L;
    private String firstName = "Test";
    private String lastName = "User";
    private String email = "test@example.com";
    private Role userRole = Role.USER;
    private Long activityPoint = 0L;
    private Long visitCount = 0L;
    private String translateLanguage = "en";
    private boolean agreedToPushNotification = true;
    private String appleRefreshToken = null;

    // 필요한 필드만 체이닝 메서드 추가
    public static UserTestBuilder builder() {
        return new UserTestBuilder();
    }

    public UserTestBuilder id(Long id) {
        this.id = id;
        return this;
    }

    public UserTestBuilder name(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
        return this;
    }

    public UserTestBuilder email(String email) {
        this.email = email;
        return this;
    }

    public UserTestBuilder role(Role role) {
        this.userRole = role;
        return this;
    }

    public UserTestBuilder activityPoint(Long point) {
        this.activityPoint = point;
        return this;
    }

    public UserTestBuilder visitCount(Long count) {
        this.visitCount = count;
        return this;
    }

    public UserTestBuilder translateLanguage(String lang) {
        this.translateLanguage = lang;
        return this;
    }

    public User build() {
        // 1. 프로덕션 코드의 @Builder를 사용하여 기본 객체 생성
        // (생성자에 있는 필드들만 여기서 설정)
        User user = User.builder()
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .sex("MALE")          // 기본값
                .country("KR")        // 기본값
                .language("ko")       // 기본값
                .birthdate("2000-01-01") // 기본값
                .purpose("FRIENDS")   // 기본값
                .introduction("Hello")// 기본값
                .hobby("Coding")      // 기본값
                .provider("email")
                .createdAt(Instant.now())
                .appleRefreshToken(appleRefreshToken)
                .build();

        // 2. 생성자에 없는 필드(ID, Point 등)를 리플렉션으로 강제 주입
        // (Entity의 필드명과 문자열이 정확히 일치해야 함)
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "userRole", userRole); // 생성 로직 무시하고 강제 설정
        ReflectionTestUtils.setField(user, "activityPoint", activityPoint);
        ReflectionTestUtils.setField(user, "visitCount", visitCount);
        ReflectionTestUtils.setField(user, "translateLanguage", translateLanguage);
        ReflectionTestUtils.setField(user, "agreedToPushNotification", agreedToPushNotification);

        return user;
    }
}