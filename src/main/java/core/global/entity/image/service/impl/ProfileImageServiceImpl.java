package core.global.entity.image.service.impl;

import core.global.entity.image.dto.ImageDto;
import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.service.ProfileImageService;
import core.global.enums.ImageModerationStatus;
import core.global.enums.ImageType;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static core.global.entity.image.utils.UrlUtil.buildCdnUrlFromKey;
import static core.global.entity.image.utils.UrlUtil.toKeyFromUrlOrKey;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileImageServiceImpl implements ProfileImageService {

    private static final long PROFILE_MAX_BYTES = 10L * 1024 * 1024;

    private final S3Client s3Client;
    private final ImageRepository imageRepository;
    private final ImageStorageClient storageClient;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.endpoint}")
    private String endPoint;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    /**
     * 초기 셋업 시 유저 프로필 설정
     *
     * @param userId
     * @param requestedKeyOrUrl
     */
    @Transactional
    @Override
    public void saveUserProfileImage(Long userId, String requestedKeyOrUrl) {
        // 1) 입력 검증
        validateProfileInput(userId, requestedKeyOrUrl);

        if (imageRepository.existsByImageTypeAndRelatedId(ImageType.USER, userId)) {
            throw new BusinessException(ImageErrorCode.USER_IMAGES_ALREADY_EXIST);
        }

        // 2) URL/Key 판정 및 변환
        RequestInfo requestInfo = resolveRequestInfo(requestedKeyOrUrl);

        // 3) 기본이미지가 아니면 헤더 검사(용량 제한 포함)
        validateImageHeadIfNecessary(requestInfo);

        // 5) 최종 후보 키/URL 계산 (버전드 키 전략)
        String candidateFinalKey = computeCandidateFinalKey(userId, requestInfo);

        // 9) staging → 영구(버전드 키) 이동 또는 as-is 사용
        String finalKey = moveStagingProfileIfNecessary(userId, requestInfo, candidateFinalKey);

        // 10) 저장
        saveImageInDB(userId, ImageType.USER, finalKey);
    }

    /**
     * 프로필 수정 시 이미지 변경
     *
     * @param userId
     * @param requestedKeyOrUrl
     * @return
     */
    @Transactional
    @Override
    public String updateUserProfileImage(Long userId, String requestedKeyOrUrl) {
        // 1) 입력 검증
        validateProfileInput(userId, requestedKeyOrUrl);

        // 2) URL/Key 판정 및 변환
        RequestInfo requestInfo = resolveRequestInfo(requestedKeyOrUrl);


        // 3) 기본이미지가 아니면 헤더 검사(용량 제한 포함)
        validateImageHeadIfNecessary(requestInfo);

        // 4) 기존 이미지 조회
        Optional<Image> existingOpt =
                imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId);

        // 5) 최종 후보 키/URL 계산 (버전드 키 전략)
        String candidateFinalKey = computeCandidateFinalKey(userId, requestInfo);
        String candidateFinalUrl = buildCdnUrlFromKey(cdnBaseUrl, candidateFinalKey);


        // 6) 동일 URL이면 no-op (버전 키면 보통 달라서 여기 안 걸림)
        if (isNoOp(existingOpt, candidateFinalUrl)) {
            return candidateFinalUrl;
        }

        // 7) 기존 S3 삭제 (있으면, 그리고 default가 아니면)
        deleteOldS3ImageIfNecessary(userId, existingOpt);

        // 8) 기존 DB 삭제
        imageRepository.deleteByImageTypeAndRelatedId(ImageType.USER, userId);

        imageRepository.flush();

        // 9) staging → 영구(버전드 키) 이동 또는 as-is 사용
        String finalKey = moveStagingProfileIfNecessary(userId, requestInfo, candidateFinalKey);

        // 10) 저장
        return saveImageInDB(userId, ImageType.USER, finalKey);
    }

    @Override
    @Transactional
    public void deleteUserProfileImage(Long userId) {

        imageRepository.deleteByImageTypeAndRelatedId(ImageType.USER, userId);
        String folder = "users/%d/".formatted(userId);
        try {
            // 같은 클래스 내에 deleteFolder가 있다면 그대로 호출
            storageClient.deleteFolder(folder);
        } catch (BusinessException e) {
            // 폴더 삭제 실패는 경고만 남기고, 아래 레거시 개별 삭제도 시도
            log.warn("profile folder delete failed (ignored): {}", e.getMessage());
        }
    }

    @Override
    public String getUserProfileKey(Long userId) {
        return imageRepository
                .findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId)
                .map(Image::getUrl)
                .orElse(null);
    }

    /**
     * 초기 채팅방 생성 시 이미지 등록
     *
     * @param chatRoomId
     * @param requestedKeyOrUrl
     */
    @Transactional
    @Override
    public void saveChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl) {
        // 1) 입력 검증
        validateProfileInput(chatRoomId, requestedKeyOrUrl);

        if (imageRepository.existsByImageTypeAndRelatedId(ImageType.CHAT_ROOM, chatRoomId)) {
            throw new BusinessException(ImageErrorCode.CHATROOM_IMAGES_ALREADY_EXIST);
        }

        // 2) URL/Key 판정 및 변환
        RequestInfo requestInfo = resolveRequestInfo(requestedKeyOrUrl);

        // 3) 기본이미지가 아니면 헤더 검사(용량 제한 포함)
        validateImageHeadIfNecessary(requestInfo);

        // 5) 최종 후보 키/URL 계산
        String candidateFinalKey = computeChatRoomCandidateFinalKey(chatRoomId, requestInfo);

        // 9) staging → 영구 이동 또는 as-is 사용
        String finalKey = moveChatRoomStagingIfNecessary(chatRoomId, requestInfo, candidateFinalKey);

        // 10) 저장 및 종료
        saveImageInDB(chatRoomId, ImageType.CHAT_ROOM, finalKey);
    }

    /**
     * 채팅방 이미지 수정 시 이미지 변경
     *
     * @param chatRoomId
     * @param requestedKeyOrUrl
     * @return
     */
    @Transactional
    @Override
    public String updateChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl) {
        // 1) 입력 검증
        validateProfileInput(chatRoomId, requestedKeyOrUrl);

        // 2) URL/Key 판정 및 변환
        RequestInfo requestInfo = resolveRequestInfo(requestedKeyOrUrl);

        // 3) 기본이미지가 아니면 헤더 검사(용량 제한 포함)
        validateImageHeadIfNecessary(requestInfo);

        // 4) 기존 이미지 조회
        Optional<Image> existingOpt =
                imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, chatRoomId);

        // 5) 최종 후보 키/URL 계산
        String candidateFinalKey = computeChatRoomCandidateFinalKey(chatRoomId, requestInfo);
        String candidateFinalUrl = buildCdnUrlFromKey(cdnBaseUrl, candidateFinalKey);

        // 6) 동일 URL이면 no-op
        if (isNoOp(existingOpt, candidateFinalUrl)) {
            return candidateFinalUrl;
        }

        // 7) 기존 S3 삭제 (있고, default가 아니면)
        deleteOldS3ImageIfNecessaryForChatRoom(chatRoomId, existingOpt);

        // 8) 기존 DB 삭제
        imageRepository.deleteByImageTypeAndRelatedId(ImageType.CHAT_ROOM, chatRoomId);

        imageRepository.flush();

        // 9) staging → 영구 이동 또는 as-is 사용
        String finalKey = moveChatRoomStagingIfNecessary(chatRoomId, requestInfo, candidateFinalKey);

        // 10) 저장 및 종료
        return saveImageInDB(chatRoomId, ImageType.CHAT_ROOM, finalKey);
    }

    @Transactional
    @Override
    public void deleteChatRoomProfileImage(Long chatRoomId) {

        String folder = "chatRoom/%d/".formatted(chatRoomId);
        try {
            // 같은 클래스 내에 deleteFolder가 있다면 그대로 호출
            storageClient.deleteFolder(folder);
        } catch (BusinessException e) {
            // 폴더 삭제 실패는 경고만 남기고, 아래 레거시 개별 삭제도 시도
            log.warn("profile folder delete failed (ignored): {}", e.getMessage());
        }

        imageRepository.deleteByImageTypeAndRelatedId(ImageType.CHAT_ROOM, chatRoomId);
    }

    @Override
    public String getRoomImageUrl(Long roomId) {
        return imageRepository
                .findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, roomId)
                .map(Image::getUrl)
                .orElse(null);
    }

    @Override
    public List<ImageDto> findImagesForChatRooms(List<Long> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Image> images = imageRepository.findAllByImageTypeAndRelatedIdIn(ImageType.CHAT_ROOM, roomIds);

        return images.stream()
                .map(image -> new ImageDto(image.getId(), image.getRelatedId(), image.getUrl()))
                .collect(Collectors.toList());
    }

    @Transactional
    @Override
    public void uploadUserProfileImage(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
        if (file.getSize() > PROFILE_MAX_BYTES) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }

        if (imageRepository.existsByImageTypeAndRelatedId(ImageType.USER, userId)) {
            throw new BusinessException(ImageErrorCode.USER_IMAGES_ALREADY_EXIST);
        }

        String originalFilename = file.getOriginalFilename();
        String ext = StringUtils.getFilenameExtension(originalFilename);
        if (ext == null) ext = "jpg";

        String uuid = UUID.randomUUID().toString().replace("-", "");

        String key = "users/%d/profile.%s.%s".formatted(userId, uuid, ext);

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .acl(ObjectCannedACL.PUBLIC_READ)
                    .contentType(file.getContentType())
                    .cacheControl("public, max-age=31536000, immutable")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(file.getBytes()));

        } catch (Exception e) {
            log.error("Profile Image Direct Upload Failed userId={}", userId, e);
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }

        saveImageInDB(userId, ImageType.USER, key);
    }


    private void validateProfileInput(Long userId, String requestedKeyOrUrl) {
        if (requestedKeyOrUrl == null || requestedKeyOrUrl.isBlank()) {
            log.warn("[UPI] fail.input_validation reason=null_or_blank userId={}", userId);
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
    }

    private RequestInfo resolveRequestInfo(String requestedKeyOrUrl) {
        boolean isDefaultIncoming = storageClient.isDefaultUrlOrKey(requestedKeyOrUrl);
        String reqKey = toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, requestedKeyOrUrl);
        boolean reqIsStaging = storageClient.isStagingKey(reqKey);

        return new RequestInfo(isDefaultIncoming, reqKey, reqIsStaging);
    }

    private String computeCandidateFinalKey(Long userId, RequestInfo requestInfo) {
        String reqKey = requestInfo.getReqKey();

        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            return buildVersionedProfileKey(userId, ImageType.USER, reqKey);
        }

        return reqKey;
    }

    private void validateImageHeadIfNecessary(RequestInfo requestInfo) {
        if (requestInfo.isDefaultIncoming()) return;
        validateImageHeadOrThrow(requestInfo.getReqKey(), PROFILE_MAX_BYTES);
    }

    private void validateImageHeadOrThrow(String key, long maxBytes) {
        HeadObjectResponse head = storageClient.headObject(key);
        long size = head.contentLength();
        if (size <= 0 || size > maxBytes) throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        String ct = Optional.ofNullable(head.contentType()).orElse("").toLowerCase();
        if (!ct.startsWith("image/")) throw new BusinessException(ImageErrorCode.IMAGE_FILE_UPLOAD_TYPE_ERROR);
    }

    private boolean isNoOp(Optional<Image> existingOpt, String candidateFinalUrl) {
        return existingOpt.isPresent()
               && java.util.Objects.equals(existingOpt.get().getUrl(), candidateFinalUrl);
    }

    private void deleteOldS3ImageIfNecessary(Long userId, Optional<Image> existingOpt) {
        existingOpt.ifPresent(old -> {
            if (!storageClient.isDefaultUrlOrKey(old.getUrl())) {
                try {
                    String oldKey = toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, old.getUrl());
                    s3Client.deleteObject(b -> b.bucket(bucket).key(oldKey));
                } catch (SdkException e) {
                    // 실패해도 치명적이지 않으므로 경고만
                    log.warn("[UPI] old_s3_delete_ignored userId={} url={} err={}", userId, old.getUrl(), e.getMessage());
                }
            }
        });
    }

    private String saveImageInDB(Long relatedId, ImageType imageType, String finalKey) {
        String finalUrl = buildCdnUrlFromKey(cdnBaseUrl, finalKey);

        Image image = Image.of(imageType, relatedId, finalUrl, 0, ImageModerationStatus.CLEAN, null);
        Image savedImage = imageRepository.save(image);
        if (!storageClient.isDefaultUrlOrKey(finalKey)) {
            log.info("[ProfileImage] 비동기 유해성 검사 요청 발행: ID={}, Key={}", savedImage.getId(), finalKey);
            eventPublisher.publishEvent(new ImageModerationEvent(savedImage.getId(), finalKey));
        }

        return finalUrl;
    }

    private String computeChatRoomCandidateFinalKey(Long chatRoomId, RequestInfo requestInfo) {
        String reqKey = requestInfo.getReqKey();

        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            return buildVersionedProfileKey(chatRoomId, ImageType.CHAT_ROOM, reqKey);
        }
        // default거나 이미 영구키면 그대로 사용
        return reqKey;
    }

    private void deleteOldS3ImageIfNecessaryForChatRoom(Long chatRoomId,
                                                        Optional<Image> existingOpt) {
        existingOpt.ifPresent(old -> {
            if (!storageClient.isDefaultUrlOrKey(old.getUrl())) {
                try {
                    String oldKey = toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, old.getUrl());
                    s3Client.deleteObject(b -> b.bucket(bucket).key(oldKey));
                } catch (SdkException e) {
                    log.warn("[CHAT_ROOM {}] old S3 delete ignored: {}", chatRoomId, e.getMessage());
                }
            } else {
                log.info("[CHAT_ROOM {}] old image is default - skip S3 delete", chatRoomId);
            }
        });
    }

    private String buildVersionedProfileKey(Long id, ImageType imageType, String reqKey) {
        String ext = storageClient.extOf(reqKey);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        if (imageType == ImageType.USER) {
            return "users/%d/profile.%s.%s".formatted(id, uuid, ext);
        } else if (imageType == ImageType.CHAT_ROOM) {
            return "chatRoom/%d/chat_profile_%s.%s".formatted(id, uuid, ext);
        }

        return null;
    }

    private String moveStagingProfileIfNecessary(Long userId, RequestInfo requestInfo, String candidateFinalKey) {
        String reqKey = requestInfo.getReqKey();

        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            String dstKey = candidateFinalKey;
            try {
                // 메타데이터는 REPLACE하여 표준화(원치 않으면 COPY로 유지 가능)
                s3Client.copyObject(b -> b
                        .sourceBucket(bucket).sourceKey(reqKey)
                        .destinationBucket(bucket).destinationKey(dstKey)
                        .acl(ObjectCannedACL.PUBLIC_READ)
                        .metadataDirective(MetadataDirective.REPLACE)
                        .cacheControl("public, max-age=31536000, immutable")); // 버전 키이므로 aggressive 캐시 OK
                s3Client.deleteObject(b -> b.bucket(bucket).key(reqKey));
                return dstKey;
            } catch (SdkException e) {
                log.warn("[UPI] staging_move_failed userId={} src={} dst={} err={}", userId, reqKey, dstKey, e.getMessage());
                throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
            }
        }

        return candidateFinalKey;
    }

    private String moveChatRoomStagingIfNecessary(Long chatRoomId,
                                                  RequestInfo requestInfo,
                                                  String candidateFinalKey) {
        String reqKey = requestInfo.getReqKey();

        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            try {
                s3Client.copyObject(b -> b
                        .sourceBucket(bucket).sourceKey(reqKey)
                        .destinationBucket(bucket).destinationKey(candidateFinalKey)
                        .acl(ObjectCannedACL.PUBLIC_READ)
                        .metadataDirective(MetadataDirective.COPY));
                s3Client.deleteObject(b -> b.bucket(bucket).key(reqKey));
                return candidateFinalKey;
            } catch (SdkException e) {
                throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
            }
        }

        // default거나 이미 영구키면 candidateFinalKey 그대로 사용
        return candidateFinalKey;
    }

    private static class RequestInfo {
        private final boolean defaultIncoming;
        private final String reqKey;
        private final boolean staging;

        private RequestInfo(boolean defaultIncoming, String reqKey, boolean staging) {
            this.defaultIncoming = defaultIncoming;
            this.reqKey = reqKey;
            this.staging = staging;
        }

        boolean isDefaultIncoming() {
            return defaultIncoming;
        }

        String getReqKey() {
            return reqKey;
        }

        boolean isStaging() {
            return staging;
        }
    }
}