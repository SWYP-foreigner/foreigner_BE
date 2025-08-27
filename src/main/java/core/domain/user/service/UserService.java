package core.domain.user.service;


import core.domain.user.dto.UserSearchDTO;
import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.config.JwtTokenProvider;
import core.global.dto.UserCreateDto;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import core.global.image.service.ImageService;
import core.global.image.service.impl.ImageServiceImpl;
import core.global.service.RedisService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final ImageService imageService;
    private final RedisService redisService;
    private final JwtTokenProvider jwtTokenProvider;
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

    public User getUserBySocialIdAndProvider(String socialId, String provider) {
        log.debug("getUserBySocialIdAndProvider: socialId={}, provider={}", socialId, provider);
        return userRepository.findByProviderAndSocialId(provider, socialId).orElse(null);
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
                .email(user.getEmail())
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
                .email(user.getEmail())
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
        if(dto.getEmail()!=null){
            String v= dto.getEmail().trim();
            user.setEmail(v);
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
                .email(email)
                .build();
    }
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        userRepository.delete(user);
    }

    @Transactional
    public void deleteUser(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("토큰이 유효하지 않습니다.");
        }
        String accessToken = authHeader.substring(7);

        // 토큰에서 유저 ID 추출
        Long userId = jwtTokenProvider.getUserIdFromAccessToken(accessToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));


        userRepository.delete(user);

        // 2. Redis에서 Refresh Token 삭제
        redisService.deleteRefreshToken(userId);

        long expiration = jwtTokenProvider.getExpiration(accessToken).getTime() - System.currentTimeMillis();
        redisService.blacklistAccessToken(accessToken, expiration);
    }

    /**
     *  두개의 이름 중 하나만 있더라도 바로 검색이 되게 함
     * @param firstName
     * @param lastName
     * @return
     */
    @Transactional(readOnly = true)
    public List<UserSearchDTO> findUserByNameExcludingSelf(String firstName, String lastName) {
        String email = getCurrentEmailOrThrow();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));


        if ((firstName == null || firstName.isBlank()) &&
                (lastName  == null || lastName.isBlank())) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        Long meId = user.getId();

        String fn = firstName == null ? null : firstName.trim();
        String ln = lastName  == null ? null : lastName.trim();

        List<User> users;
        if (notBlank(fn) && notBlank(ln)) {
            users = userRepository
                    .findByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndIdNot(fn, ln, meId);
        } else if (notBlank(fn)) {
            users = userRepository
                    .findByFirstNameIgnoreCaseAndIdNot(fn, meId);
        } else { // notBlank(ln) 보장됨
            users = userRepository
                    .findByLastNameIgnoreCaseAndIdNot(ln, meId);
        }

        if (users.isEmpty()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        return users.stream()
                .filter(u -> !u.getId().equals(meId)) // 안전망
                .map(this::toSearchDto)
                .toList();
    }

    /** 현재 인증 컨텍스트에서 email만 확보 (ID는 repo로 조회) */
    private String getCurrentEmailOrThrow() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
        }

        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String email = jwtAuth.getToken().getClaim("email");
            if (email == null || email.isBlank()) {
                email = jwtAuth.getToken().getSubject(); // sub fallback
            }
            if (email == null || email.isBlank()) {
                throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
            }
            return email;
        }

        String name = auth.getName();
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_AVAILABLE);
        }
        return name;
    }

    private UserSearchDTO toSearchDto(User u) {
        return UserSearchDTO.builder()
                .id(u.getId())
                .firstName(u.getFirstName())
                .lastName(u.getLastName())
                .gender(u.getSex())
                .country(u.getCountry())
                 .imageKey(imageService.getUserProfileKey(u.getId())) // 필요 시 주석 해제
                .build();
    }
}