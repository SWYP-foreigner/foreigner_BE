package core.domain.user.service;

import core.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class UserActivityService {

    private final UserRepository userRepository;

    @Async
    @Transactional
    public void updateLastSeenAt(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getLastSeenAt() == null ||
                    ChronoUnit.MINUTES.between(user.getLastSeenAt(), Instant.now()) > 5) {
                user.updateLastSeenAt();
            }
        });
    }
}