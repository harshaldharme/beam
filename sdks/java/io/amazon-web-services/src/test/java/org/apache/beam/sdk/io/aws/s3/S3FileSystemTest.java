/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.aws.s3;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.beam.sdk.io.aws.s3.S3TestUtils.buildMockedS3FileSystem;
import static org.apache.beam.sdk.io.aws.s3.S3TestUtils.getSSECustomerKeyMd5;
import static org.apache.beam.sdk.io.aws.s3.S3TestUtils.s3Options;
import static org.apache.beam.sdk.io.aws.s3.S3TestUtils.s3OptionsWithSSECustomerKey;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.CopyObjectResult;
import com.amazonaws.services.s3.model.CopyPartRequest;
import com.amazonaws.services.s3.model.CopyPartResult;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.GetObjectMetadataRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.collect.ImmutableList;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.beam.sdk.io.aws.options.S3Options;
import org.apache.beam.sdk.io.fs.MatchResult;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatcher;

/** Test case for {@link S3FileSystem}. */
@RunWith(JUnit4.class)
public class S3FileSystemTest {

  @Test
  public void testGlobTranslation() {
    assertEquals("foo", S3FileSystem.wildcardToRegexp("foo"));
    assertEquals("fo[^/]*o", S3FileSystem.wildcardToRegexp("fo*o"));
    assertEquals("f[^/]*o\\.[^/]", S3FileSystem.wildcardToRegexp("f*o.?"));
    assertEquals("foo-[0-9][^/]*", S3FileSystem.wildcardToRegexp("foo-[0-9]*"));
    assertEquals("foo-[0-9].*", S3FileSystem.wildcardToRegexp("foo-[0-9]**"));
    assertEquals(".*foo", S3FileSystem.wildcardToRegexp("**/*foo"));
    assertEquals(".*foo", S3FileSystem.wildcardToRegexp("**foo"));
    assertEquals("foo/[^/]*", S3FileSystem.wildcardToRegexp("foo/*"));
    assertEquals("foo[^/]*", S3FileSystem.wildcardToRegexp("foo*"));
    assertEquals("foo/[^/]*/[^/]*/[^/]*", S3FileSystem.wildcardToRegexp("foo/*/*/*"));
    assertEquals("foo/[^/]*/.*", S3FileSystem.wildcardToRegexp("foo/*/**"));
    assertEquals("foo.*baz", S3FileSystem.wildcardToRegexp("foo**baz"));
  }

  @Test
  public void testGetScheme() {
    S3FileSystem s3FileSystem = new S3FileSystem(s3Options());
    assertEquals("s3", s3FileSystem.getScheme());
  }

  @Test
  public void testCopy() throws IOException {
    testCopy(s3Options());
    testCopy(s3OptionsWithSSECustomerKey());
  }

  private GetObjectMetadataRequest createObjectMetadataRequest(
      S3ResourceId path, S3Options options) {
    GetObjectMetadataRequest getObjectMetadataRequest =
        new GetObjectMetadataRequest(path.getBucket(), path.getKey());
    getObjectMetadataRequest.setSSECustomerKey(options.getSSECustomerKey());
    return getObjectMetadataRequest;
  }

  private void assertGetObjectMetadata(
      S3FileSystem s3FileSystem,
      GetObjectMetadataRequest request,
      S3Options options,
      ObjectMetadata objectMetadata) {
    when(s3FileSystem
            .getAmazonS3Client()
            .getObjectMetadata(argThat(new GetObjectMetadataRequestMatcher(request))))
        .thenReturn(objectMetadata);
    assertEquals(
        getSSECustomerKeyMd5(options),
        s3FileSystem.getAmazonS3Client().getObjectMetadata(request).getSSECustomerKeyMd5());
  }

