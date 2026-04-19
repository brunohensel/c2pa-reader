package com.brunohensel.c2pareader.manifest

import com.brunohensel.c2pareader.C2paError
import com.brunohensel.c2pareader.C2paResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManifestSummarizerTest {

    @Test
    fun failureReturnsNull() {
        assertNull(summarize(C2paResult.Failure(C2paError.NoManifest)))
    }

    @Test
    fun emptyManifestsReturnsNull() {
        val json = """{"manifests":{}}"""
        assertNull(summarize(C2paResult.Success(json)))
    }

    @Test
    fun fireflyJpegSurfacesTrainedAlgorithmicMediaAndToolName() {
        val json = """
        {
          "active_manifest": "urn:uuid:abc",
          "manifests": {
            "urn:uuid:abc": {
              "claim_generator": "Adobe_Firefly",
              "title": "Generated image",
              "format": "image/jpeg",
              "assertions": [
                {
                  "label": "c2pa.actions.v2",
                  "data": {
                    "actions": [
                      {
                        "action": "c2pa.created",
                        "softwareAgent": "Adobe Firefly",
                        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val summary = summarize(C2paResult.Success(json))

        assertEquals(
            ManifestSummary(
                aiStatus = AiStatus.AI_GENERATED,
                tool = "Adobe Firefly",
                action = "c2pa.created",
                title = "Generated image",
                format = "image/jpeg",
            ),
            summary,
        )
    }

    @Test
    fun tiffOpenedWithAlgorithmicMediaIsAiEdited() {
        val json = """
        {
          "active_manifest": "urn:uuid:t1",
          "manifests": {
            "urn:uuid:t1": {
              "claim_generator": "test/1.0",
              "title": "100kb-signed.tiff",
              "format": "image/tiff",
              "assertions": [
                {
                  "label": "c2pa.actions.v2",
                  "data": {
                    "actions": [
                      {
                        "action": "c2pa.opened",
                        "softwareAgent": { "name": "TestApp", "version": "1.0" },
                        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/algorithmicMedia"
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        assertEquals(
            ManifestSummary(
                aiStatus = AiStatus.AI_EDITED,
                tool = "TestApp",
                action = "c2pa.opened",
                title = "100kb-signed.tiff",
                format = "image/tiff",
            ),
            summarize(C2paResult.Success(json)),
        )
    }

    @Test
    fun chatgptAggregatesAiGeneratedFromNonActiveManifest() {
        // Post-filter view of ChatGPT fixture: active has opened-only action; the child
        // manifest carries the c2pa.created + GPT-4o softwareAgent + trainedAlgorithmicMedia.
        // With ingredients stripped, the child is still reachable via `manifests`.
        val json = """
        {
          "active_manifest": "urn:c2pa:parent",
          "manifests": {
            "urn:c2pa:parent": {
              "title": "image.png",
              "assertions": [
                {
                  "label": "c2pa.actions.v2",
                  "data": { "actions": [ { "action": "c2pa.opened" } ] }
                }
              ]
            },
            "urn:c2pa:child": {
              "title": "image.png",
              "assertions": [
                {
                  "label": "c2pa.actions.v2",
                  "data": {
                    "actions": [
                      {
                        "action": "c2pa.created",
                        "softwareAgent": { "name": "GPT-4o" },
                        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"
                      },
                      { "action": "c2pa.converted" }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val summary = summarize(C2paResult.Success(json))

        assertEquals(AiStatus.AI_GENERATED, summary?.aiStatus)
        assertEquals("GPT-4o", summary?.tool)
        assertEquals("c2pa.created", summary?.action)
        assertEquals("image.png", summary?.title)
    }

    @Test
    fun createdWithoutDigitalSourceTypeIsUnknownAndFallsBackToClaimGenerator() {
        val json = """
        {
          "active_manifest": "urn:uuid:w",
          "manifests": {
            "urn:uuid:w": {
              "claim_generator": "c2pareader-phase3-test/1.0.0",
              "title": "Phase 3 Test",
              "format": "image/webp",
              "assertions": [
                {
                  "label": "c2pa.actions.v2",
                  "data": { "actions": [ { "action": "c2pa.created" } ] }
                }
              ]
            }
          }
        }
        """.trimIndent()

        assertEquals(
            ManifestSummary(
                aiStatus = AiStatus.UNKNOWN,
                tool = "c2pareader-phase3-test/1.0.0",
                action = "c2pa.created",
                title = "Phase 3 Test",
                format = "image/webp",
            ),
            summarize(C2paResult.Success(json)),
        )
    }
}
