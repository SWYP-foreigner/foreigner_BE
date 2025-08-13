package core.domain.user.service;


import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.dto.UserCreateDto;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    public User findById(Long id){
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }



    public User updateUser(String loginName, UserUpdateDTO dto) {
        User user = userRepository.findByName(loginName)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.updateProfile(dto); // 부분 업데이트 + 검증 + 타임스탬프 갱신
        return user; // dirty checking으로 자동 반영
    }
}
