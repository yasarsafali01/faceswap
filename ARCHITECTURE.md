# Face Swap Video Platformu — Mimari

Kullanıcı bir video + kaynak yüz fotoğrafı yükler; videodaki yüzler kaynak yüzle değiştirilir. Hedef: üretim seviyesi, ölçeklenebilir.

## Genel Akış

```text
React Web (nginx gateway :####)
   /api, /ws -> Spring Boot Backend -> PostgreSQL | Redis | MinIO
                      | video.analyze (upload sonrası)      | job.requested (kullanıcı başlatınca)
                      v                                      v
                 RabbitMQ: faceswap.analyze              faceswap.jobs      (ayrı kanallar, her biri prefetch=1)
                      v                                      v
         GPU Worker (Python, FastAPI, ONNX Runtime CUDA)
           analiz: örnek kareler -> ArcFace -> kişi kümeleri -> thumbnail + faces.json
           render: FFmpeg decode -> detect -> (hedef kişi eşleşmesi) -> swap/enhance -> FFmpeg encode+ses
                      | video.analyzed                       | job.event (STARTED/PROGRESS/COMPLETED/FAILED)
                      v                                      v
                 Backend -> Postgres (durum) + Redis (ilerleme) + WebSocket (/user/queue/jobs)
```

Kullanıcı akışı: video yükle -> worker birkaç saniyede videodaki kişileri bulur. Paralelde bir veya birden fazla kaynak fotoğraf yüklenir (çoklu seçim); her fotoğraf da analiz edilir ve içindeki her yüz ayrı kırpılır (grup fotoğrafı = birden fazla seçenek). Kullanıcı videodaki her kişiye bu yüzlerden birini atar (her fotoğraf yüzü bir kez; yüz atanmayan kişi değişmez) ya da "Hepsine aynı yüz" der -> izin -> başlat.

## Bileşenler

| Katman | Teknoloji | Sorumluluk |
|---|---|---|
| Gateway + Web | nginx + React (Vite, TS) | SPA, `/api` ve `/ws` reverse proxy, 250 MB upload limiti |
| Backend | Spring Boot 3.5, Java 21 | Auth, yükleme, job oluşturma, event işleme, medya stream, WebSocket |
| DB | PostgreSQL 16 + Flyway | `users, roles, user_roles, videos, faces, jobs, job_logs` (V1), `video_faces` + analiz/hedef kolonları (V2), `job_swaps` kişi -> yüz eşleşmeleri (V3; `jobs.face_id/target_face_index` ilk eşleşmeyi tutar), `face_detections` + fotoğraf analiz kolonları + `job_swaps.source_face_index` (V4) |
| Cache | Redis 7 | refresh token allow-list, access token blacklist, rate limit, job ilerlemesi |
| Storage | MinIO | `videos/`, `faces/`, `results/`, `thumbnails/` (key: `{tip}/{userId}/{uuid}.{ext}`), `video-faces/{userId}/{videoId}/` (`faces.json` + `{index}.jpg`), `face-crops/{userId}/{faceId}/{index}.jpg` |
| Queue | RabbitMQ 4 | `faceswap` direct exchange; `faceswap.jobs` ve `faceswap.analyze` (DLX -> `*.dead`), `faceswap.job-events`, `faceswap.analysis-results` |
| Worker | Python 3.12, ORT 1.30 (CUDA 13), InsightFace, FFmpeg | Tek job/worker, GPU'da inference |

Faz 2 tabloları (`subscriptions, payments, notifications, api_keys, usage_statistics`) henüz yok.

## Kuyruk Sözleşmesi

Kaynak: `backend/.../job/JobMessages.java` ve `worker/app/messages.py`. Birini değiştiren diğerini de değiştirir.

```json
// video.analyze  (backend -> worker, upload commit'inden sonra)
{ "videoId": "uuid", "userId": 1, "videoKey": "videos/1/x.mp4", "facesPrefix": "video-faces/1/<videoId>/" }

// video.analyzed  (worker -> backend). Embedding'ler DB'ye değil facesPrefix + "faces.json"a yazılır.
{ "videoId": "uuid", "status": "READY|FAILED", "error": null, "faces": [{ "index": 0, "occurrences": 12 }] }

// image.analyze  (backend -> worker, analyze kuyruğunu paylaşır) / image.analyzed (-> faceswap.image-analysis-results)
{ "faceId": "uuid", "userId": 1, "imageKey": "faces/1/x.jpg", "cropsPrefix": "face-crops/1/<faceId>/" }
{ "faceId": "uuid", "status": "READY|FAILED", "error": null, "faces": [0, 1, 2] }   // büyükten küçüğe

// job.requested  (backend -> worker). Obje key'lerini backend belirler.
// faceKey: tek yüzlü fotoğrafta fotoğrafın kendisi, grup fotoğrafında seçilen yüzün kırpımı.
// Her swap: faceKey yüzü targetFaceIndex kişisine. Tek swap ve targetFaceIndex null ise tüm yüzler o yüzü alır.
{ "jobId": "uuid", "userId": 1, "videoKey": "videos/1/x.mp4",
  "resultKey": "results/1/<jobId>.mp4", "thumbnailKey": "thumbnails/1/<jobId>.jpg", "enhance": true,
  "facesKey": "video-faces/1/<videoId>/faces.json",
  "swaps": [{ "faceKey": "faces/1/a.png", "targetFaceIndex": 0 }, { "faceKey": "faces/1/b.jpg", "targetFaceIndex": 3 }] }

// job.event  (worker -> backend)
{ "jobId": "uuid", "type": "STARTED|PROGRESS|COMPLETED|FAILED", "progress": 42, "error": null, "workerId": "worker-1" }
```

