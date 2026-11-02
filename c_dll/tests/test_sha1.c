/* Exercise the private SHA-1 used by the handshake. The RFC 6455 key + GUID
 * crosses the 56-byte SHA-1 padding boundary (24 + 36 = 60 bytes). */
#include <stdio.h>
#include <string.h>

#include "../src/aero_ws.c"

int main(void) {
    static const char key[] = "dGhlIHNhbXBsZSBub25jZQ==";
    static const char guid[] = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    static const char accept[] = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
    unsigned char input[sizeof(key) - 1 + sizeof(guid) - 1];
    unsigned char digest[20];
    char encoded[32];

    memcpy(input, key, sizeof(key) - 1);
    memcpy(input + sizeof(key) - 1, guid, sizeof(guid) - 1);
    if (sizeof(input) != 60) return 1;
    sha1_hash(input, (int)sizeof(input), digest);
    base64_encode(digest, (int)sizeof(digest), encoded);
    if (strcmp(encoded, accept) != 0) {
        fprintf(stderr, "RFC 6455 accept mismatch: %s (expected %s)\n", encoded, accept);
        return 1;
    }
    return 0;
}
