-- Admin token bucket — fail-closed 옵션이 있는 admin scope 전용 (ADR-0028).
--
-- 사용자 facing token bucket (token_bucket.lua) 과 같은 알고리즘이지만, 운영자 액션은
-- per-scope (read / write / bulk) 로 RPS 가 다른 정책을 받기 위해 키 prefix 가 분리된
-- 별 스크립트로 둔다.
--
-- KEYS[1] = bucket key (예: "admin:dlq:read:127.0.0.1")
-- ARGV[1] = capacity        (max tokens)
-- ARGV[2] = refill_tokens   (per refill_interval_ms)
-- ARGV[3] = refill_interval_ms
-- ARGV[4] = now_ms
--
-- 반환: { allowed (1/0), remaining_tokens, retry_after_ms }

local key            = KEYS[1]
local capacity       = tonumber(ARGV[1])
local refill_tokens  = tonumber(ARGV[2])
local refill_iv_ms   = tonumber(ARGV[3])
local now_ms         = tonumber(ARGV[4])

local data = redis.call('HMGET', key, 'tokens', 'lastRefillMs')
local tokens = tonumber(data[1])
local last_refill = tonumber(data[2])

if tokens == nil then
    tokens = capacity
    last_refill = now_ms
end

local elapsed = math.max(0, now_ms - last_refill)
local refilled = math.floor((elapsed * refill_tokens) / refill_iv_ms)
if refilled > 0 then
    tokens = math.min(capacity, tokens + refilled)
    last_refill = last_refill + math.floor((refilled * refill_iv_ms) / refill_tokens)
end

local allowed = 0
local retry_after_ms = 0
if tokens > 0 then
    tokens = tokens - 1
    allowed = 1
else
    local ms_per_token = math.ceil(refill_iv_ms / refill_tokens)
    local progress = now_ms - last_refill
    retry_after_ms = math.max(0, ms_per_token - progress)
end

redis.call('HMSET', key, 'tokens', tokens, 'lastRefillMs', last_refill)
local ttl_ms = math.max(refill_iv_ms * 2, math.floor((capacity * refill_iv_ms) / refill_tokens) * 4)
redis.call('PEXPIRE', key, ttl_ms)

return { allowed, tokens, retry_after_ms }