  private void testCopy(S3Options options) throws IOException {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(s3Options());

    S3ResourceId sourcePath = S3ResourceId.fromUri("s3://bucket/from");
    S3ResourceId destinationPath = S3ResourceId.fromUri("s3://bucket/to");

    ObjectMetadata objectMetadata = new ObjectMetadata();
    objectMetadata.setContentLength(0);
    if (getSSECustomerKeyMd5(options) != null) {
      objectMetadata.setSSECustomerKeyMd5(getSSECustomerKeyMd5(options));
    }
    assertGetObjectMetadata(
        s3FileSystem, createObjectMetadataRequest(sourcePath, options), options, objectMetadata);

    s3FileSystem.copy(sourcePath, destinationPath);

    verify(s3FileSystem.getAmazonS3Client(), times(1))
        .copyObject(argThat(notNullValue(CopyObjectRequest.class)));

    // we simulate a big object >= 5GB so it takes the multiPart path
    objectMetadata.setContentLength(5_368_709_120L);
    assertGetObjectMetadata(
        s3FileSystem, createObjectMetadataRequest(sourcePath, options), options, objectMetadata);

    try {
      s3FileSystem.copy(sourcePath, destinationPath);
    } catch (NullPointerException e) {
      // ignore failing unmocked path, this is covered by testMultipartCopy test
    }

    verify(s3FileSystem.getAmazonS3Client(), never())
        .copyObject(argThat(nullValue(CopyObjectRequest.class)));
  }

  @Test
  public void testAtomicCopy() {
    testAtomicCopy(s3Options());
    testAtomicCopy(s3OptionsWithSSECustomerKey());
  }

  private void testAtomicCopy(S3Options options) {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(options);

    S3ResourceId sourcePath = S3ResourceId.fromUri("s3://bucket/from");
    S3ResourceId destinationPath = S3ResourceId.fromUri("s3://bucket/to");

    CopyObjectResult copyObjectResult = new CopyObjectResult();
    if (getSSECustomerKeyMd5(options) != null) {
      copyObjectResult.setSSECustomerKeyMd5(getSSECustomerKeyMd5(options));
    }
    CopyObjectRequest copyObjectRequest =
        new CopyObjectRequest(
            sourcePath.getBucket(),
            sourcePath.getKey(),
            destinationPath.getBucket(),
            destinationPath.getKey());
    copyObjectRequest.setSourceSSECustomerKey(options.getSSECustomerKey());
    copyObjectRequest.setDestinationSSECustomerKey(options.getSSECustomerKey());
    when(s3FileSystem
            .getAmazonS3Client()
            .copyObject(argThat(notNullValue(CopyObjectRequest.class))))
        .thenReturn(copyObjectResult);
    assertEquals(
        getSSECustomerKeyMd5(options),
        s3FileSystem.getAmazonS3Client().copyObject(copyObjectRequest).getSSECustomerKeyMd5());

    ObjectMetadata sourceS3ObjectMetadata = new ObjectMetadata();
    s3FileSystem.atomicCopy(sourcePath, destinationPath, sourceS3ObjectMetadata);

    verify(s3FileSystem.getAmazonS3Client(), times(2))
        .copyObject(argThat(notNullValue(CopyObjectRequest.class)));
  }

  @Test
  public void testMultipartCopy() {
    testMultipartCopy(s3Options());
    testMultipartCopy(s3OptionsWithSSECustomerKey());
  }

