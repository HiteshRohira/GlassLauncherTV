#!/bin/zsh
# Host-side tests for Glass's UxPlay patches: malformed input, fuzzing and
# connection stress run here, on the Mac, never against the TV.
# Builds the pinned UxPlay submodule with patches/UxPlay applied, using
# AddressSanitizer and UndefinedBehaviorSanitizer.
set -euo pipefail
HERE=${0:A:h}
CPP=$HERE/../../main/cpp
WORK=$(mktemp -d /tmp/glass-native-test.XXXXXX)
trap 'rm -rf $WORK' EXIT
git -C $CPP/third_party/UxPlay archive HEAD lib | tar -x -C $WORK
( cd $WORK && git init -q && for p in $CPP/patches/UxPlay/*.patch; do git apply --unidiff-zero $p; done )
L=$WORK/lib
CFLAGS=(-std=gnu11 -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=undefined
        -w -I$L -I$L/llhttp -DHTTPD_IDLE_TIMEOUT=2)
LL=($L/llhttp/llhttp.c $L/llhttp/api.c $L/llhttp/http.c)
HOST_SSL=${HOST_SSL:-/opt/homebrew/opt/openssl@3}
cc $CFLAGS -I$HOST_SSL/include -L$HOST_SSL/lib -Wl,-rpath,$HOST_SSL/lib \
   -o $WORK/test_pairing_state $HERE/test_pairing_state.c $L/crypto.c $L/utils.c $L/compat.c -lcrypto
cc $CFLAGS -o $WORK/test_media_validation $HERE/test_media_validation.c
cc $CFLAGS -o $WORK/test_hls $HERE/test_hls.c
cc $CFLAGS -o $WORK/test_httpd $HERE/test_httpd.c $L/httpd.c $L/http_request.c $L/http_response.c \
   $L/logger.c $L/netutils.c $L/utils.c $L/compat.c $LL
cc $CFLAGS -o $WORK/test_raop_buffer_nodata $HERE/test_raop_buffer_nodata.c
c++ -std=c++17 -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=undefined \
   -I$CPP -o $WORK/test_timeline_flush $HERE/test_timeline_flush.cpp
$WORK/test_pairing_state $WORK/pairing.pem
$WORK/test_media_validation
$WORK/test_hls
$WORK/test_httpd
$WORK/test_raop_buffer_nodata
$WORK/test_timeline_flush
echo "native host tests: all passed"
