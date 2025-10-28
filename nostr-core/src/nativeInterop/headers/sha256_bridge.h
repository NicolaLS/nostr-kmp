#ifndef SHA256_BRIDGE_H
#define SHA256_BRIDGE_H

#include <CommonCrypto/CommonCrypto.h>

static inline void nostr_sha256_bridge(const unsigned char *data, unsigned long len, unsigned char *out) {
    CC_SHA256(data, (CC_LONG)len, out);
}

#endif /* SHA256_BRIDGE_H */
