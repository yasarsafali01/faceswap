# Kurulum

## Gereksinimler

- Docker + Docker Compose
- NVIDIA GPU (geliştirme: RTX 4070 Super veya üstü)
- NVIDIA sürücüsü + [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/)
- Yerel geliştirme için: JDK 21, Node.js 20+, Python 3.11

## Ortam Değişkenleri

Proje kökünde `.env` oluşturun:

```env
# PostgreSQL
POSTGRES_DB=faceswap
POSTGRES_USER=faceswap
POSTGRES_PASSWORD=change-me

# Redis
REDIS_HOST=redis
REDIS_PORT=6379

# RabbitMQ
RABBITMQ_DEFAULT_USER=faceswap
RABBITMQ_DEFAULT_PASS=change-me
RABBITMQ_JOB_QUEUE=faceswap.jobs

# MinIO
MINIO_ROOT_USER=faceswap
MINIO_ROOT_PASSWORD=change-me-min-8-chars
MINIO_BUCKET=faceswap

# Backend
JWT_SECRET=change-me-long-random-string
JWT_ACCESS_TTL_MINUTES=15
JWT_REFRESH_TTL_DAYS=30
```

## Platform Notları

### Windows

- Docker Desktop'ta WSL2 backend'i açık olmalı.
- GPU erişimi için WSL2 içinde NVIDIA sürücüsü kurulu olmalı. Kontrol: `docker run --rm --gpus all nvidia/cuda:12.4.0-base-ubuntu22.04 nvidia-smi`

### AI Modelleri

FaceFusion, InsightFace ve GFPGAN/CodeFormer modelleri `models/` klasörüne indirilir. Bu klasör git'e eklenmez.

## Veritabanı

Migration'lar backend ayağa kalkarken otomatik çalışır.
