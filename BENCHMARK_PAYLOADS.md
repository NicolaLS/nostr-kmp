# Benchmark Payloads and Real-World Scenarios

This document captures typical operations and payload sizes for Nostr apps (social clients, DMs, and Nostr Wallet Connect) so benchmark scenarios reflect real usage.

## Social media clients (Nostr)

### Common operations
- Event creation + signing (kind 1 notes, reactions, reposts)
- Event verification on feed load
- Event ID hashing (SHA-256)
- Random bytes for key material or auxiliary randomness

### Typical content sizes
- Tiny note: 16–64 bytes ("gm", short replies)
- Short note: 128–400 bytes (tweet-length)
- Medium note: 512–2,000 bytes (normal post, a few paragraphs)
- Long note: 4,000–8,000 bytes (long-form posts)
- Article (NIP-23): 8–32 KB (markdown or HTML)

### Typical batch sizes
- Feed page: 20–50 events
- Profile load: 40–80 events
- Search results: 20–50 events
- Timeline catch-up: 100–500 events (in bursts)

## Direct messages (NIP-04 and NIP-44)

### Common operations
- ECDH (shared secret derivation)
- Random nonce/IV generation
- Symmetric encryption (AES-CBC for NIP-04, ChaCha20 for NIP-44)
- MAC/HMAC (NIP-44)
- Base64 encode/decode

### Typical message sizes (plaintext)
- Short DM: 64–160 bytes
- Medium DM: 256–800 bytes
- Long DM: 1–4 KB

### Typical batch sizes
- Conversation burst: 10–50 messages in quick succession
- Session activity: 50–200 messages over a few minutes (power users)

## Nostr Wallet Connect (NIP-47)

### Common operations
- NIP-04 encryption/decryption of request/response
- Event signing + verification
- Base64 encode/decode

### Typical payload sizes (JSON)
- Request: 200–400 bytes (pay_invoice, get_balance)
- Response: 300–700 bytes (includes preimage/transaction info)

### Typical batch sizes
- Wallet interaction: 1–5 workflows per user action
- Session usage: 5–20 workflows per session

## Mapping to benchmark scenarios

- SHA256-32/280/1024/4096B
  - Event ID hashing for tiny/short/medium/long notes
- RandomBytes-32/256B
  - Key material, IVs, nonces
- Base64-Encode/Decode-32/512/4096B
  - NIP-04/44 ciphertext and IV/nonce encoding
- Chacha20-128/512/2048B
  - NIP-44 encryption for short/medium/long DMs
- EventCreate-32/280/1024B
  - Signing notes and small posts
- EventVerify-32/280/1024B
  - Verifying feed events
- FeedLoad-20events, FeedLoad-50events (and batched page variants)
  - Feed page and profile load verification
- NIP04-Encrypt/Decrypt-128/512/2048B
  - Legacy DM sizes
- NIP44-Encrypt/Decrypt-128/512/2048B
  - Modern DM sizes
- NIP04/NIP44 RoundTrip (batched)
  - End-to-end DM bursts
- NWC-Workflow (batched)
  - NIP-47 request/response cycles

## Notes
- Sizes above are for plaintext content (before encryption). Ciphertext grows (IV/nonce + MAC + base64 overhead).
- Real-world apps vary; these are representative ranges for client-side benchmarking.
- For meaningful comparisons, use the same device, JVM, and batch settings across JNA/JNI runs.
