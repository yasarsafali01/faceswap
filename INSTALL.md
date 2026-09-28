# Kurulum

## Gereksinimler

- Docker + Docker Compose v2
- NVIDIA GPU ve **CUDA 13 destekli sürücü (R580 veya üstü)**. Worker imajı CUDA 13.0 kullanır (RTX 50xx Blackwell dahil).
- Linux: [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/)
- Yerel geliştirme için (opsiyonel): JDK 21, Node.js 20+

GPU yoksa worker otomatik olarak CPU'ya düşer (`/health` cevabında `providers` alanına bakın), ama bu durumda çok yavaş çalışır.

## Yapılandırma (tek kaynak: `.env`)

Tüm ayarlar proje kökündeki **`.env`** dosyasından okunur; kodda veya `compose.yml`'de değer değiştirmeye gerek yoktur. Şablon ve her değişkenin açıklaması: [`.env.example`](.env.example).

```bash
cp .env.example .env      # sonra şifreleri ve gerekirse portları değiştirin
docker compose up -d      # değişiklikten sonra ilgili servisleri yeniden oluşturur
```

| Bölüm | Değişkenler |
|---|---|
| Dışarı açılan portlar | `WEB_PORT`, `BIND_ADDRESS`, `BACKEND_HOST_PORT`, `POSTGRES_HOST_PORT`, `RABBITMQ_UI_HOST_PORT`, `MINIO_CONSOLE_HOST_PORT`, `DEV_WEB_PORT` |
| Container içi portlar | `BACKEND_PORT`, `WORKER_HEALTH_PORT` |
| PostgreSQL | `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` |
| Redis / RabbitMQ | `REDIS_HOST`, `REDIS_PORT`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` |
| Nesne depolama | `MINIO_ENDPOINT` (harici S3 için `https://...`), `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `MINIO_BUCKET` |
| Güvenlik | `JWT_SECRET` (en az 32 byte), `JWT_ACCESS_TTL`, `JWT_REFRESH_TTL`, `MEDIA_LINK_TTL` |
| Limitler | `MAX_VIDEO_MB`, `MAX_IMAGE_MB`, `MAX_UPLOAD_MB`, `MAX_DURATION_SECONDS`, `MAX_ACTIVE_JOBS_PER_USER`, `RATE_LIMIT_*` |
| GPU worker | aşağıdaki tablo |

Arayüzde gösterilen limitler (dosya boyutu, süre) de backend'den (`GET /api/config`) okunur; UI'da sabit değer yoktur. Değer değiştirdikten sonra `docker compose up -d` yeterlidir, yeniden build gerekmez.

Worker ayarları:

