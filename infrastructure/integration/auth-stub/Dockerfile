FROM nginx:1.27-alpine

# Docker Desktop 의 host bind mount 가 /Applications/* 에 막혀 있는 환경에서도 동작하도록
# 정적 파일을 image 안에 굽는다. 컨테이너 내부에서 nginx 가 8080 으로 응답.
COPY nginx.conf /etc/nginx/nginx.conf
COPY jwks.json /usr/share/nginx/html/jwks.json
COPY openid-configuration.json /usr/share/nginx/html/openid-configuration.json
