package com.ing.wbaa.gargoyle.proxy.handler

import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.Uri.{Authority, Host}
import com.amazonaws.services.s3.AmazonS3
import com.ing.wbaa.gargoyle.proxy.GargoyleS3Proxy
import com.ing.wbaa.gargoyle.proxy.config.{GargoyleAtlasSettings, GargoyleHttpSettings, GargoyleStorageS3Settings}
import com.ing.wbaa.gargoyle.proxy.data._
import com.ing.wbaa.gargoyle.proxy.provider.LineageProviderAtlas.LineageProviderAtlasException
import com.ing.wbaa.gargoyle.proxy.provider.SignatureProviderAws
import com.ing.wbaa.testkit.GargoyleFixtures
import org.scalatest._

import scala.collection.JavaConverters._
import scala.concurrent.Future

class RequestHandlerS3ItTest extends AsyncWordSpec with DiagrammedAssertions with GargoyleFixtures {
  final implicit val testSystem: ActorSystem = ActorSystem.create("test-system")

  // Settings for tests:
  //  - Force a random port to listen on.
  //  - Explicitly bind to loopback, irrespective of any default value.
  val gargoyleHttpSettings: GargoyleHttpSettings = new GargoyleHttpSettings(testSystem.settings.config) {
    override val httpPort: Int = 0
    override val httpBind: String = "127.0.0.1"
  }

  /**
    * Fixture for starting and stopping a test proxy that tests can interact with.
    *
    * @param awsSignerType Signer type for aws sdk to use
    * @param testCode      Code that accepts the created sdk
    * @return Assertion
    */
  def withS3SdkToMockProxy(awsSignerType: String)(testCode: AmazonS3 => Assertion): Future[Assertion] = {
    val proxy: GargoyleS3Proxy = new GargoyleS3Proxy with RequestHandlerS3 with SignatureProviderAws {
      override implicit lazy val system: ActorSystem = testSystem
      override val httpSettings: GargoyleHttpSettings = gargoyleHttpSettings
      override def isUserAuthorizedForRequest(request: S3Request, user: User): Boolean = true
      override val storageS3Settings: GargoyleStorageS3Settings = GargoyleStorageS3Settings(testSystem)
      override val atlasSettings: GargoyleAtlasSettings = new GargoyleAtlasSettings(system.settings.config)

      override def areCredentialsActive(awsRequestCredential: AwsRequestCredential): Future[Option[User]] =
        Future(Some(User(UserRawJson("userId", Some("group"), "accesskey", "secretkey"))))

      def createLineageFromRequest(httpRequest: HttpRequest, userSTS: User): Future[LineagePostGuidResponse] = Future.failed(LineageProviderAtlasException("Create lineage failed"))
    }
    proxy.startup.map { binding =>
      try testCode(getAmazonS3(
        awsSignerType = awsSignerType,
        authority = Authority(Host(binding.localAddress.getAddress), binding.localAddress.getPort)
      ))
      finally proxy.shutdown()
    }
  }

  // TODO: Expand with different signer types
  val awsSignerTypes = List(
    "S3SignerType" //,
    //      SignerFactory.NO_OP_SIGNER,
    //      SignerFactory.QUERY_STRING_SIGNER,
    //      SignerFactory.VERSION_FOUR_SIGNER,
    //      SignerFactory.VERSION_FOUR_UNSIGNED_PAYLOAD_SIGNER,
    //      SignerFactory.VERSION_THREE_SIGNER
  )

  awsSignerTypes.foreach { awsSignerType =>

    "S3 Proxy" should {
      s"proxy with $awsSignerType" that {

        "list the current buckets" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            assert(sdk.listBuckets().asScala.toList.map(_.getName).contains(testBucket))
          }
        }

        "create and remove a bucket" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          val testBucket = "createbuckettest"
          sdk.createBucket(testBucket)
          assert(sdk.listBuckets().asScala.toList.map(_.getName).contains(testBucket))
          sdk.deleteBucket(testBucket)
          assert(!sdk.listBuckets().asScala.toList.map(_.getName).contains(testBucket))
        }

        "list files in a bucket" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            val testKey = "keyListFiles"

            sdk.putObject(testBucket, testKey, "content")
            val resultV2 = sdk.listObjectsV2(testBucket).getObjectSummaries.asScala.toList.map(_.getKey)
            val result = sdk.listObjects(testBucket).getObjectSummaries.asScala.toList.map(_.getKey)

            assert(resultV2.contains(testKey))
            assert(result.contains(testKey))
          }
        }

        "check if bucket exists" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            assert(sdk.doesBucketExistV2(testBucket))
          }
        }

        "put, get and delete an object from a bucket" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            withFile(1024 * 1024) { filename =>
              val testKeyContent = "keyPutFileByContent"
              val testKeyFile = "keyPutFileByFile"
              val testContent = "content"

              // PUT
              sdk.putObject(testBucket, testKeyContent, testContent)
              sdk.putObject(testBucket, testKeyFile, new File(filename))

              // GET
              val checkContent = sdk.getObjectAsString(testBucket, testKeyContent)
              assert(checkContent == testContent)
              val keys1 = getKeysInBucket(sdk, testBucket)
              List(testKeyContent, testKeyFile).map(k => assert(keys1.contains(k)))

              // DELETE
              sdk.deleteObject(testBucket, testKeyContent)
              val keys2 = getKeysInBucket(sdk, testBucket)
              assert(!keys2.contains(testKeyContent))
            }
          }
        }

        // TODO: Fix proxy for copyObject function
        //        "check if object can be copied" in {
        //          sdk.putObject(bucketInCeph, "keyCopyOrg", new File("file1mb.test"))
        //          sdk.copyObject(bucketInCeph, "keyCopyOrg", "newbucket", "keyCopyDest")
        //
        //          val keys1 = getKeysInBucket(bucketInCeph)
        //          assert(!keys1.contains("keyCopyOrg"))
        //          val keys2 = getKeysInBucket("newbucket")
        //          assert(keys2.contains("keyCopyDest"))
        //        }

        // TODO: Fix proxy for doesObjectExists function
        //        "check if object exists in bucket" in {
        //          sdk.putObject(bucketInCeph, "keyCheckObjectExists", "content")
        //          assert(sdk.doesObjectExist(bucketInCeph, "key"))
        //        }

        "put a 1MB file in a bucket (multi part upload)" in withS3SdkToMockProxy(awsSignerType) { sdk =>
          withBucket(sdk) { testBucket =>
            withFile(1024 * 1024) { filename =>
              val testKey = "keyMultiPart1MB"
              doMultiPartUpload(sdk, testBucket, filename, testKey)
              val objectKeys = getKeysInBucket(sdk, testBucket)
              assert(objectKeys.contains(testKey))
            }
          }
        }

        // TODO: reenable, sometimes fails still with `upload canceled` error
        //        "put a 100MB file in a bucket (multi part upload)" in {
        //          doMultiPartUpload("file100mb.test", "keyMultiPart100MB")
        //
        //          val objectKeys = getKeysInBucket()
        //          assert(objectKeys.contains("keyMultiPart100MB"))
        //        }

        // TODO: Fix 1GB multi part upload
        //        "put a 1GB file in a bucket (multi part upload)" in {
        //          doMultiPartUpload("file1gb.test", "keyMultiPart1GB")
        //
        //          val objectKeys = getKeysInBucket()
        //          assert(objectKeys.contains("keyMultiPart1GB"))
        //        }
      }
    }
  }
}
