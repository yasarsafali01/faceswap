# FaceSwap

Kullanıcının yüklediği videodaki yüzleri, verdiği kaynak yüz fotoğrafıyla değiştiren GPU tabanlı video işleme platformu.

## Mimari

```text
Tarayıcı -> nginx gateway (:8080) -> Spring Boot API -> PostgreSQL | Redis | MinIO
                                          |  RabbitMQ
                                          v
                               GPU Worker (Python + ONNX Runtime CUDA + FFmpeg)
```

Veri akışı:
1. Kullanıcı video ve yüz fotoğrafını yükler; backend tipini doğrular, dosyalar MinIO'ya gider.
2. Kullanıcı izin beyanıyla işlemi başlatır; backend `jobs` kaydı açar ve RabbitMQ'ya mesaj bırakır.
3. GPU worker işi alır: kareleri FFmpeg ile açar, her karede yüz tespiti, swap (inswapper) ve iyileştirme (GFPGAN) yapar, filigran ekler, videoyu sesiyle birlikte yeniden oluşturur.
4. Sonuç MinIO'ya yüklenir; worker event'leri backend'e döner, ilerleme WebSocket ile anlık kullanıcıya iletilir.

Detaylar (kuyruk sözleşmesi, pipeline, güvenlik, performans): [ARCHITECTURE.md](ARCHITECTURE.md)

## Klasör Yapısı

```text
faceswap/
├── backend/            # Spring Boot API (auth, media, job, storage, config)
│   └── src/main/resources/db/migration/   # Flyway migration'ları
├── worker/             # GPU worker
│   └── app/            # consumer, processor, faces (AI), video (FFmpeg), storage
├── frontend/           # React web app + nginx gateway config
├── deploy/rabbitmq/    # RabbitMQ ek ayarları
├── compose.yml         # Tüm stack
├── ARCHITECTURE.md
└── INSTALL.md
```

## Teknolojiler

- **Web:** React 19, TypeScript, Vite, STOMP (WebSocket)
- **Backend:** Java 21, Spring Boot 3.5 (Web, Security, Data JPA, AMQP, WebSocket), Flyway, JJWT, MinIO SDK, Apache Tika
- **Veri:** PostgreSQL 16, Redis 7, MinIO, RabbitMQ 4
- **AI Worker:** Python 3.12, FastAPI, ONNX Runtime GPU 1.30 (CUDA 13), InsightFace (SCRFD, ArcFace), inswapper_128 fp16, GFPGAN 1.4, FFmpeg
- **Altyapı:** Docker Compose, nginx, NVIDIA Container Toolkit

## Hızlı Başlangıç

```bash
cp .env.example .env   # secret'ları değiştirin
docker compose up -d --build
```

Uygulama: http://localhost:8080. İlk açılışta worker modelleri indirir (~1 GB), `docker compose logs -f worker` ile izlenebilir.

NVIDIA GPU, sürücü ve Docker GPU ayarları için: [INSTALL.md](INSTALL.md)

> ⚠️ `inswapper_128` ve `buffalo_l` modelleri yalnızca ticari olmayan kullanım için lisanslıdır. Ticari yayından önce [ARCHITECTURE.md](ARCHITECTURE.md#model-lisansları-canlı-öncesi-çözülmeli) bölümüne bakın.
