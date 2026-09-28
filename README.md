# FaceSwap

Kullanıcının yüklediği videodaki yüzü, verdiği kaynak yüz fotoğrafıyla değiştiren video işleme platformu.

## Mimari

```text
React Web / React Native
        -> nginx
        -> Spring Boot API -> PostgreSQL | Redis | MinIO
        -> RabbitMQ
        -> GPU Worker (Python + FastAPI + FaceFusion + FFmpeg)
        -> MinIO (sonuç videosu)
```

Veri akışı:
1. Kullanıcı video ve yüz fotoğrafını yükler, dosyalar MinIO'ya gider.
2. Backend `jobs` tablosuna kayıt açar ve RabbitMQ'ya iş mesajı bırakır.
3. GPU worker işi alır: frame ayırma, yüz tespiti, swap, iyileştirme, video birleştirme, ses ekleme.
4. Sonuç MinIO'ya yüklenir, ilerleme Redis üzerinden izlenir, kullanıcıya bildirim gider.

Detaylı mimari için [ARCHITECTURE.md](ARCHITECTURE.md) dosyasına bakın.

## Klasör Yapısı

```text
faceswap/
├── frontend/          # React web
├── backend/           # Spring Boot API
├── worker/            # Python FastAPI + FaceFusion GPU worker
├── deploy/            # docker-compose, nginx, k8s manifestleri
├── ARCHITECTURE.md
├── INSTALL.md
└── README.md
```

## Teknolojiler

- **Frontend:** React, React Native (Faz 2)
- **Backend:** Java, Spring Boot, JWT
- **Veri:** PostgreSQL, Redis, MinIO
- **Kuyruk:** RabbitMQ
- **AI:** Python, FastAPI, CUDA, ONNX Runtime, InsightFace, FaceFusion, GFPGAN/CodeFormer, FFmpeg
- **Altyapı:** Docker, nginx, Kubernetes (Faz 3)

## Hızlı Başlangıç

```bash
docker compose -f deploy/docker-compose.yml up -d
```

Kurulum gereksinimleri, ortam değişkenleri ve GPU ayarları için [INSTALL.md](INSTALL.md) dosyasına bakın.
