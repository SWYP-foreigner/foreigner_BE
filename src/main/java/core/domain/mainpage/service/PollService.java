package core.domain.mainpage.service;

import core.domain.post.dto.comunity.PostDetailResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PollService {

    public PostDetailResponse getPollDetail(@Positive Long pollId) {


    }
}
