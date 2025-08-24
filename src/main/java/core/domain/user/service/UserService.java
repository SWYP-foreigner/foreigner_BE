package core.domain.user.service;


import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.dto.UserCreateDto;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import core.global.image.service.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final ImageService imageService;

    public User create(UserCreateDto memberCreateDto){
        User user = User.builder()
                .email(memberCreateDto.getEmail())
                .build();
        userRepository.save(user);
        return user;
    }

    @Transactional
    public User createOauth(String socialId, String email, String provider) {
        log.info("createOauth start: socialId={}, email={}, provider={}", socialId, email, provider);

        User u = new User();
        u.setSocialId(socialId);
        u.setEmail(email);
        u.setProvider(provider);
        User saved = userRepository.save(u);
        log.info("createOauth saved: id={}", saved.getId());
        return saved;
    }

    public User getUserBySocialId(String socialId) {
        log.debug("getUserBySocialId: {}", socialId);
        return userRepository.findBySocialId(socialId).orElse(null);
    }


    @Transactional
    public User createUserProfile(UserUpdateDTO dto) {
        User user = User.builder().build();
        user.updateProfile(dto);     // DTO 값 반영
        return userRepository.save(user);
    }



    @Transactional
    public UserUpdateDTO setupUserProfile(UserUpdateDTO dto) {
        User user = userRepository.findByFirstAndLastName(dto.getFirstname(), dto.getLastname())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 일반 프로필 필드 갱신
        // (firstname, lastname, gender, birthday, country, introduction, purpose, language, hobby 등)
        // ... 기존 그대로 ...

        // ✅ 이미지: 요청에 imageKey가 있으면 upsert (User는 아무 것도 저장하지 않음)
        if (dto.getImageKey() != null && !dto.getImageKey().isBlank()) {
            imageService.upsertUserProfileImage(user.getId(), dto.getImageKey());
        }

        userRepository.save(user);

        // 조회 시엔 imageService에서 가져와 DTO에 채워줌
        String profileKey = imageService.getUserProfileKey(user.getId());

        return UserUpdateDTO.builder()
                .firstname(user.getFirstName())
                .lastname(user.getLastName())
                .gender(user.getSex())
                .birthday(user.getBirthdate())
                .country(user.getCountry())
                .introduction(user.getIntroduction())
                .purpose(user.getPurpose())
                .language(stringToList(user.getLanguage()))
                .hobby(stringToList(user.getHobby()))
                .imageKey(profileKey)
                .build();
    }

    @Transactional(readOnly = true)
    public UserUpdateDTO getUserProfile() {
        // 인증 사용자 조회
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByName(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String profileKey = imageService.getUserProfileKey(user.getId());

        return UserUpdateDTO.builder()
                .firstname(user.getFirstName())
                .lastname(user.getLastName())
                .gender(user.getSex())
                .birthday(user.getBirthdate())
                .country(user.getCountry())
                .introduction(user.getIntroduction())
                .purpose(user.getPurpose())
                .language(stringToList(user.getLanguage()))
                .hobby(stringToList(user.getHobby()))
                .imageKey(profileKey)
                .build();
    }


    @Transactional
    public void deleteProfileImage() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByName(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        imageService.deleteUserProfileImage(user.getId());
        // User에는 아무 것도 저장하지 않음
    }


    /**
     * 쉼표로 구분된 문자열을 List<String>으로 변환
     */
    private List<String> stringToList(String str) {
        if (str == null || str.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public UserUpdateDTO updateUserProfile(UserUpdateDTO dto) {
        return null;
    }
}
