package com.faceswap.storage;

import com.faceswap.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
public class StorageService {

    private final MinioClient client;
    private final String bucket;

    public StorageService(AppProperties properties) {
        var props = properties.storage();
        this.client = MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
        this.bucket = props.bucket();
    }

    @PostConstruct
    void ensureBucket() throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    public void put(String key, InputStream data, long size, String contentType) throws Exception {
        client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(data, size, -1)
                .contentType(contentType)
                .build());
    }

    public StatObjectResponse stat(String key) throws Exception {
        return client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
    }

    public InputStream get(String key, long offset, long length) throws Exception {
        return client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .offset(offset)
                .length(length)
                .build());
    }
}
