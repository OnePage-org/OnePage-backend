package com.onepage.coupong.service;

import com.onepage.coupong.dto.LeaderboardUpdateDTO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LeaderBoardQueueService implements RedisZSetService {

    private final RedisTemplate<String, Object> redisTemplate;
    // Sink를 사용하여 SSE 전송
    @Getter
    private final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();

    private final String queueKeySeparator = "LEADERBOARDQUEUE:";

    @Autowired
    public LeaderBoardQueueService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean addToZSet(String couponCategory, String userId, double attemptAt) {
        try {
            boolean isAdded = redisTemplate.opsForZSet().add(queueKeySeparator + couponCategory, userId, attemptAt);
            if (isAdded) {
                // 리더보드 정보 가져오기
                Set<Object> topRankSet = getZSet(couponCategory);
                log.info("users for couponCategory {}: {}", couponCategory, topRankSet);

                // DTO 생성
                LeaderboardUpdateDTO updateDTO = new LeaderboardUpdateDTO(couponCategory,
                        new ArrayList<>(topRankSet.stream().map(Object::toString).collect(Collectors.toList())));

                // SSE로 리더보드 업데이트 전송
                updateLeaderboard(updateDTO);

                return true;
            }
        } catch (Exception e) {
            log.error("Error while adding to ZSet: ", e);
        }
        return false;
    }

    @Override
    public Set<Object> getZSet(String couponCategory) {
        return redisTemplate.opsForZSet().range(queueKeySeparator + couponCategory, 0, -1);
    }

    @Override
    public Set<ZSetOperations.TypedTuple<Object>> getTopRankSetWithScore(String couponCategory, int limit) {
        return redisTemplate.opsForZSet().rangeWithScores(queueKeySeparator + couponCategory, 0, limit - 1);
    }

    @Override
    public Set<Object> getTopRankSet(String couponCategory, int limit) {
        return redisTemplate.opsForZSet().range(queueKeySeparator + couponCategory, 0, limit - 1);
    }

    @Override
    public void removeItemFromZSet(String couponCategory, String itemValue) {
        log.info("Removing user from queue: {}", itemValue);
        redisTemplate.opsForZSet().remove(queueKeySeparator + couponCategory, itemValue);
    }

    public void clearLeaderboard(String couponCategory) {
        redisTemplate.opsForZSet().removeRange(queueKeySeparator + couponCategory, 0, -1);
    }

    public void updateLeaderboard(LeaderboardUpdateDTO updateDTO) {
        String winnerList = String.join("\", \"", updateDTO.getWinners());
        String message = "{\"" + updateDTO.getCouponCategory() + "\": [\"" + winnerList + "\"]}";
        log.info("Leaderboard update: {}", message);

        // 구독자 수 확인
        if (sink.currentSubscriberCount() == 0) {
            log.warn("No subscribers for SSE, message will not be emitted.");
        } else {
            sink.tryEmitNext(message);
        }
    }
}