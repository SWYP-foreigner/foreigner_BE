package core.global.metrics;

import core.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserCohortService {
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<Object[]> getCohortData() {
        return userRepository.cohortRetention30d();
    }
}