# FaceSwap

Kullanıcının yüklediği videodaki kişilerin yüzlerini, verdiği fotoğraflardaki yüzlerle değiştiren GPU tabanlı video işleme platformu.

- Videodaki kişiler otomatik bulunur; kullanıcı her kişiye ayrı bir yeni yüz atayabilir, atanmayan kişiler olduğu gibi kalır.
- Birden fazla kaynak fotoğraf yüklenebilir; grup fotoğraflarındaki her yüz ayrı ayrı seçilebilir.
- Yüz takibi ve optik akış stabilizasyonu ile kareler arası titreme azaltılır; opsiyonel GFPGAN yüz iyileştirme.
- İlerleme WebSocket ile canlı izlenir; sonuç tarayıcıda oynatılır ve indirilir.

## Mimari

```text
Tarayıcı -> nginx gateway (:8080) -> Spring Boot API -> PostgreSQL | Redis | MinIO
                                          |  RabbitMQ
                                          v
                               GPU Worker (Python + ONNX Runtime CUDA + FFmpeg)
```

Veri akışı:
1. Kullanıcı videoyu ve kaynak fotoğrafları yükler; backend dosya tipini içerikten doğrular, dosyalar MinIO'ya gider.
2. Backend her yükleme için worker'a analiz mesajı bırakır: videodaki farklı kişiler (ArcFace kümeleme) ve fotoğraflardaki yüzler bulunup küçük resimleri çıkarılır.
3. Kullanıcı videodaki kişilere fotoğraflardaki yüzleri atar ve izin beyanıyla işlemi başlatır; backend `jobs` + `job_swaps` kaydı açar ve RabbitMQ'ya mesaj bırakır.
4. GPU worker kareleri FFmpeg ile açar; yüzleri takip eder, her yüzü atanan kişiyle eşleştirir, swap (inswapper) ve iyileştirme (GFPGAN) yapar, videoyu sesiyle yeniden oluşturur.
5. Sonuç MinIO'ya yüklenir; worker event'leri backend'e döner, ilerleme WebSocket ile kullanıcıya iletilir.

Detaylar (kuyruk sözleşmesi, pipeline, titreme azaltma, güvenlik, performans): [ARCHITECTURE.md](ARCHITECTURE.md)

## Klasör Yapısı

```text
faceswap/
├── backend/            # Spring Boot API
│   └── src/main/java/com/faceswap/
│       ├── auth/       # JWT, refresh token, login/register
│       ├── media/      # video/fotoğraf yükleme, analiz sonuçları, medya stream
│       ├── job/        # iş oluşturma, kuyruk mesajları, worker event'leri
│       ├── storage/    # MinIO, Range destekli stream
│       └── config/     # güvenlik, RabbitMQ, WebSocket
│   └── src/main/resources/db/migration/   # Flyway migration'ları (V1-V4)
├── worker/             # GPU worker
│   └── app/            # consumer, processor, analysis, tracking, faces (AI), video (FFmpeg)
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
- **AI Worker:** Python 3.12, FastAPI, ONNX Runtime GPU 1.30 (CUDA 13), InsightFace (SCRFD, ArcFace), inswapper_128 fp16, GFPGAN 1.4, OpenCV optik akış, FFmpeg
- **Altyapı:** Docker Compose, nginx, NVIDIA Container Toolkit

## Hızlı Başlangıç

```bash
cp .env.example .env   # secret'ları değiştirin
docker compose up -d --build
```

Uygulama: http://localhost:8080 (hesap oluşturup giriş yapın). İlk açılışta worker modelleri indirir (~1 GB), `docker compose logs -f worker` ile izlenebilir.

NVIDIA GPU, sürücü, ortam değişkenleri ve worker ayarları için: [INSTALL.md](INSTALL.md)

> ⚠️ `inswapper_128` ve `buffalo_l` modelleri yalnızca ticari olmayan kullanım için lisanslıdır. Ticari yayından önce [ARCHITECTURE.md](ARCHITECTURE.md#model-lisansları-canlı-öncesi-çözülmeli) bölümüne bakın.
