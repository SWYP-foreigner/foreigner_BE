package core.domain.user.service;


import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.Sex;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TestUserGenerator implements CommandLineRunner {

    private final UserRepository userRepository;

    public TestUserGenerator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        // 이미 1000명 이상이면 건너뛰기
        if (userRepository.count() >= 1000) {
            System.out.println("테스트 유저 이미 존재함, 생성 생략");
            return;
        }

        List<User> users = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            User user = User.builder()
                    .name("User" + i)
                    .sex(i % 2 == 0 ? Sex.MALE : Sex.FEMALE)
                    .age(20 + (i % 30)) // 20~49세
                    .nationality("TestNation")
                    .introduction("Test user " + i)
                    .visitPurpose("Testing")
                    .languages("English")
                    .hobby("None")
                    .provider("test")
                    .socialId("test" + i)
                    .email("user" + i + "@example.com")
                    .build();
            users.add(user);
        }

        userRepository.saveAll(users);
        System.out.println("1000명의 테스트 유저 생성 완료!");
    }
}
