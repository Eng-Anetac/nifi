/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.minifi.c2.cache.s3;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

import org.apache.nifi.minifi.c2.api.ConfigurationProviderException;
import org.apache.nifi.minifi.c2.api.cache.WriteableConfiguration;

public class S3WritableConfiguration implements WriteableConfiguration {
  private final AmazonS3 s3;
  private final String version;
  private final String bucketName;
  private final String objectKey;

  /**
   * Creates a new S3 writable configuration.
   * @param s3 An S3 {@link AmazonS3 client}.
   * @param s3ObjectSummary The S3 object {@link S3ObjectSummary summary}.
   * @param version The version of the configuration.
   */
  public S3WritableConfiguration(AmazonS3 s3, S3ObjectSummary s3ObjectSummary, String version) {

    this.s3 = s3;
    this.bucketName = s3ObjectSummary.getBucketName();
    this.objectKey = s3ObjectSummary.getKey();
    this.version = version;

  }

  /**
   * Creates a new S3 writable configuration.
   * @param s3 An S3 {@link AmazonS3 client}.
   * @param s3Object The S3 {@link S3Object object}.
   * @param version The version of the configuration.
   */
  public S3WritableConfiguration(AmazonS3 s3, S3Object s3Object, String version) {
    this.s3 = s3;
    this.bucketName = s3Object.getBucketName();
    this.objectKey = s3Object.getKey();
    this.version = version;

  }

  @Override
  public String getVersion() {
    return version;
  }

  @Override
  public boolean exists() {
    return s3.doesObjectExist(bucketName, objectKey);
  }

  @Override
  public OutputStream getOutputStream() throws ConfigurationProviderException {
    S3Object s3Object = s3.getObject(bucketName, objectKey);
    return new S3OutputStream(s3Object.getBucketName(), s3Object.getKey(), s3);
  }

  @Override
  public InputStream getInputStream() throws ConfigurationProviderException {
    S3Object s3Object = s3.getObject(bucketName, objectKey);
    return s3Object.getObjectContent();
  }

  @Override
  public URL getURL() throws ConfigurationProviderException {
    return s3.getUrl(bucketName, objectKey);
  }

  @Override
  public String getName() {
    return objectKey;
  }

  @Override
  public String toString() {
    return "FileSystemWritableConfiguration{objectKey=" + objectKey
      + ", version='" + version + "'}";
  }

}
