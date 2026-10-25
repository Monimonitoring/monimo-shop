package com.monimo.shop.inventory

import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CachingConfigurer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.cache.interceptor.LoggingCacheErrorHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import java.time.Duration

/**
 * Redis 캐시 설정. 캐시는 조회(StockService.find)에만 쓴다 (research ⑧ : b1).
 * 키 : "stock::P-100" (캐시 이름 :: 상품 번호). 값 : StockResponse 를 JSON {"productId":"P-100","qty":999998} 으로. TTL 30초.
 * 에이전트가 Lettuce 호출을 GET · SET · DEL 스팬(db.system=redis)으로 남겨 Service map 에 inventory → Redis 노드가 생긴다.
 */
@Configuration
@EnableCaching
class CacheConfig : CachingConfigurer {

    @Bean
    fun cacheManager(connectionFactory: RedisConnectionFactory): CacheManager =
        RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(
                RedisCacheConfiguration.defaultCacheConfig()
                    // 재고는 자주 바뀌니 짧게. 차감 뒤 evict 를 놓쳐도 30초면 맞춰진다
                    .entryTtl(Duration.ofSeconds(30))
                    // redis-cli 로 읽히게 JSON (기본은 JDK 직렬화라 사람이 못 읽는다). 캐시가 stock 하나뿐이라 타입을 고정한다.
                    // Kotlin data class 는 인자 없는 생성자가 없어 Kotlin 모듈이 든 jacksonObjectMapper 여야 되읽힌다 (리뷰에서 잡힘)
                    .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(stockSerializer()))
                    .disableCachingNullValues(),
            )
            // transactionAware() 는 쓰지 않는다 : 커밋 뒤 DEL 이 실패하면 예외가 커밋 호출자까지 올라와 차감 성공이 500 이 된다.
            // 커밋 뒤 evict 는 StockService.evict 가 직접 등록하고 실패를 로그로 끝낸다
            .build()

    @Bean
    fun stockSerializer(): Jackson2JsonRedisSerializer<StockResponse> =
        Jackson2JsonRedisSerializer(jacksonObjectMapper(), StockResponse::class.java)

    /**
     * Redis 가 죽으면 캐시 오류를 로그만 남기고 DB 로 간다 (research ⑧ : e1).
     * 기본 처리기(SimpleCacheErrorHandler)는 예외를 다시 던져 캐시 장애가 곧 조회 장애가 된다
     */
    override fun errorHandler(): CacheErrorHandler = LoggingCacheErrorHandler()
}
