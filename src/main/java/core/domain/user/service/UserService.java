package core.domain.user.service;


import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.dto.UserCreateDto;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import core.global.image.service.ImageService;
import core.global.image.service.impl.ImageServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
    public UserUpdateDTO setupUserProfile(UserUpdateDTO dto) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
        }
        String email = (auth instanceof JwtAuthenticationToken jwtAuth)
                ? jwtAuth.getToken().getClaim("email")
                : auth.getName();
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (notBlank(dto.getFirstname()))    user.setFirstName(dto.getFirstname().trim());
        if (notBlank(dto.getLastname()))     user.setLastName(dto.getLastname().trim());
        if (dto.getGender() != null)         user.setSex(dto.getGender());
        if (dto.getBirthday() != null)       user.setBirthdate(dto.getBirthday()); // 형식 검증이 필요하면 여기서 추가

        if (notBlank(dto.getCountry()))      user.setCountry(dto.getCountry().trim());

        if (notBlank(dto.getIntroduction())) {
            String intro = dto.getIntroduction().trim();
            user.setIntroduction(intro.length() > 40 ? intro.substring(0, 40) : intro); // 컬럼 길이 보호
        }
        if (notBlank(dto.getPurpose())) {
            String purpose = dto.getPurpose().trim();
            user.setPurpose(purpose.length() > 40 ? purpose.substring(0, 40) : purpose);
        }

        if (dto.getLanguage() != null) {
            String csv = dto.getLanguage().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
            if (!csv.isEmpty()) user.setLanguage(csv);
        }
        if (dto.getHobby() != null) {
            String csv = dto.getHobby().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
            if (!csv.isEmpty()) user.setHobby(csv);
        }

        user.setUpdatedAt(Instant.now());

        String finalImageKey = null;
        if (notBlank(dto.getImageKey())) {
            finalImageKey = imageService.upsertUserProfileImage(user.getId(), dto.getImageKey().trim());
        }

        userRepository.save(user);


        if (finalImageKey == null) {
            finalImageKey = imageService.getUserProfileKey(user.getId());
        }

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
                .imageKey(finalImageKey)
                .build();
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }


    @Transactional(readOnly = true)
    public UserUpdateDTO getUserProfile() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
        }
        String email = (auth instanceof JwtAuthenticationToken jwtAuth)
                ? jwtAuth.getToken().getClaim("email")
                : auth.getName();
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
        }

        User user = userRepository.findByEmail(email)
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
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
        }
        String email = (auth instanceof JwtAuthenticationToken jwtAuth)
                ? jwtAuth.getToken().getClaim("email")
                : auth.getName();
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
        }


        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        imageService.deleteUserProfileImage(user.getId());
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

    @Transactional
    public UserUpdateDTO updateUserProfile(UserUpdateDTO dto) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
        }
        String email = (auth instanceof JwtAuthenticationToken jwtAuth)
                ? jwtAuth.getToken().getClaim("email")
                : auth.getName();
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (notBlank(dto.getFirstname()))    user.setFirstName(dto.getFirstname().trim());
        if (notBlank(dto.getLastname()))     user.setLastName(dto.getLastname().trim());
        if (dto.getGender() != null)         user.setSex(dto.getGender());
        if (dto.getBirthday() != null)       user.setBirthdate(dto.getBirthday());
        if (notBlank(dto.getCountry()))      user.setCountry(dto.getCountry().trim());

        if (notBlank(dto.getIntroduction())) {
            String v = dto.getIntroduction().trim();
            user.setIntroduction(v.length() > 40 ? v.substring(0, 40) : v); // 컬럼 길이 보호
        }
        if (notBlank(dto.getPurpose())) {
            String v = dto.getPurpose().trim();
            user.setPurpose(v.length() > 40 ? v.substring(0, 40) : v);
        }

        if (dto.getLanguage() != null) {
            String csv = dto.getLanguage().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
            if (!csv.isEmpty()) user.setLanguage(csv);
        }
        if (dto.getHobby() != null) {
            String csv = dto.getHobby().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
            if (!csv.isEmpty()) user.setHobby(csv);
        }

        user.setUpdatedAt(Instant.now());

        String finalImageKey = null;
        if (notBlank(dto.getImageKey())) {
            finalImageKey = imageService.upsertUserProfileImage(user.getId(), dto.getImageKey().trim());
        }

        userRepository.save(user);

        if (finalImageKey == null) {
            finalImageKey = imageService.getUserProfileKey(user.getId());
        }


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
                .imageKey(finalImageKey)
                .build();
    }
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        userRepository.delete(user);
    }

}