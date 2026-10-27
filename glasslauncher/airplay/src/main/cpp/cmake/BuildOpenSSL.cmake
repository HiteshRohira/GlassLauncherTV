# Use the pinned upstream helper with the official source and required hash.
# The third-party mirror does not carry every OpenSSL security patch.
file(READ "${CMAKE_CURRENT_LIST_DIR}/../third_party/openssl-cmake/cmake/BuildOpenSSL.cmake" _openssl_builder)
string(REPLACE "https://mirror.viaduck.org/openssl/" "https://www.openssl.org/source/"
       _openssl_builder "${_openssl_builder}")
string(REPLACE "ExternalProject_Add(openssl"
       "ExternalProject_Add(openssl\n        TIMEOUT 120 INACTIVITY_TIMEOUT 30"
       _openssl_builder "${_openssl_builder}")
set(_openssl_builder_path "${CMAKE_CURRENT_BINARY_DIR}/BuildOpenSSL-verified.cmake")
file(WRITE "${_openssl_builder_path}" "${_openssl_builder}")
include("${_openssl_builder_path}")
