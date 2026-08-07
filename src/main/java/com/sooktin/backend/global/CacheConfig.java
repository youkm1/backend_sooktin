package com.sooktin.backend.global;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
public class CacheConfig  {

    // 단건 엔티티 캐시 TTL - 원본이 거의 바뀌지 않아 길게 잡는다
    private static final Duration ENTITY_TTL = Duration.ofHours(5);
    // 검색 결과 캐시 TTL - 새 카드/글이 검색에 노출되기까지의 지연을 이 값으로 제한한다
    private static final Duration SEARCH_TTL = Duration.ofMinutes(10);

    // spring.cache.type=none 으로 띄우면 이 빈이 빠지고 Boot 가 NoOpCacheManager 를 넣어준다.
    // 캐시 on/off 를 재기동만으로 바꿀 수 있어야 before/after 비교가 가능하다.
    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 검색 결과는 쓰기마다 allEntries로 비우므로 단건 캐시와 네임스페이스를 분리한다.
        // 같은 네임스페이스를 쓰면 카드 한 장 수정에 검색 캐시 전체가 함께 날아간다.
        return RedisCacheManager.builder(connectionFactory)
                .enableStatistics() // cache.gets{result=hit|miss} 메트릭 노출에 필요
                .withCacheConfiguration("userNote", cacheConfig(ENTITY_TTL))
                .withCacheConfiguration("userNoteSearch", cacheConfig(SEARCH_TTL))
                .withCacheConfiguration("careerCard", cacheConfig(ENTITY_TTL))
                .withCacheConfiguration("careerCardSearch", cacheConfig(SEARCH_TTL))
                .build();
    }

    private RedisCacheConfiguration cacheConfig(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())
                )
                .entryTtl(ttl);
    }
}
