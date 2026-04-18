package com.brunohensel.c2pareader

import com.brunohensel.c2pareader.asset.AssetReader
import com.brunohensel.c2pareader.asset.BmffAssetReader
import com.brunohensel.c2pareader.asset.C2paSidecarReader
import com.brunohensel.c2pareader.asset.JpegAssetReader
import com.brunohensel.c2pareader.asset.MalformedAssetException
import com.brunohensel.c2pareader.asset.PngAssetReader
import com.brunohensel.c2pareader.asset.RiffAssetReader
import com.brunohensel.c2pareader.asset.TiffAssetReader
import com.brunohensel.c2pareader.cbor.CborDecodeException
import com.brunohensel.c2pareader.format.FormatDetector
import com.brunohensel.c2pareader.format.ImageFormat
import com.brunohensel.c2pareader.jumbf.JumbfParseException
import com.brunohensel.c2pareader.jumbf.JumbfParser
import com.brunohensel.c2pareader.manifest.ManifestBuildException
import com.brunohensel.c2pareader.manifest.ManifestJsonBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Public entry point of the C2PA reader library.
 *
 * Pass a full image `ByteArray`; receive either a JSON string conforming to the
 * [C2PA reader-schema](https://opensource.contentauthenticity.org/docs/manifest/json-ref/reader-schema)
 * or a typed [C2paError]. The call is synchronous and CPU-bound; wrap in your own
 * dispatcher if you need to move it off the main thread.
 */
public object C2paReader {

    // Minimal Json instance — default settings are compact output; callers that want a pretty
    // JSON can re-parse + re-serialize themselves.
    private val json = Json { encodeDefaults = false }

    public fun read(bytes: ByteArray): C2paResult {
        val reader: AssetReader = when (FormatDetector.detect(bytes)) {
            ImageFormat.Unknown -> return C2paResult.Failure(C2paError.UnsupportedFormat)
            ImageFormat.Jpeg -> JpegAssetReader
            ImageFormat.Png -> PngAssetReader
            ImageFormat.C2paSidecar -> C2paSidecarReader
            ImageFormat.Heif -> BmffAssetReader
            ImageFormat.Webp -> RiffAssetReader
            ImageFormat.Tiff -> TiffAssetReader
        }

        val jumbfBytes = try {
            reader.extractJumbf(bytes)
        } catch (e: MalformedAssetException) {
            return C2paResult.Failure(C2paError.Malformed(e.reason))
        } ?: return C2paResult.Failure(C2paError.NoManifest)

        val tree = try {
            JumbfParser.parse(jumbfBytes)
        } catch (e: JumbfParseException) {
            return C2paResult.Failure(C2paError.JumbfError(e.reason))
        }

        val manifestJson = try {
            ManifestJsonBuilder.build(tree)
        } catch (e: CborDecodeException) {
            return C2paResult.Failure(C2paError.CborError(e.reason))
        } catch (e: ManifestBuildException) {
            return C2paResult.Failure(C2paError.Malformed(e.reason))
        }

        return C2paResult.Success(json.encodeToString(JsonObject.serializer(), manifestJson))
    }
}
