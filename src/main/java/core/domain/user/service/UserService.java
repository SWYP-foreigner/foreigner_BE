package core.domain.user.service;


import core.domain.image.storage.ImageStorage;
import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.dto.UserCreateDto;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;
    private final ImageStorage imageStorage;


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
    public User updateUser(String loginEmail, UserUpdateDTO dto, @Nullable MultipartFile image) {
        User user = userRepository.findByEmail(loginEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));


        if (image != null && !image.isEmpty()) {

            try {
                String url = imageStorage.uploadProfileImage(image, user.getId());
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
            }
        }

        user.updateProfile(dto);
        return user;
    }

    @Transactional
    public User setupUserProfile(Long userId, UserUpdateDTO dto, @Nullable MultipartFile image) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (image != null && !image.isEmpty()) {

            try {
                String url = imageStorage.uploadProfileImage(image, user.getId());
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);// 필요 시 ErrorCode 추가
            }
        }

        user.updateProfile(dto);
        return user;
    }


    @Transactional
    public User setupUserNameProfile(String loginName, UserUpdateDTO dto, @Nullable List<MultipartFile> image) {
        User user = userRepository.findByEmail(loginName)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (image == null || image.isEmpty() || image.get(0) == null || image.get(0).isEmpty()) {
            throw new BusinessException(ErrorCode.PROFILE_IMAGE_REGISTER_REQUIRED); // "사진은 한 장만 등록할 수 있습니다" 요구사항에 맞춤
        }
        if (image.size() > 1) {
            throw new BusinessException(ErrorCode.PROFILE_IMAGE_ONLY_ONE);
        }

        MultipartFile file = image.get(0);

        try {
            String url = imageStorage.uploadProfileImage(file, user.getId());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        user.updateProfile(dto);
        return user;
    }
}