| Değişken | Varsayılan | Açıklama |
|---|---|---|
| `MAX_DURATION_SECONDS` | 180 | Kabul edilen en uzun video |
| `MAX_HEIGHT` | 1080 | Daha büyük videolar bu yüksekliğe küçültülür |
| `FRAME_WORKERS` | 3 | Paralel işlenen kare sayısı |
| `VIDEO_ENCODER` | libx264 | `h264_nvenc` ile GPU encode |
| `VIDEO_CRF` | 18 | Çıktı kalitesi (düşük = daha iyi) |
| `ENHANCER_BLEND` | 0.8 | GFPGAN karışım oranı |
| `MIN_FACE_AGE` | 18 | Bu yaşın altında tahmin edilen yüzler reddedilir |
| `WATERMARK_TEXT` | (boş) | Doldurulursa sağ alta görünür filigran eklenir |
| `AI_METADATA_TAG` | AI-generated content (FaceSwap) | Çıktının metadata `comment` etiketi; boş bırakılırsa yazılmaz |
| `CLUSTER_THRESHOLD` | 0.4 | Analizde iki yüzü aynı kişi saymak için ArcFace benzerliği |
| `MATCH_THRESHOLD` | 0.3 | Render'da bir yüzü seçilen kişi saymak için benzerlik (düşük = profil açıları da yakalanır) |
| `KEEP_THRESHOLD` | 0.15 | Seçilen kişi olarak tanınmış bir yüzün, bu benzerliğe düşene kadar seçili kalması |
| `TEMPORAL_SMOOTHING` | true | Yüz takibi, landmark stabilizasyonu ve GFPGAN detay yumuşatması (titreme azaltma) |
| `FLOW_WEIGHT` | 0.7 | Landmark stabilizasyon gücü (0 = ham tespit, 1'e yakın = daha yumuşak) |
| `ENHANCER_TEMPORAL` | 0.5 | GFPGAN detayında güncel karenin payı (1 = yumuşatma yok; düşük = daha az titreme, hızlı mimikte gölge riski) |
| `ANALYSIS_SAMPLES_PER_SECOND` | 2 | Kişi analizinde saniye başına örnek kare |
| `ANALYSIS_MAX_SAMPLES` | 120 | Kişi analizinde en fazla örnek kare |

## Portlar

Tüm portlar `.env`'den gelir; aşağıda hangi değişkenin hangi servise ait olduğu var.

| Servis | Adres | `.env` değişkeni | Not |
|---|---|---|---|
| Web + API gateway | http://localhost:#### | `WEB_PORT` | Dışarıya açık tek port |
| Backend (doğrudan) | http://`BIND_ADDRESS`:#### | `BACKEND_HOST_PORT` | Geliştirme için |
| PostgreSQL | `BIND_ADDRESS`:#### | `POSTGRES_HOST_PORT` | |
| RabbitMQ yönetim | http://`BIND_ADDRESS`:#### | `RABBITMQ_UI_HOST_PORT` | `.env` kullanıcısı |
| MinIO konsol | http://`BIND_ADDRESS`:#### | `MINIO_CONSOLE_HOST_PORT` | `.env` kullanıcısı |
| Web geliştirme sunucusu | http://localhost:#### | `DEV_WEB_PORT` | `npm run dev` |

## Veritabanı

Flyway migration'ları backend açılırken otomatik çalışır (`backend/src/main/resources/db/migration`). Seed olarak `USER` ve `ADMIN` rolleri eklenir. Ayrı bir seed adımı yok.

## AI Modelleri

Worker ilk açılışta modelleri `models` Docker volume'üne indirir: InsightFace `buffalo_l`, `inswapper_128_fp16.onnx` (GPU) veya `inswapper_128.onnx` (CPU), `gfpgan_1.4.onnx`. Model dosyaları git'e eklenmez.

## Yerel Geliştirme

Altyapı ve worker Docker'da çalışırken web'i hot-reload ile geliştirmek için:

```bash
cd frontend && npm install && npm run dev   # http://localhost:####, /api -> 127.0.0.1:####
```

Backend testleri (Maven kurulu değilse Docker ile):

```bash
docker run --rm -v "$PWD/backend:/src" -w /src maven:3.9-eclipse-temurin-21 mvn -B test
```

## Platform Notları

### Windows

- Docker Desktop'ta WSL2 backend'i açık olmalı. GPU sürücüsü Windows tarafına kurulur, WSL2'ye ayrıca kurulmaz.
- GPU'nun container'dan göründüğünü kontrol edin: `docker run --rm --gpus all nvidia/cuda:13.0.2-base-ubuntu24.04 nvidia-smi`
- PostgreSQL'in varsayılan portu Windows'ta rezerve olabildiği için host'ta farklı bir porttan açılır (bkz. `compose.yml`).
- Docker imajları (worker ~10 GB) ve build cache hızla büyür. C: diski dolarsa Docker çöker; Docker Desktop > Settings > Resources > Advanced > **Disk image location** ile disk dosyasını daha büyük bir sürücüye taşıyın. Silinen imajlar yer açmaz, Windows tarafındaki VHDX kendiliğinden küçülmez.

### Sorun Giderme

- Worker log'unda `libcublasLt.so.XX: cannot open shared object file` görürseniz `onnxruntime-gpu` sürümünün CUDA major'ı ile `worker/Dockerfile`'daki base imaj uyuşmuyordur.
- Backend yeniden başlatıldıktan sonra 502 alırsanız gateway imajı günceldir mi kontrol edin: nginx backend'i Docker DNS ile her istekte çözer.