  private void testMultipartCopy(S3Options options) {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(options);

    S3ResourceId sourcePath = S3ResourceId.fromUri("s3://bucket/from");
    S3ResourceId destinationPath = S3ResourceId.fromUri("s3://bucket/to");

    InitiateMultipartUploadResult initiateMultipartUploadResult =
        new InitiateMultipartUploadResult();
    initiateMultipartUploadResult.setUploadId("upload-id");
    if (getSSECustomerKeyMd5(options) != null) {
      initiateMultipartUploadResult.setSSECustomerKeyMd5(getSSECustomerKeyMd5(options));
    }
    when(s3FileSystem
            .getAmazonS3Client()
            .initiateMultipartUpload(argThat(notNullValue(InitiateMultipartUploadRequest.class))))
        .thenReturn(initiateMultipartUploadResult);
    assertEquals(
        getSSECustomerKeyMd5(options),
        s3FileSystem
            .getAmazonS3Client()
            .initiateMultipartUpload(
                new InitiateMultipartUploadRequest(
                    destinationPath.getBucket(), destinationPath.getKey()))
            .getSSECustomerKeyMd5());

    ObjectMetadata sourceObjectMetadata = new ObjectMetadata();
    sourceObjectMetadata.setContentLength(
        (long) (options.getS3UploadBufferSizeBytes() * 1.5));
    sourceObjectMetadata.setContentEncoding("read-seek-efficient");
    if (getSSECustomerKeyMd5(options) != null) {
      sourceObjectMetadata.setSSECustomerKeyMd5(getSSECustomerKeyMd5(options));
    }
    assertGetObjectMetadata(
        s3FileSystem,
        createObjectMetadataRequest(sourcePath, options),
        options,
        sourceObjectMetadata);

    CopyPartResult copyPartResult1 = new CopyPartResult();
    copyPartResult1.setETag("etag-1");
    CopyPartResult copyPartResult2 = new CopyPartResult();
    copyPartResult1.setETag("etag-2");
    if (getSSECustomerKeyMd5(options) != null) {
      copyPartResult1.setSSECustomerKeyMd5(getSSECustomerKeyMd5(options));
      copyPartResult2.setSSECustomerKeyMd5(getSSECustomerKeyMd5(options));
    }
    CopyPartRequest copyPartRequest = new CopyPartRequest();
    copyPartRequest.setSourceSSECustomerKey(options.getSSECustomerKey());
    when(s3FileSystem.getAmazonS3Client().copyPart(argThat(notNullValue(CopyPartRequest.class))))
        .thenReturn(copyPartResult1)
        .thenReturn(copyPartResult2);
    assertEquals(
        getSSECustomerKeyMd5(options),
        s3FileSystem.getAmazonS3Client().copyPart(copyPartRequest).getSSECustomerKeyMd5());

    s3FileSystem.multipartCopy(sourcePath, destinationPath, sourceObjectMetadata);

    verify(s3FileSystem.getAmazonS3Client(), times(1))
        .completeMultipartUpload(argThat(notNullValue(CompleteMultipartUploadRequest.class)));
  }

  @Test
  public void deleteThousandsOfObjectsInMultipleBuckets() throws IOException {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(s3Options());

    List<String> buckets = ImmutableList.of("bucket1", "bucket2");
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 2500; i++) {
      keys.add(String.format("key-%d", i));
    }
    List<S3ResourceId> paths = new ArrayList<>();
    for (String bucket : buckets) {
      for (String key : keys) {
        paths.add(S3ResourceId.fromComponents(bucket, key));
      }
    }

    s3FileSystem.delete(paths);

