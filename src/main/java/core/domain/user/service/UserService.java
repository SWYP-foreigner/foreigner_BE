package core.domain.user.service;


import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.dto.UserCreateDto;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    public User create(UserCreateDto memberCreateDto){
       User user = User.builder()
                .email(memberCreateDto.getEmail())
                .build();
        userRepository.save(user);
        return user;
    }

    public  User getUserBySocialId(String socialId){
        User member = userRepository.findBySocialId(socialId).orElse(null);
        return member;
    }

    public  User createOauth(String socialId, String email, String socialType){
        User member =  User.builder()
                .email(email)
                .provider(socialType)
                .socialId(socialId)
                .build();
      userRepository.save(member);
        return member;
    }

    public User findUserByUsername(String username) {
        // 사용자 이름으로 User 객체를 찾고, 없으면 예외를 발생시킵니다.
        return userRepository.findByName(username)
                .orElseThrow(() -> new EntityNotFoundException(username + " 사용자를 찾을 수 없습니다."));
    }

    public User findById(Long id){
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(id + " 사용자를 찾을 수 없습니다."));
    }
}
