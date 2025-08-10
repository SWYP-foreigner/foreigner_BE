package core.service;

import com.foreigner.core.domain.user.User;
import com.foreigner.core.dto.UserCreateDto;
import com.foreigner.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    public User  create(UserCreateDto memberCreateDto){
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
}
