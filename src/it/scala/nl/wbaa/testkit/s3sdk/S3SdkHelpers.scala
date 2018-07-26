package nl.wbaa.testkit.s3sdk

import java.io.File

import akka.http.scaladsl.model.Uri.Authority
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.transfer.TransferManagerBuilder
import com.amazonaws.services.s3.transfer.model.UploadResult
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}

import scala.collection.JavaConverters._


trait S3SdkHelpers {
  def getAmazonS3(awsSignerType: String, authority: Authority): AmazonS3 = {
    val cliConf = new ClientConfiguration()
    cliConf.setMaxErrorRetry(1)
    cliConf.setSignerOverride(awsSignerType)

    AmazonS3ClientBuilder
      .standard()
      .withClientConfiguration(cliConf)
      .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("accesskey", "secretkey")))
      .withEndpointConfiguration(new EndpointConfiguration(s"http://${authority.host.address}:${authority.port}", "us-west-2"))
      .build()
  }

  def getKeysInBucket(sdk: AmazonS3, bucket: String = "demobucket"): List[String] =
    sdk
      .listObjectsV2(bucket)
      .getObjectSummaries
      .asScala.toList
      .map(_.getKey)


  def doMultiPartUpload(sdk: AmazonS3, file: String, key: String): UploadResult = {
    val upload = TransferManagerBuilder
      .standard()
      .withS3Client(sdk)
      .build()
      .upload("demobucket", key, new File(file))

    upload.waitForUploadResult()
  }
}
