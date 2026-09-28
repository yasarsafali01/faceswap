from minio import Minio

from .config import settings


class Storage:
    def __init__(self) -> None:
        self.client = Minio(
            settings.minio_endpoint,
            access_key=settings.minio_access_key,
            secret_key=settings.minio_secret_key,
            secure=settings.minio_secure,
        )
        self.bucket = settings.minio_bucket

    def download(self, key: str, path: str) -> None:
        self.client.fget_object(self.bucket, key, path)

    def upload(self, key: str, path: str, content_type: str) -> None:
        self.client.fput_object(self.bucket, key, path, content_type=content_type)