Eşleşme kuralları (`JobService.resolveTargets`): analiz sürerken başlatma 409; analiz başarısızsa sadece tek yüz + herkes; tek swap ve hedef yoksa (tek kişilik videoda o kişi, değilse herkes); birden fazla swap'ta her birinin hedefi olmalı, hedefler analizde bulunmalı ve tekrar etmemeli; en fazla 12 swap.

- Mesaj DB commit'inden **sonra** yayınlanır (worker'ın olmayan satırı raporlamasını önler).
- Worker job bitene kadar ack atmaz; worker çökerse mesaj tekrar dağıtılır. Backend terminal durumdaki job'a gelen event'leri yok sayar.
- PROGRESS sadece Redis'e yazılır (Postgres'e yük bindirmez), her event WebSocket ile kullanıcıya itilir.
- RabbitMQ `consumer_timeout` 3 saat (`deploy/rabbitmq/20-faceswap.conf`).

## Worker Pipeline

### Video analizi (kişi bulma)

1. Video saniyede 2 kare (en fazla 120 kare) örneklenir; her yüz için ArcFace embedding çıkarılır (det_score >= 0.6, >= 32 px).
2. Embedding'ler cosine benzerliğiyle açgözlü kümelenir (`CLUSTER_THRESHOLD` 0.4, küme merkezine göre). 10+ örnek karede tek sefer görülen yüzler (arka plan, yanlış tespit) atılır; en sık görülen 12 kişi tutulur.
3. Her kişi için en büyük/net yüz kırpılıp thumbnail olur; merkez embedding'ler `faces.json`a yazılır.

### Fotoğraf analizi (kaynak yüzler)

Fotoğraftaki yüzler bulunur (sıkı kırpılmışsa kenar boşluğuyla tekrar denenir), büyükten küçüğe sıralanır, det_score >= 0.6, en fazla 10. Her yüz için yüz boyutunun ~2.2 katı kare kırpım (render'da detector'ın yüzü tekrar bulabilmesi için bağlam; seçilen yüz kırpımdaki en büyük yüz olur), küçükse 512 px'e büyütülür. Birden fazla yüzlü fotoğrafta `sourceFaceIndex` zorunlu (400).

### Render

