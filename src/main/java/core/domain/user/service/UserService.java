package core.domain.user.service;


import com.amazonaws.services.s3.AmazonS3;
import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.dto.UserCreateDto;
import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import core.global.image.entity.Image;
import core.global.image.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class UserService {

    @Value("${ncp.s3.endpoint}")
    private String s3Endpoint;

    @Value("${ncp.s3.bucket}")
    private String s3BucketName;

    @Value("${ncp.s3.bucket}")
    private String bucketName;

    private final AmazonS3 amazonS3;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;

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








    /**
     * @author 한준
     * 로그인 후
     * 프로필 설정 (username 파라미터 제거, 내부에서 인증 컨텍스트 사용)
     *
     * @param userUpdateDTO 프로필 데이터
     * @return 업데이트된 사용자 정보
     */
    @Transactional
    public UserUpdateDTO setupUserProfile(UserUpdateDTO userUpdateDTO) {

        // firstName + lastName 조합으로 사용자 조회
        User user = userRepository.findByFirstAndLastName(
                        userUpdateDTO.getFirstname(),
                        userUpdateDTO.getLastname())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        user.setFirstName(userUpdateDTO.getFirstname());
        user.setLastName(userUpdateDTO.getLastname());
        user.setSex(userUpdateDTO.getGender());
        user.setBirthdate(userUpdateDTO.getBirthday());
        user.setCountry(userUpdateDTO.getCountry());
        user.setIntroduction(userUpdateDTO.getIntroduction());
        user.setPurpose(userUpdateDTO.getPurpose());
        user.setLanguage(listToString(userUpdateDTO.getLanguage()));
        user.setHobby(listToString(userUpdateDTO.getHobby()));

        if (userUpdateDTO.getImageKey() != null && !userUpdateDTO.getImageKey().isEmpty()) {
            String newKey = userUpdateDTO.getImageKey();

            if (user.getProfileImageUrl() != null) {
                deleteProfileImage(user.getProfileImageUrl());
                imageRepository.deleteByUrl(user.getProfileImageUrl());
            }

            user.setProfileImageUrl(newKey);

            /**
             * 이미지 USER 로 들어가서 찾음
             */
            Image profileImage = Image.of(ImageType.USER, user.getId(), newKey,1);
            imageRepository.save(profileImage);
        }

        userRepository.save(user);

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
                .imageKey(user.getProfileImageUrl())
                .build();
    }
    /**
     * 로그인 하고 사용자 프로필 조회 (username 파라미터 제거, 내부에서 인증 컨텍스트 사용)
     */
    public UserUpdateDTO getUserProfile() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        User user = userRepository.findByName(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        return UserUpdateDTO.builder()
                .firstname(user.getFirstName())
                .lastname(user.getLastName())
                .imageKey(generateProfileImageUrl(user.getProfileImageUrl()))
                .language(stringToList(user.getLanguage()))
                .hobby(stringToList(user.getHobby()))
                .build();
    }

    /**
     * 프로필 이미지 삭제 (username 파라미터 제거, 내부에서 인증 컨텍스트 사용)
     */
    @Transactional
    public void deleteProfileImage() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        User user = userRepository.findByName(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (user.getProfileImageUrl() != null) {
            deleteProfileImage(user.getProfileImageUrl());
            user.setProfileImageUrl(null);
            userRepository.save(user);
        }
    }

    /**
     * S3에서 이미지 삭제
     */
    private void deleteProfileImage(String s3ImageKey) {
        if (s3ImageKey != null && !s3ImageKey.isEmpty()) {
            amazonS3.deleteObject(bucketName, s3ImageKey);
        }
    }

    /**
     * 프로필 이미지 공개 URL 생성
     */
    private String generateProfileImageUrl(String s3ImageKey) {
        if (s3ImageKey == null) {
            return null;
        }
        return amazonS3.getUrl(bucketName, s3ImageKey).toString();
    }

    /**
     * List<String>을 쉼표로 구분된 문자열로 변환
     */
    private String listToString(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return list.stream()
                .filter(s -> s != null && !s.trim().isEmpty())
                .collect(Collectors.joining(","));
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

    @Transactional(readOnly = true)
    public UserUpdateDTO getUserProfile(String email) {
        User user = resolveUser(email);

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
                // 이미지 키 그대로 주거나, 공개 URL이 필요하면 generateProfileImageUrl(...) 사용
                .imageKey(user.getProfileImageUrl())
                .build();
    }

    @Transactional
    public void deleteProfileImageTest(String email) {
        User user = resolveUser(email);

        String key = user.getProfileImageUrl();
        if (key != null && !key.isBlank()) {
            // S3 삭제
            amazonS3.deleteObject(bucketName, key);

            // 이미지 레코드 삭제(있으면)
            imageRepository.deleteByUrl(key);

            // 유저 컬럼 비우기
            user.setProfileImageUrl(null);
            userRepository.save(user);
        }
    }

    /**
     * 테스트 용으로 이걸 이용해서 사람을 찾는다 .
     * 사용자 식별 우선순위:
     * 1) email(헤더)  2) firstName+lastName(쿼리)  3) SecurityContext(username)
     */
    private User resolveUser(String email) {
            return userRepository.findByEmail(email)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }


    @Transactional
    public UserUpdateDTO updateUserProfileTest(String email, UserUpdateDTO dto) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        User user = userRepository.findByEmail(email.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (notBlank(dto.getFirstname()))    user.setFirstName(dto.getFirstname().trim());
        if (notBlank(dto.getLastname()))     user.setLastName(dto.getLastname().trim());
        if (dto.getGender() != null)         user.setSex(dto.getGender());
        if (dto.getBirthday() != null)       user.setBirthdate(dto.getBirthday());
        if (notBlank(dto.getCountry()))      user.setCountry(dto.getCountry().trim());
        if (notBlank(dto.getIntroduction())) user.setIntroduction(dto.getIntroduction().trim());
        if (notBlank(dto.getPurpose()))      user.setPurpose(dto.getPurpose().trim());
        if (dto.getLanguage() != null)       user.setLanguage(String.join(",", dto.getLanguage()));
        if (dto.getHobby() != null)          user.setHobby(String.join(",", dto.getHobby()));

        if (notBlank(dto.getImageKey())) {
            String newKey = dto.getImageKey().trim();
            String oldKey = user.getProfileImageUrl();

            /**
             * 기존 이미지키 랑 다르면 수정을 한다.
             *  S3에서 기존 이미지 삭제
             *  Image 테이블에서도 삭제
             *  새 이미지 저장
             */
            if (notBlank(oldKey) && !oldKey.equals(newKey)) {
                deleteProfileImageKey(oldKey);

                imageRepository.deleteByUrl(oldKey);
            }

            user.setProfileImageUrl(newKey);
            imageRepository.save(Image.of(ImageType.USER, user.getId(), newKey,1));
        }

        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

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
                .imageKey(user.getProfileImageUrl())
                .build();
    }

    /**
     *
     *이미지 키로 삭제를 함
     * @param s3ImageKey
     */

    private void deleteProfileImageKey(String s3ImageKey) {
        if (notBlank(s3ImageKey)) {
            amazonS3.deleteObject(bucketName, s3ImageKey);
        }
    }

    /**
     * 로그인한 상태로 실제 테스트 하는 로직
     * @param dto
     * @return
     */
    @Transactional
    public UserUpdateDTO updateUserProfile(UserUpdateDTO dto) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        User user = userRepository.findByEmail(username.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (notBlank(dto.getFirstname()))    user.setFirstName(dto.getFirstname().trim());
        if (notBlank(dto.getLastname()))     user.setLastName(dto.getLastname().trim());
        if (dto.getGender() != null)         user.setSex(dto.getGender());
        if (dto.getBirthday() != null)       user.setBirthdate(dto.getBirthday());
        if (notBlank(dto.getCountry()))      user.setCountry(dto.getCountry().trim());
        if (notBlank(dto.getIntroduction())) user.setIntroduction(dto.getIntroduction().trim());
        if (notBlank(dto.getPurpose()))      user.setPurpose(dto.getPurpose().trim());
        if (dto.getLanguage() != null)       user.setLanguage(String.join(",", dto.getLanguage()));
        if (dto.getHobby() != null)          user.setHobby(String.join(",", dto.getHobby()));

        // 이미지 교체 로직
        if (notBlank(dto.getImageKey())) {
            String newKey = dto.getImageKey().trim();
            String oldKey = user.getProfileImageUrl();

            if (notBlank(oldKey) && !oldKey.equals(newKey)) {
                deleteProfileImageKey(oldKey);
                imageRepository.deleteByUrl(oldKey);
            }

            user.setProfileImageUrl(newKey);
            imageRepository.save(Image.of(ImageType.USER, user.getId(), newKey,1));
        }

        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

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
                .imageKey(user.getProfileImageUrl())
                .build();
    }

}
