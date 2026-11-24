package com.api.tqt.ws.service;

import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * 대기열 상태 전이를 원자적으로 처리하는 Lua 스크립트 모음.
 * 여러 명령을 따로 보내면 그 사이에 스케줄러나 다른 요청이 끼어들 수 있어서 한 스크립트로 묶는다.
 * 키 접두사를 스크립트 안에서 조합하므로 단일 Redis(Upstash 포함) 기준이다.
 */
final class QueueRedisScripts {

    private QueueRedisScripts() {
    }

    /**
     * 대기열 등록 + 세션 발급.
     * KEYS[1]=사용자→세션 키, KEYS[2]=대기열(ZSET), KEYS[3]=입장 허용 키
     * ARGV[1]=userId, ARGV[2]=새 세션 ID, ARGV[3]=세션 TTL(ms), ARGV[4]=등록 시각(score),
     * ARGV[5]=요청이 들고 온 세션 ID(없으면 ""), ARGV[6]=세션 키 접두사
     * 반환: {"OK", 세션 ID} | {"CONFLICT"} (다른 세션이 이미 이 userId를 쓰는 중)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static final RedisScript<List<String>> REGISTER = (RedisScript) RedisScript.of("""
            local owner = redis.call('GET', KEYS[1])
            if owner and owner ~= ARGV[5] then
              return {'CONFLICT'}
            end
            local sid = owner
            if not owner then
              sid = ARGV[2]
              redis.call('SET', KEYS[1], sid, 'PX', ARGV[3])
              redis.call('SET', ARGV[6] .. sid, ARGV[1], 'PX', ARGV[3])
            end
            if redis.call('EXISTS', KEYS[3]) == 0 then
              redis.call('ZADD', KEYS[2], 'NX', ARGV[4], ARGV[1])
            end
            return {'OK', sid}
            """, List.class);

    /**
     * 세션 ID로 userId를 찾고, 유효하면 세션 TTL을 연장한다.
     * KEYS[1]=세션 키 / ARGV[1]=세션 TTL(ms), ARGV[2]=사용자→세션 키 접두사, ARGV[3]=세션 ID
     * 반환: userId (없거나 이미 다른 세션으로 바뀌었으면 nil)
     */
    static final RedisScript<String> RESOLVE_SESSION = RedisScript.of("""
            local userId = redis.call('GET', KEYS[1])
            if not userId then
              return false
            end
            local userSessionKey = ARGV[2] .. userId
            if redis.call('GET', userSessionKey) ~= ARGV[3] then
              return false
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            redis.call('PEXPIRE', userSessionKey, ARGV[1])
            return userId
            """, String.class);

    /**
     * 현재 상태 조회. 입장 토큰과 순번을 한 번에 읽는다.
     * KEYS[1]=입장 허용 키(값이 토큰), KEYS[2]=대기열 / ARGV[1]=userId
     * 반환: {"ALLOWED", token} | {"WAITING", rank} | {"NOT_FOUND"}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static final RedisScript<List<String>> STATUS = (RedisScript) RedisScript.of("""
            local token = redis.call('GET', KEYS[1])
            if token then
              return {'ALLOWED', token}
            end
            local rank = redis.call('ZRANK', KEYS[2], ARGV[1])
            if rank then
              return {'WAITING', tostring(rank)}
            end
            return {'NOT_FOUND'}
            """, List.class);

    /**
     * 대기열 상위 N명을 꺼내 입장 토큰을 발급한다 (ZPOPMIN + 토큰 저장을 하나로 묶음).
     * 입장 허용 상태와 토큰 모두 TTL을 가지므로 따로 정리할 필요가 없다.
     * KEYS[1]=대기열 / ARGV[1]=토큰 TTL(ms), ARGV[2]=입장 허용 키 접두사, ARGV[3]=토큰 키 접두사,
     * ARGV[4..]=미리 생성한 토큰. 토큰 개수만큼 꺼낸다.
     * 반환: 입장 허용된 인원 수
     */
    static final RedisScript<Long> ADMIT = RedisScript.of("""
            local count = #ARGV - 3
            if count <= 0 then
              return 0
            end
            local popped = redis.call('ZPOPMIN', KEYS[1], count)
            local admitted = 0
            for idx = 1, #popped, 2 do
              local userId = popped[idx]
              admitted = admitted + 1
              local token = ARGV[3 + admitted]
              local allowKey = ARGV[2] .. userId
              local oldToken = redis.call('GET', allowKey)
              if oldToken then
                redis.call('DEL', ARGV[3] .. oldToken)
              end
              redis.call('SET', allowKey, token, 'PX', ARGV[1])
              redis.call('SET', ARGV[3] .. token, userId, 'PX', ARGV[1])
            end
            return admitted
            """, Long.class);

    /**
     * 대기열 이탈. 대기열, 입장 허용 상태, 입장 토큰, 세션을 모두 지운다.
     * KEYS[1]=대기열, KEYS[2]=입장 허용 키, KEYS[3]=사용자→세션 키, KEYS[4]=세션 키
     * ARGV[1]=userId, ARGV[2]=토큰 키 접두사
     * 반환: 대기열에서 제거된 인원 수(0 또는 1)
     */
    static final RedisScript<Long> LEAVE = RedisScript.of("""
            local removed = redis.call('ZREM', KEYS[1], ARGV[1])
            local token = redis.call('GET', KEYS[2])
            if token then
              redis.call('DEL', ARGV[2] .. token)
            end
            redis.call('DEL', KEYS[2], KEYS[3], KEYS[4])
            return removed
            """, Long.class);
}
