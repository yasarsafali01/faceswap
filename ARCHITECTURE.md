# Face Swap Video Platformu — Mimari

Kullanıcı bir video + kaynak yüz fotoğrafı yükler; videodaki yüz kaynak yüzle değiştirilir. Hedef: üretim seviyesi, ölçeklenebilir.

## Genel Akış

```text
Mobil (React Native) / Web (React)
        -> API Gateway (nginx)
        -> Spring Boot Backend -> PostgreSQL | Redis | MinIO
        -> RabbitMQ (job queue)
        -> GPU Worker Cluster (Python + FastAPI)
        -> FaceFusion Engine -> FFmpeg
        -> Rendered Video -> MinIO / CDN
```

## Bileşenler

| Katman | Teknoloji | Sorumluluk |
|---|---|---|
| Web | React | Yükleme, job takibi, sonuç izleme |
| Mobil | React Native | Faz 2 |
| Backend | Spring Boot (Java) | Auth (JWT + refresh), roller, video/yüz yükleme, job oluşturma, kuyruk, ödeme, kota, API key, bildirim |
| DB | PostgreSQL | `users, roles, subscriptions, payments, jobs, job_logs, videos, faces, notifications, api_keys, usage_statistics` |
| Cache | Redis | JWT blacklist, session, rate limiting, job progress, kuyruk istatistikleri |
| Storage | MinIO | Orijinal video, kaynak yüz, işlenmiş video, geçici frame'ler, thumbnail |
| Queue | RabbitMQ | Uzun video işlerini API'den ayırır |
| Worker | Python, FastAPI, CUDA, ONNX Runtime, InsightFace, FaceFusion, FFmpeg | Ayrı Docker container, Spring içinde çalışmaz |

Job mesajı:
```json
{ "jobId": "12345", "userId": "50", "videoUrl": "video.mp4", "faceUrl": "face.jpg" }
```

## Worker Pipeline

Her worker (`worker-1..N`) bir RabbitMQ consumer:

1. Video + kaynak yüz MinIO'dan indirilir
2. FFmpeg ile frame'lere ayrılır (`frame_0001.jpg ...`)
3. Face detection (InsightFace): koordinat, landmark, yüz açısı
4. Face alignment: yüz normalize edilir
5. Embedding extraction: kaynak yüzün 512 boyutlu vektörü
6. Face swap (FaceFusion): hedef yüz + kaynak embedding = yeni yüz
7. Enhancement: GFPGAN / CodeFormer (göz, cilt detayı)
8. FFmpeg ile video yeniden oluşturulur
9. Orijinal ses yeni videoya eklenir
10. Sonuç MinIO'ya yüklenir, progress Redis'e yazılır

## API

```http
POST /api/auth/register
POST /api/auth/login
POST /api/videos/upload
POST /api/faces/upload
POST /api/jobs/start
GET  /api/jobs/{id}
GET  /api/jobs/{id}/result
```

Bildirim: WebSocket, push notification, e-posta ("İşleminiz tamamlandı. Video hazır.")

## Deployment

Docker Compose servisleri: `frontend, backend, postgres, redis, rabbitmq, minio, worker-1..3, nginx`

Kubernetes (Faz 3): `frontend-deployment, backend-deployment, worker-deployment, postgres-statefulset, redis-deployment, rabbitmq-deployment, minio-deployment`

## Güvenlik

JWT + refresh token, rate limiting, dosya tipi/boyutu kontrolü, zararlı dosya taraması, audit log, API key yönetimi, zorunlu HTTPS.

### Kötüye kullanım önlemleri (ekleme, onay bekliyor)

Face swap ürünlerinde yasal risk yüksek. Önerilen ekler:
- Yüklemede kullanıcı onayı: kaynak yüzün sahibi olduğunu veya izni olduğunu beyan eder
- Çıktıya görünür watermark ve C2PA/metadata ile "AI generated" etiketi
- NSFW ve reşit olmayan yüz tespiti, bu durumlarda job reddedilir
- Kötüye kullanım bildirimi ve kaldırma (takedown) akışı

## Yol Haritası

- **Faz 1 (MVP):** React web, Spring Boot, PostgreSQL, MinIO, RabbitMQ, FaceFusion worker. Tek GPU.
- **Faz 2:** Mobil uygulama, ödeme, kota, bildirim sistemi.
- **Faz 3:** Çoklu GPU worker, Kubernetes, CDN, global dağıtım.

## Donanım

- Geliştirme: RTX 4070 Super, 32 GB RAM, NVMe SSD
- İlk yayın: RTX 4090, 64 GB RAM
- Ölçekleme: Çoklu RTX 4090, Kubernetes GPU cluster
