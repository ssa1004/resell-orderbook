FROM wiremock/wiremock:3.10.0

# Docker Desktop file sharing 제약 회피용 (integration compose 에서 사용) — host bind
# 대신 mappings 를 image 에 굽는다. 일반 dev compose 는 여전히 host bind 사용.
COPY mappings/ /home/wiremock/mappings/
