# Face Swap Video Platformu — Mimari

Kullanıcı bir video + kaynak yüz fotoğrafı yükler; videodaki yüzler kaynak yüzle değiştirilir. Hedef: üretim seviyesi, ölçeklenebilir.

## Genel Akış

```text
React Web (nginx gateway :8080)
   /api, /ws -> Spring Boot Backend -> PostgreSQL | Redis | MinIO
                      | publish job.requested
                      v
                 RabbitMQ (faceswap exchange)
                      | faceswap.jobs (prefetch=1)
                      v
         GPU Worker (Python, FastAPI, ONNX Runtime CUDA)
           MinIO'dan indir -> FFmpeg decode -> detect/swap/enhance -> FFmpeg encode+ses -> MinIO'ya yükle
                      | job.event (STARTED/PROGRESS/COMPLETED/FAILED)
                      v
                 Backend -> Postgres (durum) + Redis (ilerleme) + WebSocket (/user/queue/jobs)
```

## Bileşenler

| Katman | Teknoloji | Sorumluluk |
|---|---|---|
| Gateway + Web | nginx + React (Vite, TS) | SPA, `/api` ve `/ws` reverse proxy, 250 MB upload limiti |
| Backend | Spring Boot 3.5, Java 21 | Auth, yükleme, job oluşturma, event işleme, medya stream, WebSocket |
| DB | PostgreSQL 16 + Flyway | `users, roles, user_roles, videos, faces, jobs, job_logs` (V1) |
| Cache | Redis 7 | refresh token allow-list, access token blacklist, rate limit, job ilerlemesi |
| Storage | MinIO | `videos/`, `faces/`, `results/`, `thumbnails/` (key: `{tip}/{userId}/{uuid}.{ext}`) |
| Queue | RabbitMQ 4 | `faceswap` direct exchange, `faceswap.jobs` (DLX -> `faceswap.jobs.dead`), `faceswap.job-events` |
| Worker | Python 3.12, ORT 1.30 (CUDA 13), InsightFace, FFmpeg | Tek job/worker, GPU'da inference |

Faz 2 tabloları (`subscriptions, payments, notifications, api_keys, usage_statistics`) henüz yok.

## Kuyruk Sözleşmesi

Kaynak: `backend/.../job/JobMessages.java` ve `worker/app/messages.py`. Birini değiştiren diğerini de değiştirir.

```json
// job.requested  (backend -> worker). Obje key'lerini backend belirler.
{ "jobId": "uuid", "userId": 1, "videoKey": "videos/1/x.mp4", "faceKey": "faces/1/y.png",
  "resultKey": "results/1/<jobId>.mp4", "thumbnailKey": "thumbnails/1/<jobId>.jpg", "enhance": true }

// job.event  (worker -> backend)
{ "jobId": "uuid", "type": "STARTED|PROGRESS|COMPLETED|FAILED", "progress": 42, "error": null, "workerId": "worker-1" }
```

- Mesaj DB commit'inden **sonra** yayınlanır (worker'ın olmayan satırı raporlamasını önler).
- Worker job bitene kadar ack atmaz; worker çökerse mesaj tekrar dağıtılır. Backend terminal durumdaki job'a gelen event'leri yok sayar.
- PROGRESS sadece Redis'e yazılır (Postgres'e yük bindirmez), her event WebSocket ile kullanıcıya itilir.
- RabbitMQ `consumer_timeout` 3 saat (`deploy/rabbitmq/20-faceswap.conf`).

## Worker Pipeline

