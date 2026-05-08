-- Token bucket — atomic refill + try-consume on Redis (ADR-0020).
--
-- KEYS[1] = bucket key (예: "rl:USER123:POST /api/v1/listings")
-- ARGV[1] = capacity        (max tokens, integer)
-- ARGV[2] = refill_tokens   (per refill_interval_ms, integer)
-- ARGV[3] = refill_interval_ms (integer)
-- ARGV[4] = now_ms           (서버 시계, ms epoch — Redis 의 TIME 으로 대체 가능하지만
--                             테스트의 결정성을 위해 외부 주입)
--
-- 반환: { allowed (1 또는 0), remaining_tokens, retry_after_ms }
--
-- 구조: HSET bucket "tokens" T "lastRefillMs" L
--   읽기 → refill 계산 → 한 토큰 차감 (가능 시) → 다시 쓰기. 모두 Lua 안에서 한 번에 →
--   원자적 (race-free).
--
-- TTL: bucket 이 한참 안 쓰이면 가비지로 남으므로 (capacity / refill_rate) * 4 만큼만 유지.
--   다음 사용 시 새로 만들면 어차피 capacity 로 시작 — 정확성 영향 없음.

local key            = KEYS[1]
local capacity       = tonumber(ARGV[1])
local refill_tokens  = tonumber(ARGV[2])
local refill_iv_ms   = tonumber(ARGV[3])
local now_ms         = tonumber(ARGV[4])

local data = redis.call('HMGET', key, 'tokens', 'lastRefillMs')
local tokens = tonumber(data[1])
local last_refill = tonumber(data[2])

if tokens == nil then
    -- 첫 요청 → 통이 가득 찬 상태로 시작
    tokens = capacity
    last_refill = now_ms
end

-- 경과 ms 동안 채워질 토큰 수 계산. 정수 단위로만 누적 (소수 토큰 허용 시 round-trip 마다
-- floor 되어 버려져 평균 rate 가 낮아짐). 따라서 부분 진행은 last_refill 갱신을 통해 다음 호출에
-- 이월: refilled 토큰만큼 ms 를 진행.
local elapsed = math.max(0, now_ms - last_refill)
local refilled = math.floor((elapsed * refill_tokens) / refill_iv_ms)
if refilled > 0 then
    tokens = math.min(capacity, tokens + refilled)
    -- 정수 토큰만큼만 시간을 진행 — 소수점 부분은 다음 호출 때 합산되도록 last_refill 보존.
    last_refill = last_refill + math.floor((refilled * refill_iv_ms) / refill_tokens)
end

local allowed = 0
local retry_after_ms = 0
if tokens > 0 then
    tokens = tokens - 1
    allowed = 1
else
    -- 다음 토큰 1개가 채워지기까지 남은 ms.
    local ms_per_token = math.ceil(refill_iv_ms / refill_tokens)
    local progress = now_ms - last_refill           -- 현재 누적 진행 (last_refill 이후 경과)
    retry_after_ms = math.max(0, ms_per_token - progress)
end

redis.call('HMSET', key, 'tokens', tokens, 'lastRefillMs', last_refill)
-- TTL: 한 번 가득 채워지는 시간 * 4 — 안 쓰이면 자동 청소.
local ttl_ms = math.max(refill_iv_ms * 2, math.floor((capacity * refill_iv_ms) / refill_tokens) * 4)
redis.call('PEXPIRE', key, ttl_ms)

return { allowed, tokens, retry_after_ms }