    // Should require 6 calls to delete 2500 objects in each of 2 buckets.
    verify(s3FileSystem.getAmazonS3Client(), times(6))
        .deleteObjects(argThat(notNullValue(DeleteObjectsRequest.class)));
  }

  @Test
  public void matchNonGlob() {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(s3Options());

    S3ResourceId path = S3ResourceId.fromUri("s3://testbucket/testdirectory/filethatexists");
    ObjectMetadata s3ObjectMetadata = new ObjectMetadata();
    s3ObjectMetadata.setContentLength(100);
    s3ObjectMetadata.setContentEncoding("read-seek-efficient");
    when(s3FileSystem
            .getAmazonS3Client()
            .getObjectMetadata(
                argThat(
                    new GetObjectMetadataRequestMatcher(
                        new GetObjectMetadataRequest(path.getBucket(), path.getKey())))))
        .thenReturn(s3ObjectMetadata);

    MatchResult result = s3FileSystem.matchNonGlobPath(path);
    assertThat(
        result,
        MatchResultMatcher.create(
            ImmutableList.of(
                MatchResult.Metadata.builder()
                    .setSizeBytes(100)
                    .setResourceId(path)
                    .setIsReadSeekEfficient(true)
                    .build())));
  }

  @Test
  public void matchNonGlobNotReadSeekEfficient() {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(s3Options());

    S3ResourceId path = S3ResourceId.fromUri("s3://testbucket/testdirectory/filethatexists");
    ObjectMetadata s3ObjectMetadata = new ObjectMetadata();
    s3ObjectMetadata.setContentLength(100);
    s3ObjectMetadata.setContentEncoding("gzip");
    when(s3FileSystem
            .getAmazonS3Client()
            .getObjectMetadata(
                argThat(
                    new GetObjectMetadataRequestMatcher(
                        new GetObjectMetadataRequest(path.getBucket(), path.getKey())))))
        .thenReturn(s3ObjectMetadata);

    MatchResult result = s3FileSystem.matchNonGlobPath(path);
    assertThat(
        result,
        MatchResultMatcher.create(
            ImmutableList.of(
                MatchResult.Metadata.builder()
                    .setSizeBytes(100)
                    .setResourceId(path)
                    .setIsReadSeekEfficient(false)
                    .build())));
  }

  @Test
  public void matchNonGlobNullContentEncoding() {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(s3Options());

    S3ResourceId path = S3ResourceId.fromUri("s3://testbucket/testdirectory/filethatexists");
    ObjectMetadata s3ObjectMetadata = new ObjectMetadata();
    s3ObjectMetadata.setContentLength(100);
    s3ObjectMetadata.setContentEncoding(null);
    when(s3FileSystem
            .getAmazonS3Client()
            .getObjectMetadata(
                argThat(
                    new GetObjectMetadataRequestMatcher(
                        new GetObjectMetadataRequest(path.getBucket(), path.getKey())))))
        .thenReturn(s3ObjectMetadata);

    MatchResult result = s3FileSystem.matchNonGlobPath(path);
    assertThat(
        result,
        MatchResultMatcher.create(
            ImmutableList.of(
                MatchResult.Metadata.builder()
                    .setSizeBytes(100)
                    .setResourceId(path)
                    .setIsReadSeekEfficient(true)
                    .build())));
  }

  @Test
  public void matchNonGlobNotFound() {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(s3Options());

    S3ResourceId path = S3ResourceId.fromUri("s3://testbucket/testdirectory/nonexistentfile");
    AmazonS3Exception exception = new AmazonS3Exception("mock exception");
    exception.setStatusCode(404);
    when(s3FileSystem
            .getAmazonS3Client()
            .getObjectMetadata(
                argThat(
                    new GetObjectMetadataRequestMatcher(
                        new GetObjectMetadataRequest(path.getBucket(), path.getKey())))))
        .thenThrow(exception);

    MatchResult result = s3FileSystem.matchNonGlobPath(path);
    assertThat(
        result,
        MatchResultMatcher.create(MatchResult.Status.NOT_FOUND, new FileNotFoundException()));
  }

  @Test
  public void matchNonGlobForbidden() {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(s3Options());

    AmazonS3Exception exception = new AmazonS3Exception("mock exception");
    exception.setStatusCode(403);
    S3ResourceId path = S3ResourceId.fromUri("s3://testbucket/testdirectory/keyname");
    when(s3FileSystem
            .getAmazonS3Client()
            .getObjectMetadata(
                argThat(
                    new GetObjectMetadataRequestMatcher(
                        new GetObjectMetadataRequest(path.getBucket(), path.getKey())))))
        .thenThrow(exception);

    assertThat(
        s3FileSystem.matchNonGlobPath(path),
        MatchResultMatcher.create(MatchResult.Status.ERROR, new IOException(exception)));
  }

  static class ListObjectsV2RequestArgumentMatches extends ArgumentMatcher<ListObjectsV2Request> {
    private final ListObjectsV2Request expected;

    ListObjectsV2RequestArgumentMatches(ListObjectsV2Request expected) {
      this.expected = checkNotNull(expected);
    }

    @Override
    public boolean matches(Object argument) {
      if (argument instanceof ListObjectsV2Request) {
        ListObjectsV2Request actual = (ListObjectsV2Request) argument;
        return expected.getBucketName().equals(actual.getBucketName())
            && expected.getPrefix().equals(actual.getPrefix())
            && (expected.getContinuationToken() == null
                ? actual.getContinuationToken() == null
                : expected.getContinuationToken().equals(actual.getContinuationToken()));
      }
      return false;
    }
  }

  @Test
  public void matchGlob() throws IOException {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(s3Options());

    S3ResourceId path = S3ResourceId.fromUri("s3://testbucket/foo/bar*baz");

    ListObjectsV2Request firstRequest =
        new ListObjectsV2Request()
            .withBucketName(path.getBucket())
            .withPrefix(path.getKeyNonWildcardPrefix())
            .withContinuationToken(null);

    // Expected to be returned; prefix and wildcard/regex match
    S3ObjectSummary firstMatch = new S3ObjectSummary();
    firstMatch.setBucketName(path.getBucket());
    firstMatch.setKey("foo/bar0baz");
    firstMatch.setSize(100);

    // Expected to not be returned; prefix matches, but substring after wildcard does not
    S3ObjectSummary secondMatch = new S3ObjectSummary();
    secondMatch.setBucketName(path.getBucket());
    secondMatch.setKey("foo/bar1qux");
    secondMatch.setSize(200);

    // Expected first request returns continuation token
    ListObjectsV2Result firstResult = new ListObjectsV2Result();
    firstResult.setNextContinuationToken("token");
    firstResult.getObjectSummaries().add(firstMatch);
    firstResult.getObjectSummaries().add(secondMatch);
    when(s3FileSystem
            .getAmazonS3Client()
            .listObjectsV2(argThat(new ListObjectsV2RequestArgumentMatches(firstRequest))))
        .thenReturn(firstResult);

    // Expect second request with continuation token
    ListObjectsV2Request secondRequest =
        new ListObjectsV2Request()
            .withBucketName(path.getBucket())
            .withPrefix(path.getKeyNonWildcardPrefix())
            .withContinuationToken("token");

    // Expected to be returned; prefix and wildcard/regex match
    S3ObjectSummary thirdMatch = new S3ObjectSummary();
    thirdMatch.setBucketName(path.getBucket());
    thirdMatch.setKey("foo/bar2baz");
    thirdMatch.setSize(300);

    // Expected second request returns third prefix match and no continuation token
    ListObjectsV2Result secondResult = new ListObjectsV2Result();
    secondResult.setNextContinuationToken(null);
    secondResult.getObjectSummaries().add(thirdMatch);
    when(s3FileSystem
            .getAmazonS3Client()
            .listObjectsV2(argThat(new ListObjectsV2RequestArgumentMatches(secondRequest))))
        .thenReturn(secondResult);

    // Expect object metadata queries for content encoding
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentEncoding("");
    when(s3FileSystem.getAmazonS3Client().getObjectMetadata(anyObject())).thenReturn(metadata);

    assertThat(
        s3FileSystem.matchGlobPaths(ImmutableList.of(path)).get(0),
        MatchResultMatcher.create(
            ImmutableList.of(
                MatchResult.Metadata.builder()
                    .setIsReadSeekEfficient(true)
                    .setResourceId(
                        S3ResourceId.fromComponents(
                            firstMatch.getBucketName(), firstMatch.getKey()))
                    .setSizeBytes(firstMatch.getSize())
                    .build(),
                MatchResult.Metadata.builder()
                    .setIsReadSeekEfficient(true)
                    .setResourceId(
                        S3ResourceId.fromComponents(
                            thirdMatch.getBucketName(), thirdMatch.getKey()))
                    .setSizeBytes(thirdMatch.getSize())
                    .build())));
  }

  @Test
  public void matchGlobWithSlashes() throws IOException {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(s3Options());

    S3ResourceId path = S3ResourceId.fromUri("s3://testbucket/foo/bar\\baz*");

    ListObjectsV2Request request =
        new ListObjectsV2Request()
            .withBucketName(path.getBucket())
            .withPrefix(path.getKeyNonWildcardPrefix())
            .withContinuationToken(null);

    // Expected to be returned; prefix and wildcard/regex match
    S3ObjectSummary firstMatch = new S3ObjectSummary();
    firstMatch.setBucketName(path.getBucket());
    firstMatch.setKey("foo/bar\\baz0");
    firstMatch.setSize(100);

    // Expected to not be returned; prefix matches, but substring after wildcard does not
    S3ObjectSummary secondMatch = new S3ObjectSummary();
    secondMatch.setBucketName(path.getBucket());
    secondMatch.setKey("foo/bar/baz1");
    secondMatch.setSize(200);

    // Expected first request returns continuation token
    ListObjectsV2Result result = new ListObjectsV2Result();
    result.getObjectSummaries().add(firstMatch);
    result.getObjectSummaries().add(secondMatch);
    when(s3FileSystem
            .getAmazonS3Client()
            .listObjectsV2(argThat(new ListObjectsV2RequestArgumentMatches(request))))
        .thenReturn(result);

    // Expect object metadata queries for content encoding
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentEncoding("");
    when(s3FileSystem.getAmazonS3Client().getObjectMetadata(anyObject())).thenReturn(metadata);

    assertThat(
        s3FileSystem.matchGlobPaths(ImmutableList.of(path)).get(0),
        MatchResultMatcher.create(
            ImmutableList.of(
                MatchResult.Metadata.builder()
                    .setIsReadSeekEfficient(true)
                    .setResourceId(
                        S3ResourceId.fromComponents(
                            firstMatch.getBucketName(), firstMatch.getKey()))
                    .setSizeBytes(firstMatch.getSize())
                    .build())));
  }

  @Test
  public void matchVariousInvokeThreadPool() throws IOException {
    S3FileSystem s3FileSystem = buildMockedS3FileSystem(s3Options());

    AmazonS3Exception notFoundException = new AmazonS3Exception("mock exception");
    notFoundException.setStatusCode(404);
    S3ResourceId pathNotExist =
        S3ResourceId.fromUri("s3://testbucket/testdirectory/nonexistentfile");
    when(s3FileSystem
            .getAmazonS3Client()
            .getObjectMetadata(
                argThat(
                    new GetObjectMetadataRequestMatcher(
                        new GetObjectMetadataRequest(
                            pathNotExist.getBucket(), pathNotExist.getKey())))))
        .thenThrow(notFoundException);

    AmazonS3Exception forbiddenException = new AmazonS3Exception("mock exception");
    forbiddenException.setStatusCode(403);
    S3ResourceId pathForbidden =
        S3ResourceId.fromUri("s3://testbucket/testdirectory/forbiddenfile");
    when(s3FileSystem
            .getAmazonS3Client()
            .getObjectMetadata(
                argThat(
                    new GetObjectMetadataRequestMatcher(
                        new GetObjectMetadataRequest(
                            pathForbidden.getBucket(), pathForbidden.getKey())))))
        .thenThrow(forbiddenException);

    S3ResourceId pathExist = S3ResourceId.fromUri("s3://testbucket/testdirectory/filethatexists");
    ObjectMetadata s3ObjectMetadata = new ObjectMetadata();
    s3ObjectMetadata.setContentLength(100);
    s3ObjectMetadata.setContentEncoding("not-gzip");
    when(s3FileSystem
            .getAmazonS3Client()
            .getObjectMetadata(
                argThat(
                    new GetObjectMetadataRequestMatcher(
                        new GetObjectMetadataRequest(pathExist.getBucket(), pathExist.getKey())))))
        .thenReturn(s3ObjectMetadata);

    S3ResourceId pathGlob = S3ResourceId.fromUri("s3://testbucket/path/part*");

    S3ObjectSummary foundListObject = new S3ObjectSummary();
    foundListObject.setBucketName(pathGlob.getBucket());
    foundListObject.setKey("path/part-0");
    foundListObject.setSize(200);

    ListObjectsV2Result listObjectsResult = new ListObjectsV2Result();
    listObjectsResult.setNextContinuationToken(null);
    listObjectsResult.getObjectSummaries().add(foundListObject);
    when(s3FileSystem.getAmazonS3Client().listObjectsV2(notNull(ListObjectsV2Request.class)))
        .thenReturn(listObjectsResult);

    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentEncoding("");
    when(s3FileSystem
            .getAmazonS3Client()
            .getObjectMetadata(
                argThat(
                    new GetObjectMetadataRequestMatcher(
                        new GetObjectMetadataRequest(pathGlob.getBucket(), "path/part-0")))))
        .thenReturn(metadata);

    assertThat(
        s3FileSystem.match(
            ImmutableList.of(
                pathNotExist.toString(),
                pathForbidden.toString(),
                pathExist.toString(),
                pathGlob.toString())),
        contains(
            MatchResultMatcher.create(MatchResult.Status.NOT_FOUND, new FileNotFoundException()),
            MatchResultMatcher.create(
                MatchResult.Status.ERROR, new IOException(forbiddenException)),
            MatchResultMatcher.create(100, pathExist, true),
            MatchResultMatcher.create(
                200,
                S3ResourceId.fromComponents(pathGlob.getBucket(), foundListObject.getKey()),
                true)));
  }

  /** A mockito argument matcher to implement equality on GetObjectMetadataRequest. */
  private static class GetObjectMetadataRequestMatcher
      extends ArgumentMatcher<GetObjectMetadataRequest> {
    private final GetObjectMetadataRequest expected;

    GetObjectMetadataRequestMatcher(GetObjectMetadataRequest expected) {
      this.expected = expected;
    }

    @Override
    public boolean matches(Object obj) {
      if (!(obj instanceof GetObjectMetadataRequest)) {
        return false;
      }
      GetObjectMetadataRequest actual = (GetObjectMetadataRequest) obj;
      return actual.getBucketName().equals(expected.getBucketName())
          && actual.getKey().equals(expected.getKey());
    }
  }
}
