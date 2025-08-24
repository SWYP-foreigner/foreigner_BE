package core.domain.user.controller;


import core.domain.user.dto.UserUpdateDTO;
import core.domain.user.entity.CustomUserDetails;
import core.domain.user.service.ContentBasedRecommender;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "친구 추천 ", description = "친구 추천 API")
@RestController
@RequestMapping("/api/v1/commend")
@RequiredArgsConstructor
@Slf4j
public class FriednCommendController {
    private final ContentBasedRecommender recommender;

    /**
     @AuthenticationPrincipal 사용 (CustomUserDetails에 getId() 있다고 가정)
     *
     * @param
     */
    @GetMapping("/content-based")
    @Operation(
            summary = "친구 추천 하기 기능 한 번 에 20명씩 Pagination 으로 가져오기"
    )
    public ResponseEntity<List<UserUpdateDTO>> recommend(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(defaultValue = "20") int limit
    ) {
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<UserUpdateDTO> list = recommender.recommendForUser(me.getId(), limit);
        return ResponseEntity.ok(list);
    }




}