1. Video + kaynak yüz MinIO'dan indirilir, `ffprobe` ile süre/fps/rotasyon okunur (limit: 180 sn, 1080p, 60 fps).
2. Kaynak yüz: InsightFace `buffalo_l` (detection + ArcFace embedding + yaş). Sıkı kırpılmış fotoğrafta kenar boşluğu eklenip tekrar denenir. Embedding inswapper latent'ine bir kez projekte edilir.
3. FFmpeg kareleri pipe ile raw BGR olarak verir (diske frame yazılmaz, reader ayrı thread'de).
4. Her kare (3 kare paralel, sıra korunarak): SCRFD detection -> ArcFace hizalama -> **inswapper_128 fp16** -> bölgesel yumuşak maske ile paste-back -> opsiyonel **GFPGAN 1.4** (FFHQ hizalama, %80 blend) -> "AI GENERATED" filigranı.
5. FFmpeg tek geçişte H.264 (CRF 18) encode eder ve orijinal sesi (AAC) geri ekler, `+faststart`.
6. Sonuç ve thumbnail MinIO'ya yüklenir.

Ölçülen hız (RTX 5060, 6 yüzlü 1280x886 video): iyileştirme açık 2.7 fps, kapalı 11.3 fps. Yüz başına GPU süresi yaklaşık 16 ms (swap) ve 47 ms (GFPGAN), tek yüzlü videoda iyileştirmeyle yaklaşık 14 fps.

## API

```http
POST /api/auth/register | login | refresh | logout     GET /api/auth/me
POST /api/videos/upload   GET /api/videos                (multipart "file")
POST /api/faces/upload    GET /api/faces
POST /api/jobs/start      {videoId, faceId, consent: true, enhance}
GET  /api/jobs            GET /api/jobs/{id}             GET /api/jobs/{id}/result (Range destekli)
GET  /api/media/{token}   imzalı medya linki (<video>/<img> için, Range destekli)
WS   /ws (STOMP)          CONNECT header: Authorization: Bearer <token>; SUBSCRIBE /user/queue/jobs
```

## Güvenlik

- JWT: access 15 dk, refresh 30 gün; refresh tek kullanımlık (rotasyon, Redis allow-list), logout access token'ı blacklist'e alır.
- Medya linkleri 2+ saat geçerli imzalı token; saatlik dilimde deterministik (cache dostu).
- Rate limit (Redis, sabit pencere): auth 20/dk/IP, upload 60/saat, job 30/saat/kullanıcı; kullanıcı başına 3 aktif job.
- Dosya tipi magic byte ile tespit edilir (Tika + ISO-BMFF brand kontrolü), istemcinin bildirdiği tipe güvenilmez. Video 200 MB, görsel 10 MB.
- BCrypt, kullanıcı enumerasyonuna karşı sabit süreli login, WebSocket'te sadece `/user/**` abonelikleri.
- Servis portları sadece `127.0.0.1`'e açık; dışarıya sadece gateway (8080).
- Eksik (canlı öncesi): HTTPS terminasyonu, zararlı dosya taraması, genel audit log, API key yönetimi.

### Kötüye kullanım önlemleri

- **Uygulandı:** zorunlu izin beyanı (`consent_at` saklanır), çıktıda görünür "AI GENERATED" filigranı, kaynak yüzde ve videoda (5 sn'de bir) yaş tahmini ile 18 altı reddi (`MIN_FACE_AGE`).
- **Eksik:** NSFW tespiti, C2PA/metadata etiketi, kötüye kullanım bildirimi ve takedown akışı.

### Model lisansları (canlı öncesi çözülmeli)

`inswapper_128` ve `buffalo_l` (InsightFace) **yalnızca ticari olmayan araştırma** lisanslıdır. Ticari yayından önce InsightFace'ten ticari lisans alınmalı ya da ticari kullanıma izin veren modellere geçilmelidir. GFPGAN Apache-2.0'dır.

## Deployment

`compose.yml` servisleri: `postgres, redis, rabbitmq, minio, backend, worker, frontend`. Worker ölçekleme: `docker compose up -d --scale worker=N` (her worker `prefetch=1`, aynı GPU'yu paylaşırlar; asıl ölçek birden fazla GPU/makine ile).

Kubernetes (Faz 3): `frontend-deployment, backend-deployment, worker-deployment (GPU node pool), postgres-statefulset, redis-deployment, rabbitmq-deployment, minio-deployment`.

## Yol Haritası

- **Faz 1 (MVP): tamamlandı.** Web, backend, worker, kuyruk, depolama, WebSocket, temel güvenlik.
- **Faz 2:** Mobil (React Native), ödeme, kota (`usage_statistics`), e-posta/push bildirim, NSFW filtresi, admin paneli.
- **Faz 3:** Çoklu GPU worker, Kubernetes, CDN, global dağıtım, GFPGAN fp16/TensorRT.

## Donanım

- Geliştirme (mevcut): RTX 5060 8 GB (Blackwell), NVIDIA sürücüsü CUDA 13 destekli (R580+)
- İlk yayın: RTX 4090, 64 GB RAM
- Ölçekleme: Çoklu RTX 4090, Kubernetes GPU cluster
