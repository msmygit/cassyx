package io.cassyx.bulk.impl;

import io.cassyx.bulk.api.BulkException;
import io.cassyx.bulk.api.Sink;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.BlockingOutputStreamAsyncRequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * S3 sink (plan section 5.2).
 *
 * <p>Uses {@link AsyncRequestBody#forBlockingOutputStream} so the caller gets a plain
 * {@link OutputStream} while the SDK performs a multipart upload underneath. That matters: the
 * ordinary {@code putObject} path needs the content length up front, which for an unload means
 * buffering the whole export first.
 *
 * <p>Options: {@code region}, {@code endpoint} (for MinIO / LocalStack), {@code accessKeyId} +
 * {@code secretAccessKey} (otherwise the default credential chain), {@code contentType},
 * {@code pathStyleAccess}.
 */
public final class S3Sink implements Sink {

  @Override
  public String scheme() {
    return "s3";
  }

  @Override
  public OutputStream open(String target, String partName, Map<String, String> options)
      throws IOException {
    S3Location location = S3Location.parse(target).withKeySuffix(partName);
    S3AsyncClient client = client(options);

    BlockingOutputStreamAsyncRequestBody body =
        AsyncRequestBody.forBlockingOutputStream(null /* unknown content length */);

    PutObjectRequest.Builder request =
        PutObjectRequest.builder().bucket(location.bucket()).key(location.key());
    if (options.get("contentType") != null) {
      request.contentType(options.get("contentType"));
    }
    CompletableFuture<PutObjectResponse> upload = client.putObject(request.build(), body);

    return new S3UploadStream(body.outputStream(), upload, client);
  }

  private static S3AsyncClient client(Map<String, String> options) {
    // Multipart is mandatory here, not an optimisation: an unload has no content length up front,
    // and a single-part PUT would have to buffer the whole export to discover one.
    S3AsyncClientBuilder builder = S3AsyncClient.builder().multipartEnabled(true);
    String region = options.get("region");
    if (region != null && !region.isBlank()) {
      builder.region(Region.of(region));
    }
    String endpoint = options.get("endpoint");
    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    if (Boolean.parseBoolean(options.getOrDefault("pathStyleAccess", "false"))) {
      builder.forcePathStyle(true);
    }
    String accessKeyId = options.get("accessKeyId");
    String secretAccessKey = options.get("secretAccessKey");
    if (accessKeyId != null && secretAccessKey != null) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
    } else {
      builder.credentialsProvider(DefaultCredentialsProvider.create());
    }
    return builder.build();
  }

  /**
   * {@code s3://bucket/prefix} split into its parts. A record so the parsing - the bit that
   * silently writes to the wrong place when it is wrong - is unit-testable with no AWS account.
   */
  public record S3Location(String bucket, String key) {

    public S3Location {
      Objects.requireNonNull(bucket, "bucket");
      key = key == null ? "" : key;
    }

    public static S3Location parse(String uri) {
      if (uri == null || !uri.startsWith("s3://")) {
        throw new BulkException("S3 sink target must start with s3:// but was '" + uri + "'");
      }
      String rest = uri.substring("s3://".length());
      int slash = rest.indexOf('/');
      if (slash < 0) {
        if (rest.isBlank()) {
          throw new BulkException("S3 sink target has no bucket: '" + uri + "'");
        }
        return new S3Location(rest, "");
      }
      String bucket = rest.substring(0, slash);
      if (bucket.isBlank()) {
        throw new BulkException("S3 sink target has no bucket: '" + uri + "'");
      }
      return new S3Location(bucket, rest.substring(slash + 1));
    }

    public S3Location withKeySuffix(String partName) {
      if (partName == null || partName.isBlank()) {
        return this;
      }
      if (key.isEmpty()) {
        return new S3Location(bucket, partName);
      }
      return new S3Location(bucket, key.endsWith("/") ? key + partName : key + "/" + partName);
    }
  }

  /** Closing the stream completes the multipart upload and surfaces its failure. */
  private static final class S3UploadStream extends OutputStream {

    private final OutputStream delegate;
    private final CompletableFuture<PutObjectResponse> upload;
    private final S3AsyncClient client;
    private boolean closed;

    S3UploadStream(
        OutputStream delegate,
        CompletableFuture<PutObjectResponse> upload,
        S3AsyncClient client) {
      this.delegate = delegate;
      this.upload = upload;
      this.client = client;
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      try {
        delegate.close();
        upload.join();
      } catch (RuntimeException e) {
        throw new BulkException("S3 upload failed", e);
      } finally {
        client.close();
      }
    }
  }
}
