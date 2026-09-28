# Kurulum

## Gereksinimler

- Docker + Docker Compose v2
- NVIDIA GPU ve **CUDA 13 destekli sürücü (R580 veya üstü)**. Worker imajı CUDA 13.0 kullanır (RTX 50xx Blackwell dahil).
- Linux: [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/)
- Yerel geliştirme için (opsiyonel): JDK 21, Node.js 20+

GPU yoksa worker otomatik olarak CPU'ya düşer (`/health` cevabında `providers` alanına bakın), ama bu durumda çok yavaş çalışır.

## Ortam Değişkenleri

`.env.example` dosyasını `.env` olarak kopyalayıp secret'ları değiştirin:

```env
POSTGRES_DB=faceswap
POSTGRES_USER=faceswap
POSTGRES_PASSWORD=change-me

RABBITMQ_USER=faceswap
RABBITMQ_PASSWORD=change-me

MINIO_ROOT_USER=faceswap
MINIO_ROOT_PASSWORD=change-me-min-8-chars
MINIO_BUCKET=faceswap

# En az 32 byte: openssl rand -base64 48
JWT_SECRET=change-me-to-a-long-random-string-of-at-least-32-bytes
```

Worker ayarları (`compose.yml` içinde `worker.environment` altına eklenebilir):

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

| Servis | Adres | Not |
|---|---|---|
| Web + API gateway | http://localhost:8080 | Dışarıya açık tek port |
| Backend (doğrudan) | http://127.0.0.1:8081 | Geliştirme için |
| PostgreSQL | 127.0.0.1:15432 | |
| RabbitMQ yönetim | http://127.0.0.1:15672 | `.env` kullanıcısı |
| MinIO konsol | http://127.0.0.1:9001 | `.env` kullanıcısı |

## Veritabanı

Flyway migration'ları backend açılırken otomatik çalışır (`backend/src/main/resources/db/migration`). Seed olarak `USER` ve `ADMIN` rolleri eklenir. Ayrı bir seed adımı yok.

## AI Modelleri

Worker ilk açılışta modelleri `models` Docker volume'üne indirir: InsightFace `buffalo_l`, `inswapper_128_fp16.onnx` (GPU) veya `inswapper_128.onnx` (CPU), `gfpgan_1.4.onnx`. Model dosyaları git'e eklenmez.

## Yerel Geliştirme

Altyapı ve worker Docker'da çalışırken web'i hot-reload ile geliştirmek için:

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173, /api -> 127.0.0.1:8081
```

Backend testleri (Maven kurulu değilse Docker ile):

```bash
docker run --rm -v "$PWD/backend:/src" -w /src maven:3.9-eclipse-temurin-21 mvn -B test
```

## Platform Notları

### Windows

- Docker Desktop'ta WSL2 backend'i açık olmalı. GPU sürücüsü Windows tarafına kurulur, WSL2'ye ayrıca kurulmaz.
- GPU'nun container'dan göründüğünü kontrol edin: `docker run --rm --gpus all nvidia/cuda:13.0.2-base-ubuntu24.04 nvidia-smi`
- 5432 portu Windows'ta rezerve olabildiği için PostgreSQL host'ta 15432'den açılır.

### Sorun Giderme

- Worker log'unda `libcublasLt.so.XX: cannot open shared object file` görürseniz `onnxruntime-gpu` sürümünün CUDA major'ı ile `worker/Dockerfile`'daki base imaj uyuşmuyordur.
- Backend yeniden başlatıldıktan sonra 502 alırsanız gateway imajı günceldir mi kontrol edin: nginx backend'i Docker DNS ile her istekte çözer.