1. Video + kaynak yüz MinIO'dan indirilir, `ffprobe` ile süre/fps/rotasyon okunur (limit: 180 sn, 1080p, 60 fps).
2. Her swap'ın kaynak yüzü: InsightFace `buffalo_l` (detection + ArcFace embedding + yaş). Sıkı kırpılmış fotoğrafta kenar boşluğu eklenip tekrar denenir. Embedding inswapper latent'ine bir kez projekte edilir. Hedef kişiler varsa `faces.json`dan merkez embedding'leri (K x 512) yüklenir.
3. FFmpeg kareleri pipe ile raw BGR olarak verir (diske frame yazılmaz, reader ayrı thread'de).
4. **Sıralı aşama (ana thread, kare sırasıyla):** SCRFD detection -> yüz takibi (`worker/app/tracking.py`):
   - Yüzler IoU ile kareler arası eşlenir; eşleşmeyen yüz yeni track (sahne kesmesinde durum taşınmaz).
   - Landmark'lar **optik akışla stabilize** edilir: yüz bölgesinin kareler arası hareketi (LK flow + benzerlik dönüşümü) ölçülür, önceki tahmin bu hareketle taşınıp yeni tespitle %70/%30 harmanlanır. Düz alçak geçiren filtre (One Euro) sarsıntılı kamerada geride kaldığı için denendi ve reddedildi.
   - Hedef kişiler varsa her track en benzer hedefe atanır; kimlik histerezisi: `MATCH_THRESHOLD` (0.3) ile atanır, `KEEP_THRESHOLD` (0.15) altına düşene kadar ataması korunur; aynı hedefe iki track düşerse en benzeri seçilir; ArcFace 5 karede bir yeniden çalışır. Her yüz kendi hedefinin kaynak yüzüyle swap edilir.
   - 1–3 karelik tespit kayıpları yüz hareketle taşınarak doldurulur (orijinal yüz "yanıp sönmez").
5. **Paralel aşama (3 thread):** ArcFace hizalama -> **inswapper_128 fp16** -> bölgesel yumuşak maske ile paste-back -> opsiyonel **GFPGAN 1.4** inference (FFHQ hizalama).
6. **Sıralı yazma:** GFPGAN'ın eklediği detay katmanı track başına önceki kareyle harmanlanır (`ENHANCER_TEMPORAL` 0.5), %80 blend ile yapıştırılır. Görünür filigran varsayılan kapalı (`WATERMARK_TEXT`).
7. FFmpeg tek geçişte H.264 (CRF 18) encode eder, orijinal sesi (AAC) geri ekler, kaynak metadata'yı siler ve `comment=AI-generated content (FaceSwap)` etiketini yazar, `+faststart`.
8. Sonuç ve thumbnail MinIO'ya yüklenir.

Titreme ölçümü (sarsıntılı sentetik klip, optik akışla hareket telafili kareler arası hata, swap'ın orijinale eklediği fazlalık): eski kare-bağımsız pipeline 0.97 -> akış stabilizasyonu 0.65 -> + GFPGAN detay yumuşatması 0.54 (−%44). İyileştirme kapalıyken 0.15; kalan titremenin çoğu GFPGAN'ın her karede dokuyu yeniden üretmesinden geliyor.

Ölçülen hız (RTX 5060, 6 yüzlü 1280x886 video): iyileştirme açık 2.7 fps, kapalı 11.3 fps; 6 kişiden 1'i seçilip iyileştirme ve takip açıkken 12.5 fps, 2 kişiye 2 farklı yüz 7.3 fps, 3 kişiye 4.9 fps. Fotoğraf analizi ~1 sn. Analiz 6 sn'lik videoda 2.2 sn. Yüz başına GPU süresi yaklaşık 16 ms (swap) ve 47 ms (GFPGAN), tek yüzlü videoda iyileştirmeyle yaklaşık 14 fps.

## API

```http
POST /api/auth/register | login | refresh | logout     GET /api/auth/me
POST /api/videos/upload   GET /api/videos                (multipart "file")
GET  /api/videos/{id}     analysisStatus (PENDING|READY|FAILED) + faces [{index, url, occurrences}]
POST /api/faces/upload    GET /api/faces
GET  /api/faces/{id}      analysisStatus + detections [{index, url}] (fotoğraftaki yüzler)
POST /api/jobs/start      {videoId, consent: true, enhance, swaps: [{faceId, sourceFaceIndex, targetFaceIndex}]}  (kısa yol: faceId + targetFaceIndex)
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
- Servis portları sadece `127.0.0.1`'e açık; dışarıya sadece gateway (####).
- Eksik (canlı öncesi): HTTPS terminasyonu, zararlı dosya taraması, genel audit log, API key yönetimi.

### Kötüye kullanım önlemleri

- **Uygulandı:** zorunlu izin beyanı (`consent_at` saklanır), çıktı dosyasında makinece okunabilir "AI-generated" metadata etiketi (AB Yapay Zeka Yasası Md. 50 işaretleme yükümlülüğü için; görünür filigran ürün kararıyla kapalı, `WATERMARK_TEXT` ile açılabilir), kaynak yüzde ve değiştirilen yüzlerde (5 sn'de bir) yaş tahmini ile 18 altı reddi (`MIN_FACE_AGE`).
- **Eksik:** NSFW tespiti, C2PA manifest'i, kötüye kullanım bildirimi ve takedown akışı.

### Model lisansları (canlı öncesi çözülmeli)

`inswapper_128` ve `buffalo_l` (InsightFace) **yalnızca ticari olmayan araştırma** lisanslıdır. Ticari yayından önce InsightFace'ten ticari lisans alınmalı ya da ticari kullanıma izin veren modellere geçilmelidir. GFPGAN Apache-2.0'dır.

## Deployment

Yapılandırmanın tek kaynağı kökteki `.env`'dir (şablon: `.env.example`): portlar, servis adresleri, kullanıcılar, secret'lar, limitler ve worker ayarları. `compose.yml` değerleri `${VAR}` ile alır, backend ve worker `env_file: .env` ile okur, nginx config'i şablondan (`BACKEND_PORT`, `MAX_UPLOAD_MB`) üretilir, Vite dev sunucusu `.env`'i `loadEnv` ile okur, UI limitleri `GET /api/config`'ten gelir. Kodda sabit port/kullanıcı/limit yoktur.

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
