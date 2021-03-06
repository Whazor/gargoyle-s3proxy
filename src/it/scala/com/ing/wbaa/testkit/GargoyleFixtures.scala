package com.ing.wbaa.testkit

import java.io.{File, RandomAccessFile}

import com.amazonaws.services.s3.AmazonS3
import com.ing.wbaa.testkit.awssdk.S3SdkHelpers
import org.scalatest.Assertion

import scala.util.Random

trait GargoyleFixtures extends S3SdkHelpers {

  /**
    * Fixture to create a test file with a certain size for your testcase
    *
    * @param size     Size in Bytes of the file you want to test
    * @param testCode Code that accepts the name of the created file
    * @return Assertion
    */
  def withFile(size: Long)(testCode: String => Assertion): Assertion = {
    val filename = "testfile.tst"
    val file = new RandomAccessFile(filename, "rw")
    file.setLength(size)
    file.close()

    try {
      testCode(filename)
    } finally {
      new File(filename).delete()
    }
  }

  /**
    * Fixture that creates a bucket for a test and deletes it after
    *
    * @param sdk An Amazon S3 sdk object pointed to a running cluster
    * @param testCode Code that accepts the bucket name that was created
    * @return Assertion
    */
  def withBucket(s3Client: AmazonS3)(testCode: String => Assertion): Assertion = {
    val testBucket = s"justabucket${(Random.alphanumeric take 20).mkString.toLowerCase}"
    s3Client.createBucket(testBucket)
    try testCode(testBucket)
    finally {
      cleanBucket(s3Client, testBucket)
      s3Client.deleteBucket(testBucket)
    }
  }

  private def cleanBucket(s3Client: AmazonS3, bucketName: String) = {
    import scala.collection.JavaConverters._
    s3Client.listObjectsV2(bucketName).getObjectSummaries.asScala.toList.map(_.getKey).foreach { key =>
      s3Client.deleteObject(bucketName, key)
    }
  }
}
